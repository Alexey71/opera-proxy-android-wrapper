package com.example.operaproxy

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnSelectApps: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var rgCountry: RadioGroup
    private lateinit var rgAppMode: RadioGroup
    private lateinit var etDns: TextInputEditText
    private lateinit var prefs: SharedPreferences

    companion object { var selectedApps: ArrayList<String> = ArrayList() }

    private fun isServiceRunning(): Boolean {
        return ServiceState.isRunning(this)
    }

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startProxyService()
        } else {
            Toast.makeText(this, "Без разрешения VPN туннель не создастся", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) startVpnPreparation()
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("log")?.let { rawMessage ->
                val timestamp = timeFormatter.format(Date())
                val formattedLog = "[$timestamp] $rawMessage\n"
                val view = svLog.getChildAt(0)
                val diff = if (view != null) (view.bottom - (svLog.height + svLog.scrollY)) else 0
                val wasAtBottom = diff <= 100

                tvLog.append(formattedLog)

                if (wasAtBottom) {
                    svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isServiceRunning()) updateUiStarted() else updateUiStopped()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

        initViews()

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val appName = getString(R.string.app_name)
        val version = BuildConfig.VERSION_NAME
        supportActionBar?.title = "$appName v$version"

        setupListeners()

        val logFilter = IntentFilter("UPDATE_LOG")
        val statusFilter = IntentFilter("STATUS_UPDATE")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, logFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, logFilter)
            registerReceiver(statusReceiver, statusFilter)
        }

        loadSettings()
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun initViews() {
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        rgCountry = findViewById(R.id.rgCountry)
        rgAppMode = findViewById(R.id.rgAppMode)
        etDns = findViewById(R.id.etDns)
        tvLog.setTextIsSelectable(true)
    }

    private fun setupListeners() {
        btnAdvanced.setOnClickListener {
            saveSettings()
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        btnSelectApps.setOnClickListener {
            saveSettings()
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        btnToggle.setOnClickListener {
            if (!isServiceRunning()) {
                saveSettings()
                checkPermissionsAndStart()
            } else {
                stopProxyService()
            }
        }

        btnCopyLog.setOnClickListener {
            val textToCopy = tvLog.text.toString()
            if (textToCopy.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OperaProxy Log", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLog.setOnClickListener { tvLog.text = "" }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        if (isServiceRunning()) updateUiStarted() else updateUiStopped()
    }

    private fun loadSettings() {
        val dns = prefs.getString("DNS", "8.8.8.8")
        etDns.setText(dns)
        val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
        rgCountry.check(countryId)
        
        // Загрузка режима проксирования
        // 0 = Whitelist, 1 = Whitelist (Inverted), 2 = Blacklist
        val appMode = prefs.getInt("PROXY_APP_MODE", 0)
        when (appMode) {
            1 -> rgAppMode.check(R.id.rbModeWhitelistDisallowed)
            2 -> rgAppMode.check(R.id.rbModeBlacklist)
            else -> rgAppMode.check(R.id.rbModeWhitelist)
        }
        
        val savedApps = prefs.getStringSet("APPS", emptySet())
        if (savedApps != null) selectedApps = ArrayList(savedApps)
    }

    private fun saveSettings() {
        // Определяем режим
        val appMode = when (rgAppMode.checkedRadioButtonId) {
            R.id.rbModeWhitelistDisallowed -> 1
            R.id.rbModeBlacklist -> 2
            else -> 0
        }
        
        prefs.edit()
            .putString("DNS", etDns.text.toString())
            .putInt("COUNTRY_ID", rgCountry.checkedRadioButtonId)
            .putInt("PROXY_APP_MODE", appMode)
            .putStringSet("APPS", selectedApps.toSet())
            .apply()
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startVpnPreparation()
            }
        } else {
            startVpnPreparation()
        }
    }

    private fun startVpnPreparation() {
        val isProxyOnly = prefs.getBoolean("PROXY_ONLY", false)
        if (isProxyOnly) {
            startProxyService()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                startProxyService()
            }
        }
    }

    private fun startProxyService() {
        tvLog.text = ""
        val verbosity = prefs.getInt("VERBOSITY", 20)

        if (verbosity == 10) {
            tvLog.append("[INFO] MainActivity Initiating Service start...")
        }

        val intent = Intent(this, ProxyVpnService::class.java)

        val country = when (rgCountry.checkedRadioButtonId) { R.id.rbAS -> "AS"; R.id.rbAM -> "AM"; else -> "EU" }
        intent.putExtra("COUNTRY", country)
        var dns = etDns.text.toString(); if (dns.isEmpty()) dns = "8.8.8.8"
        intent.putExtra("DNS", dns)
        if (selectedApps.isNotEmpty()) intent.putStringArrayListExtra("ALLOWED_APPS", selectedApps)

        intent.putExtra("BIND_ADDRESS", prefs.getString("BIND_ADDRESS", "127.0.0.1:1085"))
        intent.putExtra("FAKE_SNI", prefs.getString("FAKE_SNI", ""))
        intent.putExtra("UPSTREAM_PROXY", prefs.getString("UPSTREAM_PROXY", ""))
        intent.putExtra("BOOTSTRAP_DNS", prefs.getString("BOOTSTRAP_DNS", ""))
        intent.putExtra("SOCKS_MODE", prefs.getBoolean("SOCKS_MODE", false))
        intent.putExtra("PROXY_ONLY", prefs.getBoolean("PROXY_ONLY", false))
        intent.putExtra("VERBOSITY", verbosity)
        intent.putExtra("TUN2PROXY_DNS_STRATEGY", prefs.getInt("TUN2PROXY_DNS_STRATEGY", 1))
        intent.putExtra("TEST_URL", prefs.getString("TEST_URL", ""))
        intent.putExtra("API_ADDRESS", prefs.getString("API_ADDRESS", ""))
        intent.putExtra("MANUAL_CMD_MODE", prefs.getBoolean("MANUAL_CMD_MODE", false))
        intent.putExtra("CUSTOM_CMD_STRING", prefs.getString("CUSTOM_CMD_STRING", ""))
        // Передаем режим проксирования приложений
        // 0 = Whitelist, 1 = Whitelist (Inverted), 2 = Blacklist
        intent.putExtra("PROXY_APP_MODE", prefs.getInt("PROXY_APP_MODE", 0))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyVpnService::class.java)
        intent.action = "STOP_VPN"
        startService(intent)

        val verbosity = prefs.getInt("VERBOSITY", 20)
        if (verbosity == 10) {
            tvLog.append("[INFO] MainActivity Service stop requested.\n")
        }
    }

    private fun updateUiStarted() {
        btnToggle.text = getString(R.string.btn_stop)
        btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
        rgCountry.isEnabled = false
        etDns.isEnabled = false
        btnSelectApps.isEnabled = false
        btnAdvanced.isEnabled = false
        
        // Блокируем выбор режима
        if (::rgAppMode.isInitialized) {
            rgAppMode.isEnabled = false
            for (i in 0 until rgAppMode.childCount) { rgAppMode.getChildAt(i).isEnabled = false }
        }
    }


    private fun updateUiStopped() {
        btnToggle.text = getString(R.string.btn_start)
        btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.opera_red))
        rgCountry.isEnabled = true
        etDns.isEnabled = true
        btnSelectApps.isEnabled = true
        btnAdvanced.isEnabled = true
        
        // Разблокируем выбор режима
        if (::rgAppMode.isInitialized) {
            rgAppMode.isEnabled = true
            for (i in 0 until rgAppMode.childCount) { rgAppMode.getChildAt(i).isEnabled = true }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }
}
