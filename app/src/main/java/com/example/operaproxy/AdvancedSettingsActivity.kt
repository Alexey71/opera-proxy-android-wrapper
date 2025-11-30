package com.example.operaproxy

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// FIX: Имя класса исправлено на AdvancedSettingsActivity
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
	private lateinit var swProxyOnly: SwitchMaterial
    private lateinit var etBindAddress: TextInputEditText
    private lateinit var etFakeSni: TextInputEditText
    private lateinit var etUpstreamProxy: TextInputEditText
    private lateinit var etBootstrapDns: TextInputEditText
    private lateinit var swSocksMode: SwitchMaterial
    private lateinit var spVerbosity: Spinner

    // Значения по умолчанию
    private val DEFAULT_BIND = "127.0.0.1:1080"
    private val DEFAULT_BOOTSTRAP = "https://1.1.1.3/dns-query,https://8.8.8.8/dns-query,https://dns.google/dns-query,https://security.cloudflare-dns.com/dns-query"
    private val DEFAULT_VERBOSITY_INDEX = 1 // 20 Info

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        
        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupTooltips()
        loadValues()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            if (validateInputs()) {
                saveValues()
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Сброс")
                .setMessage("Вернуть все расширенные настройки к значениям по умолчанию?")
                .setPositiveButton("Да") { _, _ -> resetDefaults() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun initViews() {
		swProxyOnly = findViewById(R.id.swProxyOnly)
        etBindAddress = findViewById(R.id.etBindAddress)
        etFakeSni = findViewById(R.id.etFakeSni)
        etUpstreamProxy = findViewById(R.id.etUpstreamProxy)
        etBootstrapDns = findViewById(R.id.etBootstrapDns)
        swSocksMode = findViewById(R.id.swSocksMode)
        spVerbosity = findViewById(R.id.spVerbosity)

        val verbosityAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.verbosity_levels,
            android.R.layout.simple_spinner_item
        )
        verbosityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVerbosity.adapter = verbosityAdapter
    }

    private fun setupTooltips() {
        fun safeSetup(id: Int, titleRes: Int, msgRes: Int) {
            val layout = findViewById<TextInputLayout>(id)
            layout?.setEndIconOnClickListener {
                showInfoDialog(getString(titleRes), getString(msgRes))
            }
        }

        safeSetup(R.id.tilBindAddress, R.string.pref_bind_address, R.string.help_bind_address)
        safeSetup(R.id.tilFakeSni, R.string.pref_fake_sni, R.string.help_fake_sni)
        safeSetup(R.id.tilUpstreamProxy, R.string.pref_upstream_proxy, R.string.help_upstream_proxy)
        safeSetup(R.id.tilBootstrapDns, R.string.pref_bootstrap_dns, R.string.help_bootstrap_dns)
		
		swProxyOnly.setOnLongClickListener {
            showInfoDialog(getString(R.string.pref_proxy_only), getString(R.string.help_proxy_only))
            true
        }
        
        swSocksMode.setOnLongClickListener { 
            showInfoDialog(getString(R.string.pref_socks_mode), getString(R.string.help_socks_mode))
            true 
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loadValues() {
		swProxyOnly.isChecked = prefs.getBoolean("PROXY_ONLY", false)
        etBindAddress.setText(prefs.getString("BIND_ADDRESS", DEFAULT_BIND))
        etFakeSni.setText(prefs.getString("FAKE_SNI", ""))
        etUpstreamProxy.setText(prefs.getString("UPSTREAM_PROXY", ""))
        etBootstrapDns.setText(prefs.getString("BOOTSTRAP_DNS", DEFAULT_BOOTSTRAP))
        swSocksMode.isChecked = prefs.getBoolean("SOCKS_MODE", false)
        
        try {
            val verbosityVal = prefs.getInt("VERBOSITY", 20)
            val verbosityValues = resources.getStringArray(R.array.verbosity_values)
            var index = verbosityValues.indexOf(verbosityVal.toString())
            if (index < 0 || index >= spVerbosity.adapter.count) {
                index = DEFAULT_VERBOSITY_INDEX
            }
            spVerbosity.setSelection(index)
        } catch (e: Exception) {
            spVerbosity.setSelection(DEFAULT_VERBOSITY_INDEX)
        }
    }

    private fun validateInputs(): Boolean {
        var isValid = true
        
        val bindRegex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?):([0-9]{1,5})$")
        if (!etBindAddress.text.toString().matches(bindRegex)) {
            findViewById<TextInputLayout>(R.id.tilBindAddress).error = getString(R.string.err_invalid_format)
            isValid = false
        } else {
            findViewById<TextInputLayout>(R.id.tilBindAddress).error = null
        }

        val proxyText = etUpstreamProxy.text.toString()
        if (proxyText.isNotEmpty() && !proxyText.matches(Regex("^(http|https|socks5|socks5h)://.*"))) {
             findViewById<TextInputLayout>(R.id.tilUpstreamProxy).error = "Must start with http://, socks5:// etc."
             isValid = false
        } else {
             findViewById<TextInputLayout>(R.id.tilUpstreamProxy).error = null
        }

        return isValid
    }

    private fun saveValues() {
        val verbosityValues = resources.getStringArray(R.array.verbosity_values)
        val selectedPos = spVerbosity.selectedItemPosition
        val safePos = if (selectedPos >= 0 && selectedPos < verbosityValues.size) selectedPos else DEFAULT_VERBOSITY_INDEX
        val selectedVerbosity = verbosityValues[safePos].toInt()

        prefs.edit()
			.putBoolean("PROXY_ONLY", swProxyOnly.isChecked)
            .putString("BIND_ADDRESS", etBindAddress.text.toString())
            .putString("FAKE_SNI", etFakeSni.text.toString())
            .putString("UPSTREAM_PROXY", etUpstreamProxy.text.toString())
            .putString("BOOTSTRAP_DNS", etBootstrapDns.text.toString())
            .putBoolean("SOCKS_MODE", swSocksMode.isChecked)
            .putInt("VERBOSITY", selectedVerbosity)
            .apply()
    }

    private fun resetDefaults() {
		swProxyOnly.isChecked = false
        etBindAddress.setText(DEFAULT_BIND)
        etFakeSni.setText("")
        etUpstreamProxy.setText("")
        etBootstrapDns.setText(DEFAULT_BOOTSTRAP)
        swSocksMode.isChecked = false
        spVerbosity.setSelection(DEFAULT_VERBOSITY_INDEX)
    }
}