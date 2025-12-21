package com.example.operaproxy

import android.content.Context
import java.io.File

/**
 * Состояние для multi-process (:vpn).
 * SharedPreferences на Android 8+ кэшируются и не синхронизируются между процессами стабильно.
 */
object ServiceState {
    private const val FILE_NAME = "proxy_service_state"

    private fun file(ctx: Context): File = File(ctx.noBackupFilesDir, FILE_NAME)

    fun setRunning(ctx: Context, running: Boolean) {
        try {
            file(ctx).writeText(if (running) "1" else "0")
        } catch (_: Exception) {
        }
    }

    fun isRunning(ctx: Context): Boolean {
        return try {
            file(ctx).readText().trim() == "1"
        } catch (_: Exception) {
            false
        }
    }
}
