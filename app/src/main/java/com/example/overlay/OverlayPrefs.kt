package com.example.overlay

import android.content.Context

/** Preferências do painel flutuante (ativado, lado da tela e posição vertical). */
object OverlayPrefs {
    const val SIDE_LEFT = 0
    const val SIDE_RIGHT = 1

    private const val FILE = "overlay_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SIDE = "side"
    private const val KEY_Y = "y_fraction"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun side(context: Context): Int = prefs(context).getInt(KEY_SIDE, SIDE_RIGHT)

    fun yFraction(context: Context): Float = prefs(context).getFloat(KEY_Y, 0.4f)

    fun savePosition(context: Context, side: Int, yFraction: Float) {
        prefs(context).edit().putInt(KEY_SIDE, side).putFloat(KEY_Y, yFraction).apply()
    }
}
