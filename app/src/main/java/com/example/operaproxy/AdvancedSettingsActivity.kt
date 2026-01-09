package com.example.operaproxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var swProxyOnly: SwitchMaterial
    private lateinit var etBindAddress: TextInputEditText
    private lateinit var etFakeSni: TextInputEditText
    private lateinit var etUpstreamProxy: TextInputEditText
    private lateinit var tilBootstrapDns: TextInputLayout
    private lateinit var etBootstrapDns: TextInputEditText
    private lateinit var tilApiAddress: TextInputLayout
    private lateinit var etApiAddress: TextInputEditText
    private lateinit var swSocksMode: SwitchMaterial
    private lateinit var spVerbosity: Spinner
    private lateinit var spDnsStrategy: Spinner
    private lateinit var ivHelpDnsStrategy: ImageView
    private lateinit var etTestUrl: TextInputEditText
    private lateinit var swManualMode: SwitchMaterial
    private lateinit var etCmdPreview: TextInputEditText

    // Значения по умолчанию
    private val DEFAULT_BIND = "127.0.0.1:1085"
    private val DEFAULT_BOOTSTRAP =
        "https://1.1.1.3/dns-query,https://8.8.8.8/dns-query,https://dns.google/dns-query,tls://9.9.9.9:853,https://security.cloudflare-dns.com/dns-query,https://fidelity.vm-0.com/q,https://wikimedia-dns.org/dns-query,https://dns.adguard-dns.com/dns-query,https://dns.quad9.net/dns-query,https://dns.comss.one/dns-query,https://router.comss.one/dns-query"
    private val DEFAULT_TEST_URL =
        "https://ajax.googleapis.com/ajax/libs/indefinite-observable/2.0.1/indefinite-observable.bundle.js"
    private val DEFAULT_VERBOSITY_INDEX = 2 // 20 Info
    private val DEFAULT_DNS_STRATEGY_INDEX = 1 // 1 = OverTcp (Default)

    // Временные переменные для генерации превью
    private var mainCountry = "EU"
    private var mainDns = "8.8.8.8"

    // Лаунчеры для сохранения/загрузки файла
    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                writeSettingsToUri(uri)
            }
        }
    }

    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                readSettingsFromUri(uri)
            }
        }
    }

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
        mainDns = prefs.getString("DNS", "8.8.8.8") ?: "9.9.9.9"

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupTooltips()
        setupApiAddressLogic()
        setupSwitchImmediatePersistence()
        setupBootstrapDnsClick()
        loadValues()
        setupLivePreview()

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
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.advanced_settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_json -> {
                saveValues() // Сначала сохраняем текущие поля в префы
                startExport()
                true
            }
            R.id.action_import_json -> {
                startImport()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
        mainDns = prefs.getString("DNS", "8.8.8.8") ?: "9.9.9.9"
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
        tilApiAddress = findViewById(R.id.tilApiAddress)
        etApiAddress = findViewById(R.id.etApiAddress)
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
                updateCmdPreview()
            }
        }
    }

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

    private fun setupLivePreview() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateCmdPreview()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        val fields = listOf(etBindAddress, etFakeSni, etUpstreamProxy, etBootstrapDns, etTestUrl, etApiAddress)
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
        if (swManualMode.isChecked) return

        val sb = StringBuilder()

        sb.append("-bind-address ")
            .append(etBindAddress.text.toString().ifEmpty { DEFAULT_BIND })
        sb.append(" -country ").append(mainCountry)

        val verbosityValues = resources.getStringArray(R.array.verbosity_values)
        val pos = spVerbosity.selectedItemPosition
        val safePos = if (pos in verbosityValues.indices) pos else DEFAULT_VERBOSITY_INDEX
        sb.append(" -verbosity ").append(verbosityValues[safePos])

        val bootstrap = etBootstrapDns.text.toString()
        if (bootstrap.isNotEmpty()) sb.append(" -bootstrap-dns ").append(bootstrap)

        val sni = etFakeSni.text.toString()
        if (sni.isNotEmpty()) sb.append(" -fake-SNI ").append(sni)

        val proxy = etUpstreamProxy.text.toString()
        if (proxy.isNotEmpty()) sb.append(" -proxy ").append(proxy)

        if (swSocksMode.isChecked) sb.append(" -socks-mode")

        val apiAddr = etApiAddress.text.toString()
        if (apiAddr.isNotEmpty()) sb.append(" -api-address ").append(apiAddr)

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

   private fun setupApiAddressLogic() {
        // Логика нажатия на иконку списка (пресеты)
        tilApiAddress.setEndIconOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("api.sec-tunnel.com")
            popup.menu.add("api2.sec-tunnel.com")
            popup.menu.add("Справка")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Справка" -> {
                        showInfoDialog(getString(R.string.pref_api_address), getString(R.string.help_api_address))
                    }
                    else -> {
                        etApiAddress.setText(item.title)
                        checkForBootstrapConflict()
                    }
                }
                true
            }
            popup.show()
        }
    }

    // Функция проверки конфликта и предложения очистки
    private fun checkForBootstrapConflict() {
        val bootstrapVal = etBootstrapDns.text.toString()
        if (bootstrapVal.isNotEmpty() && bootstrapVal != " ") {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_bootstrap_title)
                .setMessage(R.string.dialog_clear_bootstrap_msg)
                .setPositiveButton("Да") { _, _ ->
                    etBootstrapDns.setText("")
                    prefs.edit().putString("BOOTSTRAP_DNS", "").apply()
                    updateCmdPreview()
                }
                .setNegativeButton("Нет", null)
                .show()
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
        etApiAddress.setText(prefs.getString("API_ADDRESS", ""))
        etTestUrl.setText(prefs.getString("TEST_URL", DEFAULT_TEST_URL))

        try {
            val strategyVal = prefs.getInt("TUN2PROXY_DNS_STRATEGY", 1)
            val strategyValues = resources.getStringArray(R.array.dns_strategy_values)
            var index = strategyValues.indexOf(strategyVal.toString())
            if (index < 0 || index >= spDnsStrategy.adapter.count) {
                index = DEFAULT_DNS_STRATEGY_INDEX
            }
            spDnsStrategy.setSelection(index)
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            spVerbosity.setSelection(DEFAULT_VERBOSITY_INDEX)
        }

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
                "Должен начинаться с http://, https://, socks5://, socks5h://."
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
        val safeStratPos =
            if (stratPos >= 0 && stratPos < strategyValues.size) stratPos else DEFAULT_DNS_STRATEGY_INDEX
        val selectedStrategy = strategyValues[safeStratPos].toInt()

        prefs.edit()
            .putBoolean("PROXY_ONLY", swProxyOnly.isChecked)
            .putString("BIND_ADDRESS", etBindAddress.text.toString())
            .putString("FAKE_SNI", etFakeSni.text.toString())
            .putString("UPSTREAM_PROXY", etUpstreamProxy.text.toString())
            .putString("BOOTSTRAP_DNS", etBootstrapDns.text.toString())
            .putBoolean("SOCKS_MODE", swSocksMode.isChecked)
            .putString("API_ADDRESS", etApiAddress.text.toString())
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
            .putString("API_ADDRESS", "")
            .putInt("VERBOSITY", 20)
            .putInt("TUN2PROXY_DNS_STRATEGY", 1)
            .putString("TEST_URL", DEFAULT_TEST_URL)
            .putBoolean("MANUAL_CMD_MODE", false)
            .putString("CUSTOM_CMD_STRING", "")
            .remove("APPS")
            .apply()
        
        updateCmdPreview()
    }

    // --- JSON Export/Import Logic ---

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "operaproxy_config.json")
        }
        exportJsonLauncher.launch(intent)
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importJsonLauncher.launch(intent)
    }

    private fun writeSettingsToUri(uri: Uri) {
        try {
            val json = JSONObject()
            
            // Основные настройки
            json.put("DNS", prefs.getString("DNS", "8.8.8.8"))
            
            // Преобразуем ID страны в строку для переносимости
            val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
            val countryCode = when(countryId) {
                R.id.rbAS -> "AS"
                R.id.rbAM -> "AM"
                else -> "EU"
            }
            json.put("COUNTRY_CODE", countryCode)

            json.put("PROXY_APP_MODE", prefs.getInt("PROXY_APP_MODE", 0))
            json.put("PROXY_ONLY", prefs.getBoolean("PROXY_ONLY", false))
            json.put("SOCKS_MODE", prefs.getBoolean("SOCKS_MODE", false))
            json.put("BIND_ADDRESS", prefs.getString("BIND_ADDRESS", DEFAULT_BIND))
            json.put("FAKE_SNI", prefs.getString("FAKE_SNI", ""))
            json.put("UPSTREAM_PROXY", prefs.getString("UPSTREAM_PROXY", ""))
            json.put("BOOTSTRAP_DNS", prefs.getString("BOOTSTRAP_DNS", DEFAULT_BOOTSTRAP))
            json.put("API_ADDRESS", prefs.getString("API_ADDRESS", ""))
            json.put("VERBOSITY", prefs.getInt("VERBOSITY", 20))
            json.put("TUN2PROXY_DNS_STRATEGY", prefs.getInt("TUN2PROXY_DNS_STRATEGY", 1))
            json.put("TEST_URL", prefs.getString("TEST_URL", DEFAULT_TEST_URL))
            json.put("MANUAL_CMD_MODE", prefs.getBoolean("MANUAL_CMD_MODE", false))
            json.put("CUSTOM_CMD_STRING", prefs.getString("CUSTOM_CMD_STRING", ""))

            // Список приложений
            val appsArray = JSONArray()
            val appsSet = prefs.getStringSet("APPS", emptySet())
            appsSet?.forEach { appsArray.put(it) }
            json.put("APPS", appsArray)

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                var jsonString = json.toString(4)
                // Убираем экранирование слешей
                jsonString = jsonString.replace("\\/", "/")
                outputStream.write(jsonString.toByteArray())
            }
            Toast.makeText(this, "Настройки сохранены в JSON", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readSettingsFromUri(uri: Uri) {
        try {
            val stringBuilder = StringBuilder()
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line)
                    }
                }
            }

            val json = JSONObject(stringBuilder.toString())
            val editor = prefs.edit()

            // Восстанавливаем простые типы
            if (json.has("DNS")) editor.putString("DNS", json.getString("DNS"))
            
            // Восстанавливаем страну
            if (json.has("COUNTRY_CODE")) {
                val cCode = json.getString("COUNTRY_CODE")
                val cId = when(cCode) {
                    "AS" -> R.id.rbAS
                    "AM" -> R.id.rbAM
                    else -> R.id.rbEU
                }
                editor.putInt("COUNTRY_ID", cId)
            }

            if (json.has("PROXY_APP_MODE")) editor.putInt("PROXY_APP_MODE", json.getInt("PROXY_APP_MODE"))
            if (json.has("PROXY_ONLY")) editor.putBoolean("PROXY_ONLY", json.getBoolean("PROXY_ONLY"))
            if (json.has("SOCKS_MODE")) editor.putBoolean("SOCKS_MODE", json.getBoolean("SOCKS_MODE"))
            if (json.has("BIND_ADDRESS")) editor.putString("BIND_ADDRESS", json.getString("BIND_ADDRESS"))
            if (json.has("FAKE_SNI")) editor.putString("FAKE_SNI", json.getString("FAKE_SNI"))
            if (json.has("UPSTREAM_PROXY")) editor.putString("UPSTREAM_PROXY", json.getString("UPSTREAM_PROXY"))
            if (json.has("BOOTSTRAP_DNS")) editor.putString("BOOTSTRAP_DNS", json.getString("BOOTSTRAP_DNS"))
            if (json.has("API_ADDRESS")) editor.putString("API_ADDRESS", json.getString("API_ADDRESS"))
            if (json.has("VERBOSITY")) editor.putInt("VERBOSITY", json.getInt("VERBOSITY"))
            if (json.has("TUN2PROXY_DNS_STRATEGY")) editor.putInt("TUN2PROXY_DNS_STRATEGY", json.getInt("TUN2PROXY_DNS_STRATEGY"))
            if (json.has("TEST_URL")) editor.putString("TEST_URL", json.getString("TEST_URL"))
            if (json.has("MANUAL_CMD_MODE")) editor.putBoolean("MANUAL_CMD_MODE", json.getBoolean("MANUAL_CMD_MODE"))
            if (json.has("CUSTOM_CMD_STRING")) editor.putString("CUSTOM_CMD_STRING", json.getString("CUSTOM_CMD_STRING"))

            // Восстанавливаем список приложений
            if (json.has("APPS")) {
                val appsArray = json.getJSONArray("APPS")
                val appsSet = HashSet<String>()
                for (i in 0 until appsArray.length()) {
                    appsSet.add(appsArray.getString(i))
                }
                editor.putStringSet("APPS", appsSet)
                // Обновляем статический список в MainActivity, чтобы изменения применились сразу
                MainActivity.selectedApps = ArrayList(appsSet)
            }

            editor.apply()
            
            // Обновляем UI текущей активности
            loadValues()
            
            Toast.makeText(this, "Настройки из JSON загружены", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}