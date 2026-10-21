package app.cast.monitor

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Api {
    fun send(params: Map<String, String>) {
        val query = (mapOf("t" to Cfg.TOKEN) + params).entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        try {
            val conn = URL("${Cfg.BASE}/api/report?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            Log.d("appcast", "report -> HTTP $code ($params)")
        } catch (e: Exception) {
            Log.w("appcast", "report failed: ${e.message}")
        }
    }
}
