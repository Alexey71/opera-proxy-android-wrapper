package com.example.operaproxy

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)
        selectedPkgs.clear()
        selectedPkgs.addAll(prefs.getStringSet("APPS", emptySet()).orEmpty())

        // Корневой лейаут: вертикальный, фон как в основном приложении
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_app))
            setPadding(12, 12, 12, 12)
        }

        // Поле поиска по приложениям
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
        root.addView(searchInput)

        // Список приложений
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
        }
        root.addView(recyclerView)

        setContentView(root)

        // Загрузка списка приложений в фоне
        Thread {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val apps = pm.queryIntentActivities(intent, 0).map {
                val name = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                val icon = it.loadIcon(pm)
                val checked = selectedPkgs.contains(pkg)
                AppInfo(name, pkg, icon, checked)
            }.sortedWith(compareByDescending<AppInfo> { it.isSelected }.thenBy { it.name })

            allApps.clear()
            allApps.addAll(apps)

            runOnUiThread {
                adapter = AppAdapter(allApps)
                recyclerView.adapter = adapter
            }
        }.start()

        // Фильтрация по имени / пакету
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                adapter.filter(q)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun persistSelection() {
        prefs.edit().putStringSet("APPS", selectedPkgs.toSet()).apply()
    }

    inner class AppAdapter(private val fullList: List<AppInfo>) :
        RecyclerView.Adapter<AppAdapter.Holder>() {

        private val filteredList: MutableList<AppInfo> = fullList.toMutableList()

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
            holder.check.isChecked = item.isSelected

            val listener = View.OnClickListener {
                item.isSelected = !item.isSelected
                holder.check.isChecked = item.isSelected

                if (item.isSelected) {
                    selectedPkgs.add(item.packageName)
                } else {
                    selectedPkgs.remove(item.packageName)
                }
                persistSelection()
            }

            holder.itemView.setOnClickListener(listener)
            holder.check.setOnClickListener(listener)
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
            // сохраняем сортировку: выбранные наверх
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
