package com.solmacro

import android.content.Context

object Cfg {
    const val ROBLOX = "com.roblox.client"
    val keys = listOf("cast", "reel", "inv", "sellall", "confirm", "joy", "cam", "shift")
    val label = mapOf(
        "cast" to "Cast button", "reel" to "Reel area", "inv" to "Inventory button",
        "sellall" to "Sell all button", "confirm" to "Confirm button",
        "joy" to "Movement joystick center", "cam" to "Camera swipe start", "shift" to "Shift Lock button"
    )
    private val def = mapOf(
        "cast" to 900, "wait" to 4000, "taps" to 12, "gap" to 120, "mdelay" to 700,
        "every" to 10, "sell" to 1,
        "cast_x" to 2000, "cast_y" to 820, "reel_x" to 1200, "reel_y" to 540,
        "inv_x" to 120, "inv_y" to 500, "sellall_x" to 1100, "sellall_y" to 760,
        "confirm_x" to 1250, "confirm_y" to 660,
        "joy_x" to 250, "joy_y" to 800, "cam_x" to 1700, "cam_y" to 500, "shift_x" to 2150, "shift_y" to 250, "joyr" to 130,
        "icon_custom" to 0, "detect" to 0, "debug" to 0, "whon" to 1, "whevery" to 10, "maxcatch" to 0, "color2" to -1, "axis" to 0, "holddir" to 1, "deadband" to 15, "holdms" to 300, "tol" to 40, "minpx" to 20, "maxwait" to 30, "color" to -1,
        "r1_x" to 900, "r1_y" to 300, "r2_x" to 1500, "r2_y" to 500
    )

    private fun sp(c: Context) = c.getSharedPreferences("macro", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): Int = sp(c).getInt(k, def[k] ?: 0)
    fun gets(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun puts(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun put(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
}
