package app.cast.monitor

import android.app.Activity
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
        val svc = MonitorService.current
        val on = svc != null
        status.text = if (on) "服务状态：监听中\n服务器：${Cfg.BASE}" else "服务状态：未开启\n请在无障碍里启用 AppCast Monitor"
        status.gravity = Gravity.START
    }
}
