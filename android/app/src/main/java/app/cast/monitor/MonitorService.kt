package app.cast.monitor

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 用无障碍服务监听窗口切换，得到前台应用包名后上报。
 * 这是安卓上唯一「实时且不遗漏」的通用做法；第三方自动化 App 走的也是这条路。
 */
class MonitorService : AccessibilityService() {

    private val pool = ScheduledThreadPoolExecutor(1)
    private var lastPkg: String? = null
    private var screen: String = "on"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screen = "off"
                    pool.execute { Api.send(mapOf("screen" to "off")) }
                }
                Intent.ACTION_USER_PRESENT -> {
                    screen = "on"
                    lastPkg = null   // 解锁后强制重报一次当前应用
                    pool.execute { Api.send(mapOf("screen" to "on")) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        // 心跳：让服务器知道手机还活着，避免看板误判为离线
        pool.scheduleAtFixedRate({
            val pkg = lastPkg
            if (pkg != null && screen == "on") {
                Api.send(mapOf("pkg" to pkg, "screen" to "on"))
            }
        }, 45, 45, TimeUnit.SECONDS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || NOISE.contains(pkg)) return
        if (pkg == lastPkg && screen == "on") return

        lastPkg = pkg
        screen = "on"
        val label = labelOf(pkg)
        val params = if (label == null) mapOf("pkg" to pkg) else mapOf("pkg" to pkg, "name" to label)
        pool.execute { Api.send(params) }
    }

    private fun labelOf(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (current === this) current = null
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** 状态栏、通知面板、输入法等不算「正在使用的应用」 */
        private val NOISE = setOf(
            "android",
            "com.android.systemui",
            "com.android.keyguard",
            "com.miui.securitycenter",
        )

        @Volatile var current: MonitorService? = null
    }
}
