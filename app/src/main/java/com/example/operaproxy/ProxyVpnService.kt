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
import java.util.Arrays

class ProxyVpnService : VpnService() {
    private var process: Process? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    
    // Параметры
    private var currentCountry = "EU"
    private var tunDnsServer = "8.8.8.8"
    private var allowedApps: ArrayList<String>? = null
    
    // Расширенные параметры
    private var bindAddress = "127.0.0.1:1080"
    private var bootstrapDns = ""
    private var fakeSni = ""
    private var upstreamProxy = ""
	private var testUrl = ""
    
    // По умолчанию 20, но будет перезаписано из Intent
    private var verbosity = 20
    
    private var socksMode = false
    private var proxyOnlyMode = false
	
    // Ручной режим
    private var manualCmdMode = false
    private var customCmdString = ""

    companion object { var isRunning = false }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // Считываем verbosity сразу, чтобы корректно фильтровать логи
        if (intent != null) {
             verbosity = intent.getIntExtra("VERBOSITY", 20)
        }

        if (action == "STOP_VPN") {
            logToUI("!!! ЗАПРОС НА ОСТАНОВКУ СЛУЖБЫ !!!")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        
        logToUI("=== ИНИЦИАЛИЗАЦИЯ СЕРВИСА ===")
        
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
			
			if (manualCmdMode) {
                logToUI("[CONFIG] ! ВКЛЮЧЕН РУЧНОЙ РЕЖИМ КОМАНДЫ !")
				logToUI("[CONFIG] CUSTOM CMD: $customCmdString")
            }

            logToUI("[CONFIG] Страна: $currentCountry")
            logToUI("[CONFIG] VPN DNS: $tunDnsServer")
            logToUI("[CONFIG] Apps count: ${allowedApps?.size ?: 0}")
			
			if (verbosity <= 10) {
				logToUI("=== РАСШИРЕННЫЕ НАСТРОЙКИ ===")
				logToUI("[ADV] BIND: $bindAddress")
				logToUI("[ADV] BOOTSTRAP DNS: $bootstrapDns")
				logToUI("[ADV] FAKE SNI: $fakeSni")
				logToUI("[ADV] MODE: ${if(socksMode) "SOCKS" else "HTTP"}")
				logToUI("[ADV] TEST URL: $testUrl")
				logToUI("[ADV] Verbosity: $verbosity")
			}
        }

        startForegroundNotification()

        if (!isRunning) {
            isRunning = true
			notifyStatusChange()
            
            Thread { 
                runBinary() 
            }.start()
            
            if (!proxyOnlyMode) {
                logToUI("[VPN] Создание туннеля...")
                establishVpn()
            } else {
                logToUI("[VPN] Режим Proxy Only (без TUN).")
            }
        } else {
            logToUI("[WARNING] Сервис уже запущен.")
			notifyStatusChange()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "opera_vpn_channel"
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            mgr?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ProxyVpnService::class.java).apply { action = "STOP_VPN" }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mainIntent = Intent(this, MainActivity::class.java)
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingMain = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val modeText = when {
            proxyOnlyMode -> "Proxy Only"
            socksMode -> "SOCKS VPN"
            else -> "HTTP VPN"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title) + " ($currentCountry)")
            .setContentText("$modeText | $bindAddress | $tunDnsServer")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingMain)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_stop), pendingStop)
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
        if (proxyOnlyMode) return 

        try {
            logToUI("[BUILDER] Настройка Builder...")
            val builder = Builder()
            builder.setSession("OperaTurbo")
            builder.setMtu(1500)
            builder.addAddress("10.1.10.1", 24)

            try { 
                builder.addDnsServer(tunDnsServer) 
            } catch(e:Exception) { 
                builder.addDnsServer("8.8.8.8") 
            }

            if (!allowedApps.isNullOrEmpty()) {
                 logToUI("[ROUTING] Белый список приложений включен.")
                 for (pkg in allowedApps!!) { 
                     try { builder.addAllowedApplication(pkg) } catch (_: Exception) {} 
                 }
            } else { 
                builder.addDisallowedApplication(packageName) 
            }

            builder.addRoute("0.0.0.0", 0)
            
            val parts = bindAddress.split(":")
            val host = if (parts.isNotEmpty()) parts[0] else "127.0.0.1"
            val port = if (parts.size > 1) parts[1].toInt() else 1080
            
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port))
            
            vpnInterface = builder.establish()
            logToUI("[SUCCESS] VPN интерфейс создан.")

        } catch (e: Exception) {
            logToUI("[CRITICAL] Ошибка VPN: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun runBinary() {
        try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val binaryName = "liboperaproxy.so"
            val binaryPath = File(nativeLibDir, binaryName)
			
			if (!binaryPath.exists()) {
                logToUI("[ERROR] Бинарный файл не найден: ${binaryPath.absolutePath}")
            }
            
            logToUI("[BIN] Поиск бинарника: ${binaryPath.absolutePath}")
			
            
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
                // Парсим строку пользователя по пробелам
                val tokens = customCmdString.trim().split("\\s+".toRegex())
                args.addAll(tokens)
                
                // Добавляем cafile, так как путь к нему динамический
                if (!customCmdString.contains("-cafile")) {
                    args.add("-cafile")
                    args.add(sslFile.absolutePath)
                }
                
            } else {
                // АВТОМАТИЧЕСКИЙ РЕЖИМ (Стандартный)
                args.add("-bind-address"); args.add(bindAddress)
                args.add("-country"); args.add(currentCountry)
                args.add("-verbosity"); args.add(verbosity.toString())
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
			
            logToUI("[CMD] Запуск бинарного файла...")
            // Если включен DEBUG, покажем полную команду
            logToUI("CMD: ${args.joinToString(" ")}", fromBinary = false)

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeLibDir
            
            process = pb.start()
            logToUI("[PROCESS] Started.")
            
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            
            // Читаем вывод бинарника
            while (reader.readLine().also { line = it } != null) {
                logToUI(line, fromBinary = true)
            }
            
            process!!.waitFor()
            logToUI("[PROCESS] Exit code: ${process?.exitValue()}")
            
        } catch (e: Exception) { 
            logToUI("[CRITICAL BIN ERROR] ${e.message}")
            e.printStackTrace()
        } finally { 
            stopVpn()
            stopSelf() 
        }
    }
    

    private fun logToUI(message: String?, fromBinary: Boolean = false) { 
        if (message == null) return
		
		val showSystemLog = !fromBinary && verbosity <= 20
        
        if (fromBinary || showSystemLog) {
			val intent = Intent("UPDATE_LOG")
			intent.putExtra("log", message)
			// FIX ANDROID 14: Явно указываем пакет назначения.
			intent.setPackage(packageName)
			sendBroadcast(intent)
		}
    }
	
	// Метод для отправки статуса в Activity
    private fun notifyStatusChange() {
        val intent = Intent("STATUS_UPDATE")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
    
    private fun stopVpn() { 
        try { 
            if (vpnInterface != null) {
                vpnInterface?.close()
                vpnInterface = null
            }
            if (process != null) {
                process?.destroy()
                process = null
            }
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