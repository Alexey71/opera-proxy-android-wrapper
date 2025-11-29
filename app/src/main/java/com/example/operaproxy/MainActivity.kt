package com.example.operaproxy

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnSelectApps: Button
    private lateinit var btnAdvanced: Button // NEW
    private lateinit var rgCountry: RadioGroup
    private lateinit var etDns: TextInputEditText
    private lateinit var prefs: SharedPreferences
    
    companion object { var selectedApps: ArrayList<String> = ArrayList() }
    
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == Activity.RESULT_OK) startProxyService() }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean -> if (isGranted) startVpnPreparation() }
    
    private val logReceiver = object : BroadcastReceiver() { 
        override fun onReceive(context: Context?, intent: Intent?) { 
            intent?.getStringExtra("log")?.let { 
                tvLog.append("$it\n")
                svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
            } 
        } 
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        rgCountry = findViewById(R.id.rgCountry)
        etDns = findViewById(R.id.etDns)
        
        // NEW: Кнопка для перехода в расширенные настройки (добавьте её в activity_main.xml или замените btnSelectApps если места мало, но лучше добавить новую)
        // Для примера я добавлю её программно или предположу наличие в layout.
        // Чтобы код компилировался, добавим кнопку в layout ниже.
        
        // Находим кнопку Settings в layout (предполагаем, что вы добавили её)
        // В данном коде я добавлю поиск по ID, который нужно добавить в XML
        // Если кнопки нет в XML, нужно добавить.
        // Я добавлю кнопку в XML код ниже.
        
        btnAdvanced = findViewById(R.id.btnAdvanced)
        btnAdvanced.setOnClickListener { startActivity(Intent(this, AdvancedSettingsActivity::class.java)) }

        tvLog.movementMethod = ScrollingMovementMethod.getInstance()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { registerReceiver(logReceiver, IntentFilter("UPDATE_LOG"), Context.RECEIVER_NOT_EXPORTED) } else { registerReceiver(logReceiver, IntentFilter("UPDATE_LOG")) }
        
        btnSelectApps.setOnClickListener { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        btnToggle.setOnClickListener { 
            if (!ProxyVpnService.isRunning) {
                saveSettings() 
                checkPermissionsAndStart() 
            } else {
                stopProxyService() 
            }
        }
        
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        if (ProxyVpnService.isRunning) updateUiStarted() else updateUiStopped()
    }

    private fun loadSettings() {
        val dns = prefs.getString("DNS", "8.8.8.8")
        etDns.setText(dns)
        val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
        rgCountry.check(countryId)
        val savedApps = prefs.getStringSet("APPS", emptySet())
        if (savedApps != null) selectedApps = ArrayList(savedApps)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("DNS", etDns.text.toString())
            .putInt("COUNTRY_ID", rgCountry.checkedRadioButtonId)
            .putStringSet("APPS", selectedApps.toSet())
            .apply()
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startVpnPreparation()
        } else startVpnPreparation()
    }
    
    private fun startVpnPreparation() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startProxyService()
    }
    
    private fun startProxyService() {
        val intent = Intent(this, ProxyVpnService::class.java)
        
        // Basic Settings
        val country = when(rgCountry.checkedRadioButtonId) { R.id.rbAS -> "AS"; R.id.rbAM -> "AM"; else -> "EU" }
        intent.putExtra("COUNTRY", country)
        var dns = etDns.text.toString(); if (dns.isEmpty()) dns = "8.8.8.8"
        intent.putExtra("DNS", dns) // Это DNS для TUN интерфейса
        if (selectedApps.isNotEmpty()) intent.putStringArrayListExtra("ALLOWED_APPS", selectedApps)

        // Advanced Settings (читаем из Prefs и кладем в Intent, чтобы сервис был независим)
        intent.putExtra("BIND_ADDRESS", prefs.getString("BIND_ADDRESS", "127.0.0.1:1080"))
        intent.putExtra("FAKE_SNI", prefs.getString("FAKE_SNI", ""))
        intent.putExtra("UPSTREAM_PROXY", prefs.getString("UPSTREAM_PROXY", ""))
        intent.putExtra("BOOTSTRAP_DNS", prefs.getString("BOOTSTRAP_DNS", ""))
        intent.putExtra("SOCKS_MODE", prefs.getBoolean("SOCKS_MODE", false))
        intent.putExtra("VERBOSITY", prefs.getInt("VERBOSITY", 20))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        updateUiStarted()
    }
    
    private fun stopProxyService() { val intent = Intent(this, ProxyVpnService::class.java); intent.action = "STOP_VPN"; startService(intent); updateUiStopped() }
    
    private fun updateUiStarted() { btnToggle.text = getString(R.string.btn_stop); btnToggle.setBackgroundColor(getColor(R.color.text_secondary)); rgCountry.isEnabled = false; etDns.isEnabled = false; btnSelectApps.isEnabled = false; btnAdvanced.isEnabled = false; }
    private fun updateUiStopped() { btnToggle.text = getString(R.string.btn_start); btnToggle.setBackgroundColor(getColor(R.color.opera_red)); rgCountry.isEnabled = true; etDns.isEnabled = true; btnSelectApps.isEnabled = true; btnAdvanced.isEnabled = true; }
    
    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(logReceiver) } catch (e:Exception){} }
}