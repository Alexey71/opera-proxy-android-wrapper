package com.example.operaproxy

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (ProxyVpnService.isRunning) {
            // Остановка сервиса
            val intent = Intent(this, ProxyVpnService::class.java)
            intent.action = "STOP_VPN"
            startService(intent)
            // Обновляем UI
            updateTile(Tile.STATE_INACTIVE)
        } else {
            // Запуск сервиса
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                // Требуется подтверждение прав VPN, открываем Activity
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                
                // В Android 14 (API 34) метод с Intent устарел, используем PendingIntent
                if (Build.VERSION.SDK_INT >= 34) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } else {
                // Права есть, запускаем сервис напрямую с сохраненными настройками
                startProxyService()
                updateTile(Tile.STATE_ACTIVE)
            }
        }
    }

    private fun updateTileState() {
        val state = if (ProxyVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        updateTile(state)
    }

    private fun updateTile(state: Int) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

    private fun startProxyService() {
        val prefs = getSharedPreferences("OperaProxyPrefs", Context.MODE_PRIVATE)
        val intent = Intent(this, ProxyVpnService::class.java)

        // Восстанавливаем настройки, аналогично MainActivity
        val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
        val country = when(countryId) { 
            R.id.rbAS -> "AS" 
            R.id.rbAM -> "AM" 
            else -> "EU" 
        }
        intent.putExtra("COUNTRY", country)
        
        var dns = prefs.getString("DNS", "8.8.8.8")
        if (dns.isNullOrEmpty()) dns = "8.8.8.8"
        intent.putExtra("DNS", dns)

        val savedApps = prefs.getStringSet("APPS", emptySet())
        if (!savedApps.isNullOrEmpty()) {
            intent.putStringArrayListExtra("ALLOWED_APPS", ArrayList(savedApps))
        }

        intent.putExtra("BIND_ADDRESS", prefs.getString("BIND_ADDRESS", "127.0.0.1:1080"))
        intent.putExtra("FAKE_SNI", prefs.getString("FAKE_SNI", ""))
        intent.putExtra("UPSTREAM_PROXY", prefs.getString("UPSTREAM_PROXY", ""))
        intent.putExtra("BOOTSTRAP_DNS", prefs.getString("BOOTSTRAP_DNS", ""))
        intent.putExtra("SOCKS_MODE", prefs.getBoolean("SOCKS_MODE", false))
        intent.putExtra("PROXY_ONLY", prefs.getBoolean("PROXY_ONLY", false))
        intent.putExtra("VERBOSITY", prefs.getInt("VERBOSITY", 20))
        intent.putExtra("TUN2PROXY_DNS_STRATEGY", prefs.getInt("TUN2PROXY_DNS_STRATEGY", 1))
        intent.putExtra("TEST_URL", prefs.getString("TEST_URL", ""))
        intent.putExtra("MANUAL_CMD_MODE", prefs.getBoolean("MANUAL_CMD_MODE", false))
        intent.putExtra("CUSTOM_CMD_STRING", prefs.getString("CUSTOM_CMD_STRING", ""))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    companion object {
        // Метод для вызова обновления плитки извне (например, из Service)
        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
            }
        }
    }
}