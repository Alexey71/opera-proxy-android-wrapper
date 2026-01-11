package com.example.operaproxy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

class AppSelectionActivity : AppCompatActivity() {

    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        var isSelected: Boolean
    )

    private lateinit var prefs: android.content.SharedPreferences
    private val selectedPkgs: MutableSet<String> = mutableSetOf()

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var adapter: AppAdapter
    private val allApps: MutableList<AppInfo> = mutableListOf()
    
    // Флаг для отображения системных приложений
    private var showSystemApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)
        selectedPkgs.clear()
        selectedPkgs.addAll(prefs.getStringSet("APPS", emptySet()).orEmpty())

        // Корневой лейаут
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_app))
        }

        // Добавляем Toolbar
        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_card))
            setTitleTextColor(ContextCompat.getColor(context, R.color.white))
            title = getString(R.string.app_selector_btn) // "Выбор приложений"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        setSupportActionBar(toolbar)

        // Контейнер для контента с отступами
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(12, 12, 12, 12)
        }
        root.addView(contentLayout)

        // Поле поиска
        searchInput = EditText(this).apply {
            hint = "Поиск приложений"
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
        contentLayout.addView(searchInput)

        // Список
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
        }
        contentLayout.addView(recyclerView)

        setContentView(root)

        // Инициализация адаптера пустым списком
        adapter = AppAdapter(allApps)
        recyclerView.adapter = adapter

        loadApps()

        // Фильтрация
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                adapter.filter(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // Создание меню
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_app_selection, menu)
        return true
    }

    // Обработка кликов меню
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                selectAll(true)
                true
            }
            R.id.action_clear_all -> {
                selectAll(false)
                true
            }
            R.id.action_show_system -> {
                item.isChecked = !item.isChecked
                showSystemApps = item.isChecked
                loadApps() // Перезагрузка списка
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun selectAll(select: Boolean) {
        // Меняем состояние только у тех, что сейчас в фильтрованном списке
        adapter.getFilteredItems().forEach { appInfo ->
            appInfo.isSelected = select
            if (select) {
                selectedPkgs.add(appInfo.packageName)
            } else {
                selectedPkgs.remove(appInfo.packageName)
            }
        }
        persistSelection()
        adapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val selfPkg = packageName

            val apps = if (showSystemApps) {
                // Загружаем ВСЕ пакеты
                pm.getInstalledPackages(0).mapNotNull { pkgInfo ->
                    if (pkgInfo.packageName == selfPkg) return@mapNotNull null
                    
                    val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                    val name = appInfo.loadLabel(pm).toString()
                    val icon = appInfo.loadIcon(pm)
                    val checked = selectedPkgs.contains(pkgInfo.packageName)
                    AppInfo(name, pkgInfo.packageName, icon, checked)
                }
            } else {
                // Только лаунчеры (обычные приложения)
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                
                pm.queryIntentActivities(intent, 0).mapNotNull {
                    val pkg = it.activityInfo.packageName
                    if (pkg == selfPkg) return@mapNotNull null
                    val name = it.loadLabel(pm).toString()
                    val icon = it.loadIcon(pm)
                    val checked = selectedPkgs.contains(pkg)
                    AppInfo(name, pkg, icon, checked)
                }
            }

            // Убираем дубликаты (для queryIntentActivities, если у приложения несколько активностей)
            val uniqueApps = apps.distinctBy { it.packageName }
                .sortedWith(compareByDescending<AppInfo> { it.isSelected }.thenBy { it.name })

            allApps.clear()
            allApps.addAll(uniqueApps)

            runOnUiThread {
                adapter.updateData(allApps)
                // Применяем текущий фильтр поиска, если он есть
                adapter.filter(searchInput.text.toString())
            }
        }.start()
    }

    private fun persistSelection() {
        prefs.edit().putStringSet("APPS", selectedPkgs.toSet()).apply()
        // Обновляем статический список в MainActivity сразу
        MainActivity.selectedApps = ArrayList(selectedPkgs)
    }

    inner class AppAdapter(private var fullList: List<AppInfo>) :
        RecyclerView.Adapter<AppAdapter.Holder>() {

        private val filteredList: MutableList<AppInfo> = fullList.toMutableList()

        fun updateData(newList: List<AppInfo>) {
            fullList = newList
            filter("") // Сброс фильтра при полной перезагрузке
        }
        
        fun getFilteredItems(): List<AppInfo> = filteredList

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = filteredList[position]
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            holder.icon.setImageDrawable(item.icon)
            
            // Удаляем слушатель перед установкой состояния, чтобы не триггерить лишние события
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item.isSelected

            val performClick = {
                val newState = !item.isSelected
                item.isSelected = newState
                holder.check.isChecked = newState

                if (item.isSelected) {
                    selectedPkgs.add(item.packageName)
                } else {
                    selectedPkgs.remove(item.packageName)
                }
                persistSelection()
            }

            holder.itemView.setOnClickListener { performClick() }
            holder.check.setOnClickListener { performClick() }
        }

        override fun getItemCount() = filteredList.size

        fun filter(query: String) {
            val q = query.trim().lowercase()
            filteredList.clear()
            if (q.isEmpty()) {
                filteredList.addAll(fullList)
            } else {
                filteredList.addAll(
                    fullList.filter {
                        it.name.lowercase().contains(q) ||
                                it.packageName.lowercase().contains(q)
                    }
                )
            }
            // Сортировка, выбранные всегда сверху
            filteredList.sortWith(
                compareByDescending<AppInfo> { it.isSelected }.thenBy { it.name }
            )
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPkg)
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val check: MaterialCheckBox = v.findViewById(R.id.appCheck)
        }
    }
}