package com.example.operaproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    // prefs-хелперы (оставляем для настроек; для RUNNING используем ServiceState)
    private fun prefs() = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

    private fun setServiceRunning(value: Boolean) {
        // Источник правды для multi-process
        ServiceState.setRunning(this, value)

        // для обратной совместимости (UI/Tile на это больше не полагаются)
        prefs().edit().putBoolean("SERVICE_RUNNING", value).apply()
    }

    private fun isServiceRunning(): Boolean {
        return ServiceState.isRunning(this)
    }

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

        @JvmStatic
        external fun stopTun2proxy(): Int

        init {
            System.loadLibrary("tun2proxy")
            System.loadLibrary("native-lib")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (intent != null) {
            verbosity = intent.getIntExtra("VERBOSITY", 20)
        }

        if (action == "STOP_VPN") {
            stopRequested = true
            logToUI("ЗАПРОС НА ОСТАНОВКУ СЛУЖБЫ", fromBinary = false)

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

        if (!isServiceRunning()) {
            setServiceRunning(true)
            stopRequested = false
            cleanedUp = false
            notifyStatusChange()

            Thread { runBinary() }.start()

            if (!proxyOnlyMode) {
                Thread { waitForProxyAndEstablishVpn() }.start()
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

    private fun waitForProxyAndEstablishVpn() {
        val hostPort = parseBindAddress(bindAddress)
        val host = hostPort.first
        val port = hostPort.second

        logToUI("[INIT] Ожидание запуска прокси на $host:$port...", fromBinary = false)

        var retries = 0
        val maxRetries = 120
        var isConnected = false

        while (retries < maxRetries && isServiceRunning() && !stopRequested) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 200)
                    isConnected = true
                }
            } catch (_: Exception) {
            }

            if (isConnected) break

            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
                break
            }
            retries++
        }

        if (isConnected && isServiceRunning() && !stopRequested) {
            logToUI("[INIT] Порт $port открыт. Поднимаем VPN интерфейс...", fromBinary = false)
            val mode = if (socksMode) "TUN → SOCKS5(tun2proxy)" else "TUN → HTTP(tun2proxy)"
            logToUI("[VPN] Режим: $mode")
            establishVpn()
        } else {
            if (isServiceRunning() && !stopRequested) {
                logToUI("[ERROR] Не удалось дождаться запуска локального прокси. VPN не создан.", fromBinary = false)
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
        } catch (_: Exception) {
            Pair("127.0.0.1", 1080)
        }
    }

    private fun stopProxyServiceSelf() {
        val intent = Intent(this, ProxyVpnService::class.java)
        intent.action = "STOP_VPN"
        startService(intent)
    }

    private fun piFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun startForegroundNotification() {
        val channelId = "opera_vpn_channel"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ProxyVpnService::class.java).apply { action = "STOP_VPN" }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, piFlags())

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingMain = PendingIntent.getActivity(this, 0, mainIntent, piFlags())

        val modeTextRes = when {
            proxyOnlyMode && socksMode -> R.string.notification_mode_proxy_socks_no_vpn
            proxyOnlyMode && !socksMode -> R.string.notification_mode_proxy_http_no_vpn
            !proxyOnlyMode && socksMode -> R.string.notification_mode_vpn_socks
            else -> R.string.notification_mode_vpn_http
        }
        val modeText = getString(modeTextRes)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title) + " ($currentCountry)")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

	private fun establishVpn() {
		if (proxyOnlyMode || stopRequested) return

		try {
			logToUI("[BUILDER] Настройка Builder...", fromBinary = false)

			// пробуем allowed-list (белый список)
			var builder = buildBaseVpnBuilder()
			builder = applyAppRoutingWithFallback(builder)

			builder.addRoute("0.0.0.0", 0)

			vpnInterface = builder.establish()
			if (vpnInterface == null) {
				logToUI("[CRITICAL] Не удалось создать VPN-интерфейс. Нужно заново выдать разрешение.")
				stopVpn()
				return
			}

			tunFd = vpnInterface!!.fd
			if (verbosity <= 10) {
				logToUI("[VPN] TUN fd = $tunFd", fromBinary = false)
			}

			startTun2proxyWorker()
			if (verbosity <= 10) {
				logToUI("[TUN2PROXY] Запущен обработчик TUN - proxy", fromBinary = false)
			}
		} catch (e: Exception) {
			logToUI("[CRITICAL] Ошибка VPN: ${e.javaClass.simpleName}: ${e.message}")
			stopVpn()
		}
	}
	
	private fun buildBaseVpnBuilder(): Builder {
		val builder = Builder()
		builder.setSession("OperaProxy")
		builder.setMtu(1420)
		builder.addAddress("10.1.10.1", 24)

		try {
			builder.addDnsServer(tunDnsServer)
		} catch (_: Exception) {
			builder.addDnsServer("8.8.8.8")
		}

		return builder
	}

	/**
	 * Если ловим IllegalStateException("Cannot add both allowed and disallowed applications")
	 * или IllegalArgumentException - не смешиваем режимы на одном Builder.
	 * Пересоздаём Builder и строим whitelist через addDisallowedApplication (инверсия списка выбранных).
	 */
	private fun applyAppRoutingWithFallback(builderIn: Builder): Builder {
		val selected = (allowedApps ?: arrayListOf()).toSet()
		val safeSelected = selected.filter { it.isNotBlank() && it != packageName }.toSet()

		// Если ничего не выбрано - просто исключаем приложение чтобы не ловить петлю
		if (safeSelected.isEmpty()) {
			try {
				builderIn.addDisallowedApplication(packageName)
			} catch (_: Exception) {}
			return builderIn
		}

		// Пытаемся сделать белый список через addAllowedApplication
		try {
			logToUI("[ROUTING] Пробуем режим: ALLOWED (white list) (${safeSelected.size})")
			for (pkg in safeSelected) {
				try {
					builderIn.addAllowedApplication(pkg)
				} catch (iae: IllegalArgumentException) {
					// пропускаем, но не переключаем режим из-за одного пакета.
					logToUI("[ROUTING] Пропускаем приложение (IAE): $pkg", fromBinary = false)
				}
			}
			return builderIn
		} catch (ise: IllegalStateException) {
			val msg = ise.message ?: ""
			if (!msg.contains("Cannot add both allowed and disallowed applications")) {
				throw ise
			}
			logToUI("[ROUTING] VPN ALLOWED-mode сломан в прошивке: $msg")
		} catch (iae: IllegalArgumentException) {
			logToUI("[ROUTING] ALLOWED-mode IAE: ${iae.message}", fromBinary = false)
		}

		// Fallback: белый список через DISALLOWED (инверсия)
		val builder = buildBaseVpnBuilder()
		
		// сначала исключаем operaproxy
		try {
			builder.addDisallowedApplication(packageName)
		} catch (e: Exception) {
			logToUI("[ROUTING] Не получилось сиключить OperaProxy: ${e.javaClass.simpleName}: ${e.message}", fromBinary = false)
			return builder
		}

		logToUI("[ROUTING] Fallback режим: DISALLOWED whitelist (инверсия списка выбранных)")

		// Собираем пакеты, которые будем исключать (все launchable, кроме выбранных).
		val disallowPkgs = getLaunchablePackagesMinus(safeSelected)

		// Если список слишком большой - лучше уйти в системный VPN без фильтрации приложений
		val maxDisallowed = 900
		if (disallowPkgs.size > maxDisallowed) {
			logToUI("[ROUTING] Слишком много пакетов для DISALLOWED (${disallowPkgs.size}). Отключаем фильтрацию приложений.")
			try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
			return builder
		}

		for (pkg in disallowPkgs) {
			try {
				builder.addDisallowedApplication(pkg)
			} catch (e: Exception) {
				// IAE/NameNotFound/ISE на отдельных приложениях - пропускаем.
				if (verbosity <= 10) {
					logToUI("[ROUTING] Пропускаем приложение: $pkg (${e.javaClass.simpleName})", fromBinary = false)
				}
			}
		}
		return builder
	}

	private fun getLaunchablePackagesMinus(keep: Set<String>): Set<String> {
		return try {
			val pm = packageManager
			val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
				addCategory(android.content.Intent.CATEGORY_LAUNCHER)
			}
			val resolved = pm.queryIntentActivities(intent, 0)
			val pkgs = HashSet<String>(resolved.size)
			for (ri in resolved) {
				val pkg = ri.activityInfo?.packageName ?: continue
				if (pkg.isBlank()) continue
				if (keep.contains(pkg)) continue
				pkgs.add(pkg)
			}
			pkgs
		} catch (_: Exception) {
			emptySet()
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

        if (tun2proxyThread != null && tun2proxyThread?.isAlive == true) {
            if (verbosity <= 10) {
                logToUI("[TUN2PROXY] Повторный запуск заблокирован: поток уже работает", fromBinary = false)
            }
            return
        }

        val targetHost = if (bindAddress.startsWith("0.0.0.0") || bindAddress.startsWith(":")) {
            "127.0.0.1"
        } else {
            parseBindAddress(bindAddress).first
        }

        val targetPort = parseBindAddress(bindAddress).second
        val scheme = if (socksMode) "socks5" else "http"
        val proxyUrl = "$scheme://$targetHost:$targetPort"

        val mtuChar: Char = 1420.toChar()
        val dnsStrategy = tunDnsStrategy

        val t2pVerbosity = when {
            verbosity == 5 -> 3
            verbosity <= 10 -> 4
            verbosity <= 20 -> 3
            verbosity <= 30 -> 2
            verbosity <= 40 -> 1
            else -> 0
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
                    false,
                    mtuChar,
                    dnsStrategy,
                    t2pVerbosity
                )

                if (verbosity <= 10) {
                    logToUI("[TUN2PROXY] Завершён с кодом $code", fromBinary = false)
                }
            } catch (e: Throwable) {
                logToUI("[TUN2PROXY ERROR] ${e.message}")
            } finally {
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
                val tokens = customCmdString.trim().split("\\s+".toRegex())
                args.addAll(tokens)

                if (!customCmdString.contains("-cafile")) {
                    args.add("-cafile")
                    args.add(sslFile.absolutePath)
                }
            } else {
                args.add("-bind-address"); args.add(bindAddress)
                args.add("-country"); args.add(currentCountry)
                args.add("-verbosity")
                args.add(if (verbosity == 5) "50" else verbosity.toString())
                args.add("-cafile"); args.add(sslFile.absolutePath)

                if (bootstrapDns.isNotEmpty()) { args.add("-bootstrap-dns"); args.add(bootstrapDns) }
                if (fakeSni.isNotEmpty()) { args.add("-fake-SNI"); args.add(fakeSni) }
                if (upstreamProxy.isNotEmpty()) { args.add("-proxy"); args.add(upstreamProxy) }
                if (socksMode) { args.add("-socks-mode") }

                args.add("-server-selection-test-url")
                args.add(if (testUrl.isNotEmpty()) testUrl else "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js")
            }

            logToUI("[CMD] Запуск бинарного файла...", fromBinary = false)
            if (verbosity <= 10) {
                logToUI("CMD: ${args.joinToString(" ")}", fromBinary = false)
            }

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir

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
                logToUI("[INFO] Бинарник остановлен.", fromBinary = false)
            } else {
                logToUI("[CRITICAL BIN ERROR] $msg", fromBinary = false)
            }
        } finally {
            if (!stopRequested) {
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

    private fun logDebug(message: String) {
        if (verbosity <= 10) {
            logToUI(message, fromBinary = false)
        }
    }

    private fun notifyStatusChange() {
        val intent = Intent("STATUS_UPDATE")
        intent.setPackage(packageName)
        sendBroadcast(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ProxyTileService.requestUpdate(this)
        }
    }

    private fun stopVpn() {
        synchronized(this) {
            if (cleanedUp) return
            cleanedUp = true
        }

        stopRequested = true

        try {
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            tunFd = -1

            try {
                logToUI("[STOP] Отправка сигнала tun2proxy_stop...", fromBinary = false)
                stopTun2proxy()
            } catch (e: Exception) {
                logToUI("[STOP] Ошибка вызова stopTun2proxy: ${e.message}")
            }

            try {
                tun2proxyThread?.join(2200)
            } catch (_: Exception) {
                logToUI("[STOP] Поток tun2proxy не завершился вовремя")
            }
            if (tun2proxyThread?.isAlive == true) tun2proxyThread?.interrupt()
            tun2proxyThread = null

            process?.destroy()
            process = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}

            setServiceRunning(false)
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
