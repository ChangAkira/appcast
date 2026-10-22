package app.cast.monitor

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Api {
    /** 成功（HTTP 2xx）返回 true；调用方据此决定要不要重试 */
    fun send(params: Map<String, String>): Boolean {
        val query = (mapOf("t" to Cfg.TOKEN) + params).entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return try {
            val conn = URL("${Cfg.BASE}/api/report?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            Log.d("appcast", "report -> HTTP $code ($params)")
            code in 200..299
        } catch (e: Exception) {
            Log.w("appcast", "report failed: ${e.message}")
            false
        }
    }
}
