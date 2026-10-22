package app.cast.monitor

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 用无障碍服务监听窗口切换，得到前台应用包名后上报。
 * 这是安卓上唯一「实时且不遗漏」的通用做法；第三方自动化 App 走的也是这条路。
 *
 * 但事件通道本身会丢事件（快速连切时系统会合并 TYPE_WINDOW_STATE_CHANGED，
 * 回调线程繁忙时通道溢出也会丢），所以再加一条兜底：每 10 秒用系统的
 * 使用情况统计（UsageStatsManager）查一次「真正的前台是谁」做校准。
 * 事件负责零延迟，轮询负责自愈；没给「使用情况访问权限」时自动退回纯事件模式。
 */
class MonitorService : AccessibilityService() {

    private val pool = ScheduledThreadPoolExecutor(1)

    @Volatile private var lastPkg: String? = null
    @Volatile private var screen: String = "on"

    /** 包名 → 显示名缓存；解析失败的包名不缓存，下次再试 */
    private val labels = HashMap<String, String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screen = "off"
                    pool.execute { sendScreen("off", 0) }
                }
                Intent.ACTION_USER_PRESENT -> {
                    screen = "on"
                    lastPkg = null   // 解锁后强制重报一次当前应用
                    pool.execute {
                        sendScreen("on", 0)
                        tick()       // 立刻校准一次，别让看板停在息屏前那个应用上
                    }
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
        // 心跳 + 校准：让服务器知道手机还活着，并纠正被丢掉的事件
        pool.scheduleAtFixedRate({ tick() }, TICK_SEC, TICK_SEC, TimeUnit.SECONDS)
    }

    /** 每 10 秒一次：以系统统计为准补报当前应用；拿不到就只刷在线状态 */
    private fun tick() {
        try {
            if (screen != "on") {
                // 息屏期间不查前台，避免无谓的 binder 调用和唤醒
                Api.send(mapOf("screen" to screen))
                return
            }
            val cur = foregroundPkg()
            if (cur == null) {
                // 未授予使用情况访问权限：退回纯事件模式，靠重发 lastPkg 维持在线
                val pkg = lastPkg
                Api.send(
                    if (pkg != null) mapOf("pkg" to pkg, "screen" to "on")
                    else mapOf("screen" to "on")
                )
                return
            }
            if (cur == packageName || NOISE.contains(cur)) return
            if (cur != lastPkg) {
                Log.d("appcast", "reconcile: $lastPkg -> $cur")
                lastPkg = cur
            }
            sendState(cur)
        } catch (t: Throwable) {
            Log.w("appcast", "tick failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || NOISE.contains(pkg)) return
        if (pkg == lastPkg && screen == "on") return

        lastPkg = pkg
        screen = "on"
        // 显示名要查 PackageManager，放到工作线程做，别堵住事件回调
        pool.execute { sendState(pkg) }
    }

    private fun sendState(pkg: String) {
        val label = labelOf(pkg)
        val params = if (label == null) mapOf("pkg" to pkg) else mapOf("pkg" to pkg, "name" to label)
        Api.send(params)
        // 失败不单独重试：下一次 tick 会把当前状态再发一遍
    }

    /** screen 状态不在 tick 的重发范围内，所以自己重试几次 */
    private fun sendScreen(state: String, attempt: Int) {
        val ok = Api.send(mapOf("screen" to state))
        if (!ok && attempt < SCREEN_RETRY) {
            pool.schedule({ sendScreen(state, attempt + 1) }, 5, TimeUnit.SECONDS)
        }
    }

    private fun labelOf(pkg: String): String? {
        labels[pkg]?.let { return it }
        val v = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            null
        }
        if (v != null) labels[pkg] = v
        return v
    }

    /** 从系统使用情况统计里取最近一次「切到前台」的包名；没权限时恒为 null */
    private fun foregroundPkg(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        return try {
            val events = usm.queryEvents(now - LOOKBACK_MS, now)
            var pkg: String? = null
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == EVENT_MOVED_TO_FOREGROUND) pkg = ev.packageName
            }
            pkg
        } catch (t: Throwable) {
            null
        }
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

        private const val TICK_SEC = 10L
        private const val LOOKBACK_MS = 15_000L   // 略大于 tick 间隔，避免落在两次查询之间
        private const val SCREEN_RETRY = 3
        private const val EVENT_MOVED_TO_FOREGROUND = 1  // MOVE_TO_FOREGROUND / API 29 起叫 ACTIVITY_RESUMED

        @Volatile var current: MonitorService? = null
    }
}
