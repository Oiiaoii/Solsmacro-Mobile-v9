package com.solmacro

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

object Hook {
    fun send(c: Context, title: String, desc: String, color: Int = 0x3EA6FF) {
        val url = Cfg.gets(c, "webhook").trim()
        if (Cfg.get(c, "whon") != 1 || !url.startsWith("https://")) return
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "SolMacro/1.0")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = "{\"username\":\"Sol Macro\",\"embeds\":[{\"title\":\"" + esc(title) +
                    "\",\"description\":\"" + esc(desc) + "\",\"color\":" + color + "}]}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                Dbg.add("Webhook replied HTTP " + conn.responseCode)
                conn.disconnect()
            } catch (e: Exception) {
                Dbg.add("Webhook failed: " + e.message)
            }
        }.start()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
