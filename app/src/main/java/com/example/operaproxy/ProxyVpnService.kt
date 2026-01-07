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
import androidx.annotation.Keep
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

    // Режим проксирования приложений: 
    // 0 = Whitelist (Standard), 1 = Whitelist (Inverted/Disallowed), 2 = Blacklist
    private var proxyAppMode = 0

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cleanedUp = false

    // Расширенные параметры
    private var bindAddress = "127.0.0.1:1085"
    private var bootstrapDns = ""
    private var fakeSni = ""
    private var upstreamProxy = ""
    private var testUrl = ""
    private var apiAddress = ""
    private var verbosity = 20
    private var tunDnsStrategy = 1
    private var socksMode = false
    private var proxyOnlyMode = false

    // Ручной режим
    private var manualCmdMode = false
    private var customCmdString = ""

    private fun prefs() = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

    private fun setServiceRunning(value: Boolean) {
        ServiceState.setRunning(this, value)
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

    private data class HostPort(val host: String, val port: Int)

    private fun parseHostPortLoose(s: String): HostPort? {
        val t = s.trim()
        if (t.isEmpty()) return null

        runCatching {
            val uri = android.net.Uri.parse(t)
            val h = uri.host
            val p = uri.port
            if (!h.isNullOrBlank() && p > 0) return HostPort(h.lowercase(), p)
        }
    
        val m = Regex("""^([^:]+):(\d{1,5})$""").matchEntire(t) ?: return null
        val host = m.groupValues[1].lowercase()
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return HostPort(host, port)
    }

    private fun isSelfUpstreamLoop(bind: String, upstream: String): Boolean {
        val b = parseHostPortLoose(bind) ?: return false
        val u = parseHostPortLoose(upstream) ?: return false

        val uHost = when (u.host) {
            "localhost" -> "127.0.0.1"
            else -> u.host
        }
        val bHost = when (b.host) {
            "localhost" -> "127.0.0.1"
            else -> b.host
        }

        return (u.port == b.port) && (uHost == bHost)
    }

    private fun failFastAndStop(msg: String): Int {
        logToUI("[CONFIG ERROR] $msg", fromBinary = false)
        setServiceRunning(false)
        notifyStatusChange()
        stopSelf()
        return START_NOT_STICKY
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

            bindAddress = intent.getStringExtra("BIND_ADDRESS") ?: "127.0.0.1:1085"
            bootstrapDns = intent.getStringExtra("BOOTSTRAP_DNS") ?: ""
            fakeSni = intent.getStringExtra("FAKE_SNI") ?: ""
            upstreamProxy = intent.getStringExtra("UPSTREAM_PROXY") ?: ""
            socksMode = intent.getBooleanExtra("SOCKS_MODE", false)
            proxyOnlyMode = intent.getBooleanExtra("PROXY_ONLY", false)
            apiAddress = intent.getStringExtra("API_ADDRESS") ?: ""
            testUrl = intent.getStringExtra("TEST_URL") ?: ""
            manualCmdMode = intent.getBooleanExtra("MANUAL_CMD_MODE", false)
            customCmdString = intent.getStringExtra("CUSTOM_CMD_STRING") ?: ""
            tunDnsStrategy = intent.getIntExtra("TUN2PROXY_DNS_STRATEGY", 1)
            proxyAppMode = intent.getIntExtra("PROXY_APP_MODE", 0)

            if (manualCmdMode) {
                logDebug("[CONFIG] ВКЛЮЧЕН РУЧНОЙ РЕЖИМ КОМАНДЫ")
                logDebug("[CONFIG] CUSTOM CMD: $customCmdString\n")
            }

            logToUI("[CONFIG] Страна: $currentCountry")
            logToUI("[CONFIG] VPN DNS: $tunDnsServer")
            logToUI("[CONFIG] Apps count: ${allowedApps?.size ?: 0}")
            
            val modeStr = when(proxyAppMode) {
                1 -> "Whitelist via Disallowed (Inverted)"
                2 -> "Blacklist (Block Selected)"
                else -> "Whitelist (Standard)"
            }
            logToUI("[CONFIG] APPS list Mode: $modeStr\n")

            if (verbosity <= 10) {
                logToUI("=== РАСШИРЕННЫЕ НАСТРОЙКИ ===")
                logToUI("[ADV] BIND: $bindAddress")
                logToUI("[ADV] BOOTSTRAP DNS: $bootstrapDns")
                logToUI("[ADV] FAKE SNI: $fakeSni")
                logToUI("[ADV] UPSTREAM PROXY: $upstreamProxy")
                logToUI("[ADV] MODE: ${if (socksMode) "SOCKS" else "HTTP"}")
                logToUI("[ADV] API ADDRESS: $apiAddress")
                logToUI("[ADV] TEST URL: $testUrl")
                logToUI("[ADV] TUN DNS STRATEGY: $tunDnsStrategy (0=Virt, 1=TCP)")
                logToUI("[ADV] APP MODE INT: $proxyAppMode")
                logToUI("[ADV] VERBOSITY: $verbosity")
            }
            
        }
        
        // self-loop по upstream proxy
        if (upstreamProxy.isNotBlank() && isSelfUpstreamLoop(bindAddress, upstreamProxy)) {
            return failFastAndStop("Upstream Proxy указывает на тот же адрес/порт что и Bind Address ($bindAddress). Это создаёт петлю.\n")
        }

        if (manualCmdMode && customCmdString.isNotBlank()) {
            val tokens = customCmdString.trim().split(Regex("\\s+"))
            fun argValue(key: String): String? {
                val i = tokens.indexOf(key)
                return if (i >= 0 && i + 1 < tokens.size) tokens[i + 1] else null
            }
            val bindArg = argValue("-bind-address") ?: bindAddress
            val proxyArg = argValue("-proxy")
            if (!proxyArg.isNullOrBlank() && isSelfUpstreamLoop(bindArg, proxyArg)) {
                return failFastAndStop("В ручной команде -proxy указывает на -bind-address ($bindArg). Это петля.\n")
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
                    try { protect(socket) } catch (_: Exception) {}
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
                logToUI("[ERROR] Не удалось дождаться запуска локального прокси. VPN не создан.\n", fromBinary = false)
                stopProxyServiceSelf()
                stopVpn()
            }
        }
    }

    private fun parseBindAddress(addr: String): Pair<String, Int> {
        return try {
            if (addr.contains(":")) {
                val parts = addr.split(":")
                Pair(parts[0], parts[1].toInt())
            } else {
                Pair("127.0.0.1", 1085)
            }
        } catch (_: Exception) {
            Pair("127.0.0.1", 18080)
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
        val channelId = "OperaProxy_vpn_channel"
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

            var builder = buildBaseVpnBuilder()
            builder = applyAppRoutingWithFallback(builder)

            builder.addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                logToUI("[CRITICAL] Не удалось создать VPN-интерфейс. Нужно заново выдать разрешение.\n")
                stopVpn()
                return
            }

            tunFd = vpnInterface!!.detachFd()
            if (verbosity <= 10) {
                logToUI("[VPN] TUN detachFd = $tunFd", fromBinary = false)
            }

            startTun2proxyWorker()
            if (verbosity <= 10) {
                logToUI("[tun2proxy] Запущен обработчик TUN - proxy", fromBinary = false)
            }
        } catch (e: Exception) {
            logToUI("[CRITICAL] Ошибка VPN: ${e.javaClass.simpleName}: ${e.message}\n")
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
        } catch (e: Exception) {
            logToUI("[CRITICAL] Ошибка VPN builder: ${e.javaClass.simpleName}: ${e.message}\n")
            stopVpn()
        }

        return builder
    }

    private fun applyAppRoutingWithFallback(builderIn: Builder): Builder {
        val selected = (allowedApps ?: arrayListOf()).toSet()
        val safeSelected = selected.filter { it.isNotBlank() && it != packageName }.toSet()
        val isListEmpty = safeSelected.isEmpty()

        // 0 = Whitelist (Standard), 1 = Whitelist (Inverted/Disallowed), 2 = Blacklist
        
        // --- MODE 2: BLACKLIST (BLOCK SELECTED) ---
        if (proxyAppMode == 2) {
            logToUI("[ROUTING] Mode: BLACKLIST (Проксировать все, кроме выбранных)")
            try { builderIn.addDisallowedApplication(packageName) } catch (_: Exception) {}

            if (isListEmpty) {
                logToUI("[ROUTING] Список пуст. Проксируется вся система (кроме VPN-приложения).")
                return builderIn
            }

            for (pkg in safeSelected) {
                try {
                    builderIn.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    if (verbosity <= 10) logToUI("[ROUTING] Ошибка исключения $pkg: ${e.message}", fromBinary = false)
                }
            }
            return builderIn
        }

        // --- MODE 1: WHITELIST INVERTED (VIA DISALLOWED) ---
        if (proxyAppMode == 1) {
            logToUI("[ROUTING] Mode: WHITELIST VIA DISALLOWED")
            
            // Всегда исключаем себя
            try { builderIn.addDisallowedApplication(packageName) } catch (_: Exception) {}
            
            if (isListEmpty) {
                logToUI("[ROUTING] Список пуст. Проксируется вся система (кроме VPN-приложения).")
                return builderIn
            }

            val disallowPkgs = getLaunchablePackagesMinus(safeSelected)
            val maxDisallowed = 900 // Android limitation approx
            
            if (disallowPkgs.size > maxDisallowed) {
                logToUI("[ROUTING] Слишком много приложений для исключения (${disallowPkgs.size}). Фильтрация отключена, проксируется всё.")
                return builderIn
            }

            for (pkg in disallowPkgs) {
                try {
                    builderIn.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    if (verbosity <= 10) logToUI("[ROUTING] Ошибка Disallow $pkg: ${e.message}", fromBinary = false)
                }
            }
            return builderIn
        }

        // --- MODE 0: WHITELIST STANDARD (DEFAULT) ---
        logToUI("[ROUTING] Mode: WHITELIST (Стандарт)")

        // Если список пуст -> поведение по умолчанию: Проксировать всё
        if (isListEmpty) {
            logToUI("[ROUTING] Список пуст. Проксируется вся система (кроме VPN-приложения).")
            try { builderIn.addDisallowedApplication(packageName) } catch (_: Exception) {}
            return builderIn
        }

        try {
            var added = 0
            for (pkg in safeSelected) {
                try {
                    builderIn.addAllowedApplication(pkg)
                    added++
                } catch (iae: IllegalArgumentException) {
                    logToUI("[ROUTING] Пропуск (IAE): $pkg", fromBinary = false)
                }
            }

            if (added == 0) {
                // Если ни одно не добавилось (все удалены), проксируем всё
                val b = buildBaseVpnBuilder()
                try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
                return b
            }

            return builderIn

        } catch (uoe: UnsupportedOperationException) {
            logToUI("[ROUTING] addAllowedApplication не поддерживается (UOE). Включаю Fallback на Mode 1 (WHITELIST INVERTED).", fromBinary = false)
            // Рекурсивный вызов с принудительным режимом 1
            proxyAppMode = 1
            return applyAppRoutingWithFallback(buildBaseVpnBuilder())
            
        } catch (ise: IllegalStateException) {
            // "Cannot add both allowed and disallowed applications"
            logToUI("[ROUTING] Включаю Fallback на Mode 2 (BLACKLIST)", fromBinary = false)
            proxyAppMode = 2
            return applyAppRoutingWithFallback(buildBaseVpnBuilder())
        }
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
                if (pkg == packageName) continue // Себя не добавляем в список для disallow, это делается отдельно
                pkgs.add(pkg)
            }
            pkgs
        } catch (_: Exception) {
            emptySet()
        }
    }

    @Keep
    fun onTun2ProxyLog(message: String) {
        if (verbosity == 5 || verbosity <= 10) {
            logToUI(message, fromBinary = false)
        }
    }

    private fun startTun2proxyWorker() {
        if (tunFd <= 0) {
            logToUI("[tun2proxy] Некорректный tunFd: $tunFd \n", fromBinary = false)
            return
        }

        if (tun2proxyThread != null && tun2proxyThread?.isAlive == true) {
            if (verbosity <= 10) {
                logToUI("[tun2proxy] Повторный запуск заблокирован: поток уже работает \n", fromBinary = false)
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
                    logToUI("[tun2proxy] startTun2proxy($proxyUrl, fd=$tunFd, dns=$dnsStrategy)", fromBinary = false)
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
                    logToUI("[tun2proxy] Завершён с кодом $code", fromBinary = false)
                }
            } catch (e: Throwable) {
                logToUI("[tun2proxy ERROR] ${e.message} \n")
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
                logToUI("[ERROR] Бинарный файл не найден: ${binaryPath.absolutePath} \n")
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
                    logToUI("[ERROR] Ошибка копирования сертификата: ${e.message} \n")
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
                if (apiAddress.isNotEmpty()) { args.add("-api-address"); args.add(apiAddress) }

                args.add("-server-selection-test-url")
                args.add(if (testUrl.isNotEmpty()) testUrl else "https://ajax.googleapis.com/ajax/libs/indefinite-observable/2.0.1/indefinite-observable.bundle.js")
            }

            logToUI("[CMD] Запуск бинарного файла...", fromBinary = false)
            if (verbosity <= 10) {
                logToUI("CMD: ${args.joinToString(" ")} \n", fromBinary = false)
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
                logToUI("[CRITICAL BIN ERROR] $msg \n", fromBinary = false)
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
            try {
                logToUI("[STOP] Отправка сигнала tun2proxy_stop...", fromBinary = false)
                stopTun2proxy()
            } catch (e: Exception) {
                logToUI("[STOP] Ошибка вызова stopTun2proxy: ${e.message} \n")
            }

            try {
                val t = tun2proxyThread
                if (t != null) {
                    val deadline = System.currentTimeMillis() + 3000L
                    while (t.isAlive && System.currentTimeMillis() < deadline) {
                        try { t.join(200) } catch (_: Exception) {}
                    }
                    if (t.isAlive) {
                        logToUI("[STOP] Поток tun2proxy все еще жив после таймаута", fromBinary = false)
                        t.interrupt()
                    } else {
                        tun2proxyThread = null
                    }
                }
            } catch (e: Exception) {
                logToUI("[STOP] Ошибка ожидания потока tun2proxy: ${e.message} \n", fromBinary = false)
            }

            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            tunFd = -1

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

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
