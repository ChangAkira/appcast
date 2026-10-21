# android/ —— 可选的极简安卓 App 源码

这是接入指南里的**方案 C**。绝大多数人不需要它：用 MacroDroid（方案 A）5 分钟就能接上。
只有在这些情况下才用它：

- 不想装第三方自动化 App；
- 要求切换应用**零遗漏**（无障碍服务是系统级事件，比定时轮询可靠）；
- 想把它做得更省电 / 定制上报内容。

代码总量：4 个 Kotlin 文件 + 1 个清单 + 2 个资源文件，无第三方依赖，唯一权限是**无障碍服务** + **网络**。

## 文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/app/cast/monitor/Cfg.kt` | **改这里**：服务器地址和 Token |
| `.../MonitorService.kt` | 无障碍服务：窗口切换 → 取包名与应用名 → 上报；息屏/解锁上报 `screen`；每 45 秒心跳 |
| `.../Api.kt` | 一个 GET 请求的发报封装（等价于 `curl`） |
| `.../MainActivity.kt` | 打开无障碍设置 + 发测试上报的入口页 |
| `app/src/main/AndroidManifest.xml` | 权限声明；`usesCleartextTraffic="true"` 是因为服务器现在是明文 HTTP |
| `app/src/main/res/xml/accessibility_service.xml` | 无障碍服务配置：只订阅 `typeWindowStateChanged`，`canRetrieveWindowContent="false"`（不读屏幕内容） |

## 编译步骤（在你自己的电脑上，本机没有 Android SDK）

1. 安装 Android Studio（自带 JDK 与 SDK）。
2. `File → Open` 选择 `android/` 目录。首次同步若提示缺 Gradle Wrapper，点 “Use local Gradle distribution” 或让 Studio 自动生成。
3. 打开 `Cfg.kt`，确认/修改 `BASE` 与 `TOKEN`。
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`。
5. 传到手机安装（或 `adb install -r app-debug.apk`）。

## 手机上启用

1. 设置 → 无障碍 → 已下载的应用 → **AppCast Monitor** → 开启。
2. 打开 App 点「发一条测试上报」，看板应立刻出现「手动测试」。
3. 保活：设置 → 应用 → AppCast Monitor → 电池 → **无限制**，并允许**自启动**。
   MIUI / ColorOS 还需要在「省电策略」里选无限制，否则息屏后进程被冻结、看板显示离线。

## 之后要上 HTTPS 的话

把 `Cfg.BASE` 改成 `https://...`，并删掉清单里的 `android:usesCleartextTraffic="true"`。
