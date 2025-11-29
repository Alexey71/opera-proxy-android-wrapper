package com.example.operaproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    private var verbosity = 20
    private var socksMode = false

    companion object { var isRunning = false }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_VPN") {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null) {
            // Basic
            intent.getStringExtra("COUNTRY")?.let { currentCountry = it }
            intent.getStringExtra("DNS")?.let { tunDnsServer = it }
            intent.getStringArrayListExtra("ALLOWED_APPS")?.let { allowedApps = it }
            
            // Advanced
            intent.getStringExtra("BIND_ADDRESS")?.let { bindAddress = it }
            intent.getStringExtra("BOOTSTRAP_DNS")?.let { bootstrapDns = it }
            intent.getStringExtra("FAKE_SNI")?.let { fakeSni = it }
            intent.getStringExtra("UPSTREAM_PROXY")?.let { upstreamProxy = it }
            verbosity = intent.getIntExtra("VERBOSITY", 20)
            socksMode = intent.getBooleanExtra("SOCKS_MODE", false)
        }

        startForegroundNotification()

        if (!isRunning) {
            isRunning = true
            Thread { runBinary() }.start()
            establishVpn()
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

        // FIX: Убрали verbosity, добавили DNS в текст уведомления
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title) + " ($currentCountry)")
            .setContentText("Mode: ${if(socksMode) "SOCKS" else "HTTP"} | DNS: $tunDnsServer")
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
        try {
            val builder = Builder()
            builder.setSession("OperaTurbo")
            builder.setMtu(1500)
            builder.addAddress("10.1.10.1", 24)
            try { builder.addDnsServer(tunDnsServer) } catch(e:Exception) { builder.addDnsServer("8.8.8.8") }
            if (!allowedApps.isNullOrEmpty()) {
                 for (pkg in allowedApps!!) { try { builder.addAllowedApplication(pkg) } catch (e: Exception) {} }
            } else { builder.addDisallowedApplication(packageName) }
            builder.addRoute("0.0.0.0", 0)
            
            val parts = bindAddress.split(":")
            val host = if (parts.isNotEmpty()) parts[0] else "127.0.0.1"
            val port = if (parts.size > 1) parts[1].toInt() else 1080
            
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port))
            vpnInterface = builder.establish()
            log("VPN Tunnel Established on $host:$port")
        } catch (e: Exception) {
            log("VPN Error: ${e.message}")
            stopVpn()
        }
    }

    private fun runBinary() {
        try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val binaryPath = File(nativeLibDir, "liboperaproxy.so")
            val sslFile = File(filesDir, "cacert.pem")
            if (!sslFile.exists()) { assets.open("cacert.pem").use { input -> FileOutputStream(sslFile).use { o -> input.copyTo(o) } } }
            
            val args = ArrayList<String>()
            args.add(binaryPath.absolutePath)
            
            args.add("-bind-address"); args.add(bindAddress)
            args.add("-country"); args.add(currentCountry)
            args.add("-verbosity"); args.add(verbosity.toString())
            args.add("-cafile"); args.add(sslFile.absolutePath)
            
            if (bootstrapDns.isNotEmpty()) {
                args.add("-bootstrap-dns")
                args.add(bootstrapDns)
            }
            if (fakeSni.isNotEmpty()) {
                args.add("-fake-SNI")
                args.add(fakeSni)
            }
            if (upstreamProxy.isNotEmpty()) {
                args.add("-proxy")
                args.add(upstreamProxy)
            }
            if (socksMode) {
                args.add("-socks-mode")
            }
            
            args.add("-server-selection-test-url")
            args.add("https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js")

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            
            log("Starting binary: ${args.joinToString(" ")}")
            
            process = pb.start()
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) log(line)
            process!!.waitFor()
        } catch (e: Exception) { log("Binary Error: ${e.message}") } finally { stopVpn(); stopSelf() }
    }
    
    private fun log(message: String?) { sendBroadcast(Intent("UPDATE_LOG").putExtra("log", message)) }
    
    private fun stopVpn() { 
        try { 
            vpnInterface?.close(); vpnInterface = null
            process?.destroy(); process = null
            isRunning = false 
        } catch (e: Exception) {} 
    }
    
    override fun onDestroy() { stopVpn(); super.onDestroy() }
}