package com.example.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight, thread-safe persistence for Volume Guard settings.
 *
 * NOTE: As per design requirements:
 * - NO "initial volume" or "previous volume" is ever stored or tracked.
 * - The target volume under protection is ALWAYS strictly 0.
 * - Only the binary guard ON/OFF user preference is persisted.
 */
class GuardPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var isGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARD_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUARD_ENABLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "volume_guard_prefs"
        private const val KEY_GUARD_ENABLED = "key_guard_enabled"

        @Volatile
        private var instance: GuardPreferences? = null

        fun getInstance(context: Context): GuardPreferences {
            return instance ?: synchronized(this) {
                instance ?: GuardPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
