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

    private fun isServiceRunning(): Boolean {
        return ServiceState.isRunning(this)
    }

    override fun onClick() {
        super.onClick()

        if (isServiceRunning()) {
            val stopIntent = Intent(this, ProxyVpnService::class.java).apply {
                action = "STOP_VPN"
            }
            startService(stopIntent)
            updateTile(Tile.STATE_INACTIVE)
            return
        }

        // Запуск
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("OPEN_VPN_PREPARE", true)
            }

            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(activityIntent)
            }
            return
        }

        startProxyService()
        updateTile(Tile.STATE_ACTIVE)
    }

    private fun updateTileState() {
        updateTile(if (isServiceRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
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

        val countryId = prefs.getInt("COUNTRY_ID", R.id.rbEU)
        val country = when (countryId) {
            R.id.rbAS -> "AS"
            R.id.rbAM -> "AM"
            else -> "EU"
        }
        intent.putExtra("COUNTRY", country)

        val dns = prefs.getString("DNS", "8.8.8.8").orEmpty().ifEmpty { "8.8.8.8" }
        intent.putExtra("DNS", dns)

        val savedApps = prefs.getStringSet("APPS", emptySet()).orEmpty()
        if (savedApps.isNotEmpty()) {
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
		intent.putExtra("FORCE_INVERT_APP_LIST", prefs.getBoolean("FORCE_INVERT_APP_LIST", false))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            val cn = ComponentName(context, ProxyTileService::class.java)
            requestListeningState(context, cn)
        }
    }
}
