package com.example.operaproxy
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

class AppSelectionActivity : AppCompatActivity() {
    data class AppInfo(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable, var isSelected: Boolean)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = androidx.constraintlayout.widget.ConstraintLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Используем цвет из ресурсов
        layout.setBackgroundColor(getColor(R.color.background_app))
        
        val recyclerView = RecyclerView(this)
        recyclerView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        recyclerView.layoutManager = LinearLayoutManager(this)
        layout.addView(recyclerView)
        setContentView(layout)
        
        Thread {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            
            val apps = pm.queryIntentActivities(intent, 0).map {
                val name = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                val icon = it.loadIcon(pm)
                val checked = MainActivity.selectedApps.contains(pkg)
                AppInfo(name, pkg, icon, checked)
            }
            .sortedWith(compareByDescending<AppInfo> { it.isSelected }.thenBy { it.name })
            
            runOnUiThread { recyclerView.adapter = AppAdapter(apps) }
        }.start()
    }
    
    inner class AppAdapter(private val list: List<AppInfo>) : RecyclerView.Adapter<AppAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            holder.icon.setImageDrawable(item.icon)
            holder.check.isChecked = item.isSelected
            
            // FIX: Явно задаем слушатель, чтобы избежать ошибки компиляции с if/else
            val listener = View.OnClickListener {
                item.isSelected = !item.isSelected
                holder.check.isChecked = item.isSelected
                
                if (item.isSelected) { 
                    if (!MainActivity.selectedApps.contains(item.packageName)) {
                        MainActivity.selectedApps.add(item.packageName) 
                    }
                } else { 
                    MainActivity.selectedApps.remove(item.packageName) 
                }
            }

            holder.itemView.setOnClickListener(listener)
            holder.check.setOnClickListener(listener)
        }
        override fun getItemCount() = list.size
        
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPkg)
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val check: MaterialCheckBox = v.findViewById(R.id.appCheck)
        }
    }
}