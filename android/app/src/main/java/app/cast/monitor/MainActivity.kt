package app.cast.monitor

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 32) }
        box.addView(status)

        box.addView(button("开启 / 管理 无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(button("授予使用情况访问权限（事件丢失时用于自愈）") {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "这个系统没有该设置页，跳过即可", Toast.LENGTH_LONG).show()
            }
        })
        box.addView(button("发一条测试上报") {
            Api.send(mapOf("pkg" to "app.cast.monitor.test", "name" to "手动测试"))
            Toast.makeText(this, "已发送，去看板上确认", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply {
            addView(box)
            isVerticalScrollBarEnabled = true
        })
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        val a11y = if (MonitorService.current != null) "已开启（实时）" else "未开启"
        val usage = if (usageGranted()) "已授予（每 10 秒校准一次）" else "未授予（只靠事件，丢了要等下次切换才纠正）"
        status.text = "无障碍服务：$a11y\n使用情况访问：$usage\n服务器：${Cfg.BASE}"
        status.gravity = Gravity.START
    }

    /** 没有专门的 API 能查这个权限，只能用「能不能读到统计」来间接判断 */
    private fun usageGranted(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        return try {
            val events = usm.queryEvents(now - 600_000L, now)
            val ev = UsageEvents.Event()
            var anyEvent = false
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                anyEvent = true
            }
            anyEvent || usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - 86_400_000L, now
            )?.isNotEmpty() == true
        } catch (t: Throwable) {
            false
        }
    }
}
