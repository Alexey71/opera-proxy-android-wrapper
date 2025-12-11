package com.example.operaproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class ProxyVpnService : VpnService() {
    private var process: Process? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunFd: Int = -1
    private var tun2proxyThread: Thread? = null

    // Параметры
    private var currentCountry = "EU"
    private var tunDnsServer = "8.8.8.8"
    private var allowedApps: ArrayList<String>? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cleanedUp = false

    // Расширенные параметры
    private var bindAddress = "127.0.0.1:1080"
    private var bootstrapDns = ""
    private var fakeSni = ""
    private var upstreamProxy = ""
    private var testUrl = ""
    private var verbosity = 20
	private var tunDnsStrategy = 1 
    private var socksMode = false
    private var proxyOnlyMode = false

    // Ручной режим
    private var manualCmdMode = false
    private var customCmdString = ""

    companion object {
        @JvmStatic
        external fun startTun2proxy(
			service: ProxyVpnService,
            proxyUrl: String,
            tunFd: Int,
            closeFdOnDrop: Boolean,
            tunMtu: Char,
            dnsStrategy: Int,
            verbosity: Int
        ): Int

        // Добавляем метод остановки
        @JvmStatic
        external fun stopTun2proxy(): Int

        init {
            System.loadLibrary("tun2proxy")
            System.loadLibrary("native-lib")
        }

        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Считываем verbosity сразу, чтобы корректно фильтровать логи
        if (intent != null) {
            verbosity = intent.getIntExtra("VERBOSITY", 20)
        }

        if (action == "STOP_VPN") {
            stopRequested = true
            logToUI("ЗАПРОС НА ОСТАНОВКУ СЛУЖБЫ", fromBinary = false)
            
            // Запускаем остановку в отдельном потоке, 
            // чтобы не морозить интерфейс
            Thread {
                stopVpn()
                stopSelf()
            }.start()

            return START_NOT_STICKY
        }

        if (intent != null) {
            currentCountry = intent.getStringExtra("COUNTRY") ?: "EU"
            tunDnsServer = intent.getStringExtra("DNS") ?: "8.8.8.8"
            allowedApps = intent.getStringArrayListExtra("ALLOWED_APPS")

            bindAddress = intent.getStringExtra("BIND_ADDRESS") ?: "127.0.0.1:1080"
            bootstrapDns = intent.getStringExtra("BOOTSTRAP_DNS") ?: ""
            fakeSni = intent.getStringExtra("FAKE_SNI") ?: ""
            upstreamProxy = intent.getStringExtra("UPSTREAM_PROXY") ?: ""
            socksMode = intent.getBooleanExtra("SOCKS_MODE", false)
            proxyOnlyMode = intent.getBooleanExtra("PROXY_ONLY", false)
            testUrl = intent.getStringExtra("TEST_URL") ?: ""
            manualCmdMode = intent.getBooleanExtra("MANUAL_CMD_MODE", false)
            customCmdString = intent.getStringExtra("CUSTOM_CMD_STRING") ?: ""
			tunDnsStrategy = intent.getIntExtra("TUN2PROXY_DNS_STRATEGY", 1)

            if (manualCmdMode) {
                logDebug("[CONFIG] ВКЛЮЧЕН РУЧНОЙ РЕЖИМ КОМАНДЫ")
                logDebug("[CONFIG] CUSTOM CMD: $customCmdString")
            }

            logToUI("[CONFIG] Страна: $currentCountry")
            logToUI("[CONFIG] VPN DNS: $tunDnsServer")
            logToUI("[CONFIG] Apps count: ${allowedApps?.size ?: 0}")

            if (verbosity <= 10) {
                logToUI("=== РАСШИРЕННЫЕ НАСТРОЙКИ ===")
                logToUI("[ADV] BIND: $bindAddress")
                logToUI("[ADV] BOOTSTRAP DNS: $bootstrapDns")
                logToUI("[ADV] FAKE SNI: $fakeSni")
                logToUI("[ADV] UPSTREAM PROXY: $upstreamProxy")
                logToUI("[ADV] MODE: ${if (socksMode) "SOCKS" else "HTTP"}")
                logToUI("[ADV] TEST URL: $testUrl")
				logToUI("[ADV] TUN DNS STRATEGY: $tunDnsStrategy (0=Virt, 1=TCP, 2=Direct)")
                logToUI("[ADV] VERBOSITY: $verbosity")
            }
        }

        startForegroundNotification()

        if (!isRunning) {
            isRunning = true
            stopRequested = false // Сбрасываем флаг остановки при запуске
            cleanedUp = false
            notifyStatusChange()

            // 1. Запускаем бинарник прокси в отдельном потоке
            Thread {
                runBinary()
            }.start()

            // 2. В отдельном потоке ждем поднятия порта, затем поднимаем VPN
            if (!proxyOnlyMode) {
                Thread {
                    waitForProxyAndEstablishVpn()
                }.start()
            } else {
                val mode = if (socksMode) "SOCKS5-прокси без VPN" else "HTTP-прокси без VPN"
                logToUI("[Proxy] Режим Proxy Only: $mode. Ожидание запуска порта...")
            }
        } else {
            logToUI("[WARNING] Сервис уже запущен.")
            notifyStatusChange()
        }
        return START_STICKY
    }

    /**
     * Ждет, пока локальный порт прокси (bindAddress) станет доступен,
     * и только после этого запускает VPN.
     */
    private fun waitForProxyAndEstablishVpn() {
        val hostPort = parseBindAddress(bindAddress)
        val host = hostPort.first
        val port = hostPort.second
        
        logToUI("[INIT] Ожидание запуска прокси на $host:$port...", fromBinary = false)

        var retries = 0
        val maxRetries = 120 // 120 * 600ms = 72 секунды ожидания
        var isConnected = false

        while (retries < maxRetries && isRunning && !stopRequested) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 200)
                    isConnected = true
                }
            } catch (e: Exception) {
                // Порт пока закрыт
            }

            if (isConnected) break

            try {
                Thread.sleep(600)
            } catch (e: InterruptedException) {
                break
            }
            retries++
        }

        if (isConnected && isRunning && !stopRequested) {
            logToUI("[INIT] Порт $port открыт. Поднимаем VPN интерфейс...", fromBinary = false)
            
            // establishVpn должен вызываться, когда мы уверены, что прокси готов
            val mode = if (socksMode) "TUN → SOCKS5(tun2proxy)" else "TUN → HTTP(tun2proxy)"
            logToUI("[VPN] Режим: $mode")
            establishVpn()
        } else {
            if (isRunning && !stopRequested) {
                logToUI("[ERROR] Не удалось дождаться запуска локального прокси. VPN не создан.", fromBinary = false)
                // Можно инициировать остановку, если прокси не поднялся
                stopProxyServiceSelf()
            }
        }
    }

    private fun parseBindAddress(addr: String): Pair<String, Int> {
        return try {
            if (addr.contains(":")) {
                val parts = addr.split(":")
                Pair(parts[0], parts[1].toInt())
            } else {
                Pair("127.0.0.1", 1080)
            }
        } catch (e: Exception) {
            Pair("127.0.0.1", 1080)
        }
    }
    
    private fun stopProxyServiceSelf() {
        val intent = Intent(this, ProxyVpnService::class.java)
        intent.action = "STOP_VPN"
        startService(intent)
    }

    private fun startForegroundNotification() {
        val channelId = "opera_vpn_channel"
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            mgr?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ProxyVpnService::class.java).apply { action = "STOP_VPN" }
        val pendingStop = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingMain = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeTextRes = when {
            proxyOnlyMode && socksMode -> R.string.notification_mode_proxy_socks_no_vpn
            proxyOnlyMode && !socksMode -> R.string.notification_mode_proxy_http_no_vpn
            !proxyOnlyMode && socksMode -> R.string.notification_mode_vpn_socks
            else -> R.string.notification_mode_vpn_http
        }
        val modeText = getString(modeTextRes)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title) + " ($currentCountry)")
            // формат: "Режим | bindAddress | DNS-интерфейса"
            .setContentText("$modeText | $bindAddress | $tunDnsServer")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingMain)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                pendingStop
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun establishVpn() {
        if (proxyOnlyMode || stopRequested) return

        try {
            logToUI("[BUILDER] Настройка Builder...", fromBinary = false)
            val builder = Builder()
            builder.setSession("OperaProxy")
            builder.setMtu(1420)
            builder.addAddress("10.1.10.1", 24)

            try {
                builder.addDnsServer(tunDnsServer)
            } catch (e: Exception) {
                builder.addDnsServer("8.8.8.8")
            }
			
			// Фильтруем список приложений
			val safeAllowedApps = allowedApps?.filter { it != packageName } ?: emptyList()

			if (safeAllowedApps.isNotEmpty()) {
				logToUI("[ROUTING] Белый список приложений включен (${safeAllowedApps.size}).")
				for (pkg in safeAllowedApps) {
					try {
						builder.addAllowedApplication(pkg)
					} catch (_: Exception) {}
				}
			} else {
				// Если список пуст или пользователь выбрал только само приложение - 
				// исключаем себя, чтобы избежать петли, и проксируем всё остальное
				builder.addDisallowedApplication(packageName)
			}

            builder.addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                logToUI("[CRITICAL] Не удалось создать VPN-интерфейс")
                stopVpn()
                return
            }

            // Было: tunFd = vpnInterface!!.fd
            // Стало: detachFd(). Мы передаем полное владение дескриптором в Native код.
            
            tunFd = vpnInterface!!.detachFd() 
            if (verbosity <= 10) {
                logToUI("[VPN] TUN fd = $tunFd (detached)", fromBinary = false)
            }

            // запуск tun2proxy
            startTun2proxyWorker()
            if (verbosity <= 10) {
                logToUI("[TUN2PROXY] Запущен обработчик TUN → proxy", fromBinary = false)
            }

        } catch (e: Exception) {
            logToUI("[CRITICAL] Ошибка VPN: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }
	
	fun onTun2ProxyLog(message: String) {
		if (verbosity == 5 || verbosity <= 10) {
			logToUI("[tun2proxy] $message", fromBinary = false)
		}
	}

    private fun startTun2proxyWorker() {
        if (tunFd <= 0) {
            logToUI("[TUN2PROXY] Некорректный tunFd: $tunFd", fromBinary = false)
            return
        }
        
        // Предохранитель от повторного запуска при уже работающем потоке
        if (tun2proxyThread != null && tun2proxyThread?.isAlive == true) {
            if (verbosity <= 10) {
                logToUI("[TUN2PROXY] Повторный запуск заблокирован: поток уже работает", fromBinary = false)
            }
            return
        }
		
        // Оптимизация соединения:
        // Если прокси слушает локально (даже на 0.0.0.0), клиенту tun2proxy
        // безопаснее и быстрее подключаться к 127.0.0.1.
        // Это гарантирует проход через Loopback интерфейс, который всегда доступен.
        val targetHost = if (bindAddress.startsWith("0.0.0.0") || bindAddress.startsWith(":")) {
            "127.0.0.1"
        } else {
            // Если пользователь указал специфичный IP, берем его
            parseBindAddress(bindAddress).first
        }
        
        val targetPort = parseBindAddress(bindAddress).second		

        // Собираем URL прокси для tun2proxy:
        val scheme = if (socksMode) "socks5" else "http"
		// Формируем URL для внутреннего соединения: socks5://127.0.0.1:1080
        val proxyUrl = "$scheme://$targetHost:$targetPort"

        // MTU — тот же, что задаётся Builder'у
        val mtuChar: Char = 1420.toChar()

        // Стратегия DNS берем из переменной класса
        val dnsStrategy = tunDnsStrategy

        // Приводим VERBOSITY к диапазону tun2proxy
        val t2pVerbosity = when {
			verbosity == 5 -> 3 // Wrapper: ставим Info (3) для tun2proxy
            verbosity <= 10 -> 4 // debug
            verbosity <= 20 -> 3 // info
            verbosity <= 30 -> 2 // warn
            verbosity <= 40 -> 1 // error
            else -> 0 // off
        }

        tun2proxyThread = Thread {
            try {
                if (verbosity <= 10) {
                    logToUI("[TUN2PROXY] startTun2proxy($proxyUrl, fd=$tunFd, dns=$dnsStrategy)", fromBinary = false)
                }
                
                val code = startTun2proxy(
					this,
                    proxyUrl,
                    tunFd,
                    true,
                    mtuChar,
                    dnsStrategy,
                    t2pVerbosity
                )

                if (verbosity <= 10) {
                    val msg = when (code) {
                        -1 -> "[TUN2PROXY] Библиотека не загружена или функция не найдена, код -1"
                        else -> "[TUN2PROXY] Завершён с кодом $code"
                    }
                    logToUI(msg, fromBinary = false)
                }
            } catch (e: Throwable) {
                logToUI("[TUN2PROXY ERROR] ${e.message}")
            } finally {
                // Страховка: если поток завершился, обнуляем ссылку
                if (Thread.currentThread() == tun2proxyThread) {
                    tun2proxyThread = null
                }
            }
        }.also { it.start() }
    }

    private fun runBinary() {
        try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val binaryName = "liboperaproxy.so"
            val binaryPath = File(nativeLibDir, binaryName)

            if (!binaryPath.exists()) {
                logToUI("[ERROR] Бинарный файл не найден: ${binaryPath.absolutePath}")
            }

            if (verbosity <= 10) {
                logToUI("[BIN] Поиск бинарника: ${binaryPath.absolutePath}", fromBinary = false)
            }

            val sslFile = File(filesDir, "cacert.pem")
            if (!sslFile.exists()) {
                logToUI("[FS] Копирование сертификата...")
                try {
                    assets.open("cacert.pem").use { input ->
                        FileOutputStream(sslFile).use { o -> input.copyTo(o) }
                    }
                } catch (e: Exception) {
                    logToUI("[ERROR] Ошибка копирования сертификата: ${e.message}")
                }
            }

            val args = ArrayList<String>()
            args.add(binaryPath.absolutePath)

            if (manualCmdMode && customCmdString.isNotBlank()) {
                // Ручной режим
                val tokens = customCmdString.trim().split("\\s+".toRegex())
                args.addAll(tokens)

                if (!customCmdString.contains("-cafile")) {
                    args.add("-cafile")
                    args.add(sslFile.absolutePath)
                }

            } else {
                // Автоматический режим
                args.add("-bind-address"); args.add(bindAddress)
                args.add("-country"); args.add(currentCountry)
                args.add("-verbosity")
                if (verbosity == 5) {
                    args.add("50") 
                } else {
                    args.add(verbosity.toString())
                }
                args.add("-cafile"); args.add(sslFile.absolutePath)

                if (bootstrapDns.isNotEmpty()) {
                    args.add("-bootstrap-dns"); args.add(bootstrapDns)
                }
                if (fakeSni.isNotEmpty()) {
                    args.add("-fake-SNI"); args.add(fakeSni)
                }
                if (upstreamProxy.isNotEmpty()) {
                    args.add("-proxy"); args.add(upstreamProxy)
                }
                if (socksMode) {
                    args.add("-socks-mode")
                }

                args.add("-server-selection-test-url")
                if (testUrl.isNotEmpty()) {
                    args.add(testUrl)
                } else {
                    args.add("https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js")
                }
            }
            
            logToUI("[CMD] Запуск бинарного файла...", fromBinary = false)
            if (verbosity <= 10) {
                logToUI("CMD: ${args.joinToString(" ")}", fromBinary = false)
            }

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)

            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeLibDir

            process = pb.start()

            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                logToUI(line, fromBinary = true)
            }

            process!!.waitFor()
            logToUI("[PROCESS] Exit code: ${process?.exitValue()}")

        } catch (e: Exception) {
            val msg = e.message ?: ""

            if (stopRequested && (msg.contains("read interrupted") || msg.contains("Socket is closed"))) {
                // Это нормальное поведение при остановке
                logToUI("[INFO] Бинарник остановлен.", fromBinary = false)
            } else {
                logToUI("[CRITICAL BIN ERROR] $msg", fromBinary = false)
                e.printStackTrace()
            }
        } finally {
            if (!stopRequested) {
                // Если процесс упал сам по себе, останавливаем сервис
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun logToUI(message: String?, fromBinary: Boolean = false) {
        if (message == null) return
		
		if (verbosity == 5) {
			if (fromBinary) return
            val intent = Intent("UPDATE_LOG")
            intent.putExtra("log", message)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            return
        }

        val showSystemLog = !fromBinary && verbosity <= 20

        if (fromBinary || showSystemLog) {
            val intent = Intent("UPDATE_LOG")
            intent.putExtra("log", message)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    // Лог только для Debug (verbosity <= 10)
    private fun logDebug(message: String) {
        if (verbosity <= 10) {
            logToUI(message, fromBinary = false)
        }
    }

    // Отправка статуса в Activity и Плитке
    private fun notifyStatusChange() {
		// Уведомляем Activity
        val intent = Intent("STATUS_UPDATE")
        intent.setPackage(packageName)
        sendBroadcast(intent)
		
        // Уведомляем Плитку (Quick Settings)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             ProxyTileService.requestUpdate(this)
        }
    }

    private fun stopVpn() {
        synchronized(this) {
            if (cleanedUp) return
            cleanedUp = true
        }

        try {
            // 1. Сначала просим Rust остановиться корректно
            try {
                logToUI("[STOP] Отправка сигнала tun2proxy_stop...", fromBinary = false)
                stopTun2proxy()
            } catch (e: Exception) {
                logToUI("[STOP] Ошибка вызова stopTun2proxy: ${e.message}")
            }           
            
            // 2. Ждем поток tun2proxy
            try {
                tun2proxyThread?.join(1500) // Ждем 1.5 сек
            } catch (e: Exception) {
                logToUI("[STOP] Поток tun2proxy не завершился вовремя")
            } finally {
                if (tun2proxyThread?.isAlive == true) {
                    tun2proxyThread?.interrupt() // Крайняя мера
                }
                tun2proxyThread = null
            }

            // 3. Закрываем интерфейс
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = null
            tunFd = -1
            
            // 4. Убиваем процесс прокси
            process?.destroy()
            process = null
            
            // 5. Небольшая пауза
            try { Thread.sleep(200) } catch (_: Exception){}

            isRunning = false
            notifyStatusChange()
            logToUI("[STOP] Служба остановлена.")
        } catch (e: Exception) {
            logToUI("[STOP ERROR] ${e.message}")
        }
    }

    override fun onDestroy() {
		stopVpn()
        super.onDestroy()
    }
}