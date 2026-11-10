package com.solmacro

object Dbg {
    private val lines = ArrayList<String>()
    @Volatile var state = "Idle"
    @Volatile var catches = 0
    @Volatile var sells = 0
    @Volatile var startMs = 0L
    @Volatile var fps = 0
    @Volatile var latency = 0

    @Synchronized
    fun add(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.add("$t $s")
        if (lines.size > 150) lines.removeAt(0)
    }

    @Synchronized
    fun tail(n: Int): String = lines.takeLast(n).joinToString("\n")
}
