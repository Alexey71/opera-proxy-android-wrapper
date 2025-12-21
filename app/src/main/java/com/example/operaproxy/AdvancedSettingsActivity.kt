package com.example.operaproxy

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import android.widget.ImageView
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var swProxyOnly: SwitchMaterial
    private lateinit var etBindAddress: TextInputEditText
    private lateinit var etFakeSni: TextInputEditText
    private lateinit var etUpstreamProxy: TextInputEditText
    private lateinit var tilBootstrapDns: TextInputLayout
    private lateinit var etBootstrapDns: TextInputEditText
    private lateinit var swSocksMode: SwitchMaterial
    private lateinit var spVerbosity: Spinner
    private lateinit var spDnsStrategy: Spinner
    private lateinit var ivHelpDnsStrategy: ImageView
    private lateinit var etTestUrl: TextInputEditText
    private lateinit var swManualMode: SwitchMaterial
    private lateinit var etCmdPreview: TextInputEditText

    // Значения по умолчанию
    private val DEFAULT_BIND = "127.0.0.1:1080"
    private val DEFAULT_BOOTSTRAP =
        "https://1.1.1.3/dns-query,https://8.8.8.8/dns-query,https://dns.google/dns-query,https://security.cloudflare-dns.com/dns-query,https://fidelity.vm-0.com/q,https://wikimedia-dns.org/dns-query,https://dns.adguard-dns.com/dns-query,https://dns.quad9.net/dns-query,https://dns.comss.one/dns-query,https://router.comss.one/dns-query"
    private val DEFAULT_TEST_URL =
        "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js"
    private val DEFAULT_VERBOSITY_INDEX = 2 // 20 Info
	private val DEFAULT_DNS_STRATEGY_INDEX = 1 // 1 = OverTcp (Default)

    // Временные переменные для генерации превью
    private var mainCountry = "EU"
    private var mainDns = "8.8.8.8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)

        // Читаем настройки из MainActivity для корректного предпросмотра
        val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
        mainCountry = when (countryId) {
            R.id.rbAS -> "AS"
            R.id.rbAM -> "AM"
            else -> "EU"
        }
        mainDns = prefs.getString("DNS", "8.8.8.8") ?: "8.8.8.8"

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupTooltips()
        setupSwitchImmediatePersistence()
        setupBootstrapDnsClick()
        loadValues()
        setupLivePreview() // Слушатели изменений для обновления CMD

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
		
        findViewById<View>(R.id.btnGithubLink).setOnClickListener {
            val url = "https://github.com/SLY-F0X/opera-proxy-android-wrapper/releases"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            }
        }
    }
	
	override fun onResume() {
		super.onResume()

		val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
		mainCountry = when (countryId) {
			R.id.rbAS -> "AS"
			R.id.rbAM -> "AM"
			else -> "EU"
		}
		mainDns = prefs.getString("DNS", "8.8.8.8") ?: "8.8.8.8"

		updateCmdPreview()
	}

    private fun initViews() {
        swProxyOnly = findViewById(R.id.swProxyOnly)
        etBindAddress = findViewById(R.id.etBindAddress)
        etFakeSni = findViewById(R.id.etFakeSni)
        etUpstreamProxy = findViewById(R.id.etUpstreamProxy)
        tilBootstrapDns = findViewById(R.id.tilBootstrapDns)
        etBootstrapDns = findViewById(R.id.etBootstrapDns)
        swSocksMode = findViewById(R.id.swSocksMode)
        spDnsStrategy = findViewById(R.id.spDnsStrategy)
        ivHelpDnsStrategy = findViewById(R.id.ivHelpDnsStrategy)
        spVerbosity = findViewById(R.id.spVerbosity)
        etTestUrl = findViewById(R.id.etTestUrl)
        swManualMode = findViewById(R.id.swManualMode)
        etCmdPreview = findViewById(R.id.etCmdPreview)

        val verbosityAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.verbosity_levels,
            android.R.layout.simple_spinner_item
        )
        verbosityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVerbosity.adapter = verbosityAdapter
		
        val dnsStrategyAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.dns_strategy_labels,
            android.R.layout.simple_spinner_item
        )
		dnsStrategyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDnsStrategy.adapter = dnsStrategyAdapter
    }

    // Немедленное сохранение состояния свитчей в SharedPreferences
    private fun setupSwitchImmediatePersistence() {
        swProxyOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("PROXY_ONLY", isChecked).apply()
        }

        swSocksMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("SOCKS_MODE", isChecked).apply()
            updateCmdPreview()
        }

        swManualMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("MANUAL_CMD_MODE", isChecked).apply()
            etCmdPreview.isEnabled = isChecked
            if (!isChecked) {
                // При возврате в автоматику пересобираем команду
                updateCmdPreview()
            }
        }
    }

    // Клик по строке Bootstrap DNS -> диалог для редактирования многострочного текста
    private fun setupBootstrapDnsClick() {
        val clickListener = View.OnClickListener {
            showBootstrapDnsDialog()
        }
        tilBootstrapDns.setOnClickListener(clickListener)
        etBootstrapDns.setOnClickListener(clickListener)
    }

    private fun showBootstrapDnsDialog() {
        val currentValue = prefs.getString("BOOTSTRAP_DNS", DEFAULT_BOOTSTRAP) ?: DEFAULT_BOOTSTRAP

        val input = TextInputEditText(this).apply {
            setText(currentValue)
            minLines = 4
            maxLines = 8
            isSingleLine = false
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }

        AlertDialog.Builder(this)
            .setTitle("Bootstrap DNS")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newValue = input.text?.toString() ?: ""
                prefs.edit().putString("BOOTSTRAP_DNS", newValue).apply()
                etBootstrapDns.setText(newValue)
                updateCmdPreview()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Добавляем TextWatcher ко всем полям, влияющим на команду
    private fun setupLivePreview() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateCmdPreview()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        val fields = listOf(etBindAddress, etFakeSni, etUpstreamProxy, etBootstrapDns, etTestUrl)
        fields.forEach { it.addTextChangedListener(watcher) }

        spVerbosity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateCmdPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCmdPreview() {
        // Если включен ручной режим, не трогаем текст
        if (swManualMode.isChecked) return

        val sb = StringBuilder()

        // Обязательные (из UI)
        sb.append("-bind-address ")
            .append(etBindAddress.text.toString().ifEmpty { DEFAULT_BIND })
        sb.append(" -country ").append(mainCountry)

        // Verbosity
        val verbosityValues = resources.getStringArray(R.array.verbosity_values)
        val pos = spVerbosity.selectedItemPosition
        val safePos = if (pos in verbosityValues.indices) pos else DEFAULT_VERBOSITY_INDEX
        sb.append(" -verbosity ").append(verbosityValues[safePos])

        // Опциональные
        val bootstrap = etBootstrapDns.text.toString()
        if (bootstrap.isNotEmpty()) sb.append(" -bootstrap-dns ").append(bootstrap)

        val sni = etFakeSni.text.toString()
        if (sni.isNotEmpty()) sb.append(" -fake-SNI ").append(sni)

        val proxy = etUpstreamProxy.text.toString()
        if (proxy.isNotEmpty()) sb.append(" -proxy ").append(proxy)

        if (swSocksMode.isChecked) sb.append(" -socks-mode")

        val testUrl = etTestUrl.text.toString()
        if (testUrl.isNotEmpty()) sb.append(" -server-selection-test-url ").append(testUrl)

        etCmdPreview.setText(sb.toString())
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
        safeSetup(R.id.tilTestUrl, R.string.pref_test_url, R.string.help_test_url)

        swProxyOnly.setOnLongClickListener {
            showInfoDialog(getString(R.string.pref_proxy_only), getString(R.string.help_proxy_only))
            true
        }
        swSocksMode.setOnLongClickListener {
            showInfoDialog(getString(R.string.pref_socks_mode), getString(R.string.help_socks_mode))
            true
        }
        swManualMode.setOnLongClickListener {
            showInfoDialog(getString(R.string.pref_manual_mode), getString(R.string.help_manual_mode))
            true
        }
		
        ivHelpDnsStrategy.setOnClickListener {
            showInfoDialog(getString(R.string.pref_tun_dns_strategy), getString(R.string.help_tun_dns_strategy))
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
        etTestUrl.setText(prefs.getString("TEST_URL", DEFAULT_TEST_URL))
		
        try {
            val strategyVal = prefs.getInt("TUN2PROXY_DNS_STRATEGY", 1) // Default OverTcp (1)
            val strategyValues = resources.getStringArray(R.array.dns_strategy_values)
            var index = strategyValues.indexOf(strategyVal.toString())
            if (index < 0 || index >= spDnsStrategy.adapter.count) {
                index = DEFAULT_DNS_STRATEGY_INDEX
            }
            spDnsStrategy.setSelection(index)
        } catch (e: Exception) {
            spDnsStrategy.setSelection(DEFAULT_DNS_STRATEGY_INDEX)
        }

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

        // Загрузка состояния командной строки
        val isManual = prefs.getBoolean("MANUAL_CMD_MODE", false)
        swManualMode.isChecked = isManual
        if (isManual) {
            etCmdPreview.setText(prefs.getString("CUSTOM_CMD_STRING", ""))
            etCmdPreview.isEnabled = true
        } else {
            etCmdPreview.isEnabled = false
            etCmdPreview.post { updateCmdPreview() }
        }
    }

    private fun validateInputs(): Boolean {
        var isValid = true

        val bindRegex =
            Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?):([0-9]{1,5})$")
        if (!etBindAddress.text.toString().matches(bindRegex)) {
            findViewById<TextInputLayout>(R.id.tilBindAddress).error =
                getString(R.string.err_invalid_format)
            isValid = false
        } else {
            findViewById<TextInputLayout>(R.id.tilBindAddress).error = null
        }

        val testUrl = etTestUrl.text.toString()
        if (testUrl.isNotEmpty() && !testUrl.startsWith("http")) {
            findViewById<TextInputLayout>(R.id.tilTestUrl).error =
                "URL должен начинаться с http/https"
            isValid = false
        } else {
            findViewById<TextInputLayout>(R.id.tilTestUrl).error = null
        }

        val proxyText = etUpstreamProxy.text.toString()
        if (proxyText.isNotEmpty() && !proxyText.matches(Regex("^(http|https|socks5|socks5h)://.*"))) {
            findViewById<TextInputLayout>(R.id.tilUpstreamProxy).error =
                "Должен начинаться с http://, socks5:// etc."
            isValid = false
        } else {
            findViewById<TextInputLayout>(R.id.tilUpstreamProxy).error = null
        }

        return isValid
    }

    private fun saveValues() {
        val verbosityValues = resources.getStringArray(R.array.verbosity_values)
        val selectedPos = spVerbosity.selectedItemPosition
        val safePos =
            if (selectedPos >= 0 && selectedPos < verbosityValues.size) selectedPos else DEFAULT_VERBOSITY_INDEX
        val selectedVerbosity = verbosityValues[safePos].toInt()
		
        val strategyValues = resources.getStringArray(R.array.dns_strategy_values)
        val stratPos = spDnsStrategy.selectedItemPosition
        val safeStratPos = if (stratPos >= 0 && stratPos < strategyValues.size) stratPos else DEFAULT_DNS_STRATEGY_INDEX
        val selectedStrategy = strategyValues[safeStratPos].toInt()

        prefs.edit()
            // свитчеры уже сохраняются немедленно, но дублирующая запись не мешает
            .putBoolean("PROXY_ONLY", swProxyOnly.isChecked)
            .putString("BIND_ADDRESS", etBindAddress.text.toString())
            .putString("FAKE_SNI", etFakeSni.text.toString())
            .putString("UPSTREAM_PROXY", etUpstreamProxy.text.toString())
            .putString("BOOTSTRAP_DNS", etBootstrapDns.text.toString())
            .putBoolean("SOCKS_MODE", swSocksMode.isChecked)
            .putInt("VERBOSITY", selectedVerbosity)
			.putInt("TUN2PROXY_DNS_STRATEGY", selectedStrategy)
            .putString("TEST_URL", etTestUrl.text.toString())
            .putBoolean("MANUAL_CMD_MODE", swManualMode.isChecked)
            .putString("CUSTOM_CMD_STRING", etCmdPreview.text.toString())
            .apply()
    }

    private fun resetDefaults() {
        swProxyOnly.isChecked = false
        etBindAddress.setText(DEFAULT_BIND)
        etFakeSni.setText("")
        etUpstreamProxy.setText("")
        etBootstrapDns.setText(DEFAULT_BOOTSTRAP)
        swSocksMode.isChecked = false
        etTestUrl.setText(DEFAULT_TEST_URL)
        spVerbosity.setSelection(DEFAULT_VERBOSITY_INDEX)
		spDnsStrategy.setSelection(DEFAULT_DNS_STRATEGY_INDEX)
        swManualMode.isChecked = false
        etCmdPreview.setText("")
        etCmdPreview.isEnabled = false

        prefs.edit()
            .putBoolean("PROXY_ONLY", false)
            .putString("BIND_ADDRESS", DEFAULT_BIND)
            .putString("FAKE_SNI", "")
            .putString("UPSTREAM_PROXY", "")
            .putString("BOOTSTRAP_DNS", DEFAULT_BOOTSTRAP)
            .putBoolean("SOCKS_MODE", false)
            .putInt("VERBOSITY", 20)
			.putInt("TUN2PROXY_DNS_STRATEGY", 1)
            .putString("TEST_URL", DEFAULT_TEST_URL)
            .putBoolean("MANUAL_CMD_MODE", false)
            .putString("CUSTOM_CMD_STRING", "")
            .apply()

        updateCmdPreview()
    }
}
