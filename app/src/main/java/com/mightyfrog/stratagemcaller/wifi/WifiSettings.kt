package com.mightyfrog.stratagemcaller.wifi

import android.content.SharedPreferences
import com.mightyfrog.stratagemcaller.macro.MacroEngine

data class WifiSettings(
    val delayMs: String = MacroEngine.DEFAULT_KEY_HOLD_MS.toString(),
    val mapping: GameKeyMapping = GameKeyMapping()
) {
    companion object {
        internal fun migrateDelay(savedDelay: String?): String =
            if (savedDelay.isNullOrBlank() || savedDelay == "40" || savedDelay == "90") {
                MacroEngine.DEFAULT_KEY_HOLD_MS.toString()
            } else {
                savedDelay
            }

        fun load(preferences: SharedPreferences): WifiSettings {
            val savedDelay = preferences.getString("macro_delay_ms", MacroEngine.DEFAULT_KEY_HOLD_MS.toString())
            val delayMs = migrateDelay(savedDelay)

            val savedUp = preferences.getString("key_up", "UP").orEmpty()
            val savedDown = preferences.getString("key_down", "DOWN").orEmpty()
            val savedLeft = preferences.getString("key_left", "LEFT").orEmpty()
            val savedRight = preferences.getString("key_right", "RIGHT").orEmpty()
            val mapping = GameKeyMapping(
                activate = preferences.getString("key_activate", "LEFTCTRL").orEmpty(),
                up = if (savedUp == "W") "UP" else savedUp,
                down = if (savedDown == "S") "DOWN" else savedDown,
                left = if (savedLeft == "A") "LEFT" else savedLeft,
                right = if (savedRight == "D") "RIGHT" else savedRight
            )

            preferences.edit()
                .putString("macro_delay_ms", delayMs)
                .putString("key_up", mapping.up)
                .putString("key_down", mapping.down)
                .putString("key_left", mapping.left)
                .putString("key_right", mapping.right)
                .apply()

            return WifiSettings(delayMs, mapping)
        }
    }

    fun persist(preferences: SharedPreferences) {
        preferences.edit()
            .putString("macro_delay_ms", delayMs)
            .putString("key_activate", mapping.activate)
            .putString("key_up", mapping.up)
            .putString("key_down", mapping.down)
            .putString("key_left", mapping.left)
            .putString("key_right", mapping.right)
            .apply()
    }
}
