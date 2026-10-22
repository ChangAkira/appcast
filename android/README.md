# android/ —— 可选的极简安卓 App 源码

这是接入指南里的**方案 C**。绝大多数人不需要它：用 MacroDroid（方案 A）5 分钟就能接上。
只有在这些情况下才用它：

- 不想装第三方自动化 App；
- 要求切换应用**零遗漏**（无障碍服务是系统级事件，比定时轮询可靠）；
- 想把它做得更省电 / 定制上报内容。

代码总量：4 个 Kotlin 文件 + 1 个清单 + 2 个资源文件，无第三方依赖。权限只有**无障碍服务** + **网络**，另外可选一个**使用情况访问权限**（`PACKAGE_USAGE_STATS`）用于事件丢失时自愈，不给也能跑。

## 文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/app/cast/monitor/Cfg.kt` | **改这里**：服务器地址和 Token（编译期常量，改完要重新编译） |
| `.../MonitorService.kt` | 无障碍服务：窗口切换 → 上报；每 10 秒用 `UsageStatsManager` 校准真实前台应用（自愈）；息屏/解锁上报 `screen`，失败重试 3 次 |
| `.../Api.kt` | 一个 GET 请求的发报封装（等价于 `curl`），返回 HTTP 是否 2xx |
| `.../MainActivity.kt` | 打开无障碍 / 使用情况访问设置 + 发测试上报的入口页，首页显示两个权限的状态 |
| `app/src/main/AndroidManifest.xml` | 权限声明；`usesCleartextTraffic="true"` 是因为服务器现在是明文 HTTP |
| `app/src/main/res/xml/accessibility_service.xml` | 无障碍服务配置：只订阅 `typeWindowStateChanged`，`canRetrieveWindowContent="false"`（不读屏幕内容），`notificationTimeout=20` |

## 直接用编译好的（不用装工具链）

本仓库 **Releases** 里有 release 签名的 APK（`AppCast-Monitor-v1.1.apk`，约 600 KB，minSdk 24 / targetSdk 34，非 debuggable）。v1.0 与 v1.1 同一把签名密钥，可直接覆盖安装。

## 自己编译

需要 JDK 17+、Android SDK（`platforms;android-34` + `build-tools;34.0.0`）、Gradle 8.7 以上。实测通过：Gradle 8.10 + AGP 8.5.2 + Kotlin 1.9.24。

1. Android Studio：`File → Open` 选 `android/` 目录，同步后 `Build → Build APK(s)`，产物在 `app/build/outputs/apk/debug/app-debug.apk`。首次同步若提示缺 Gradle Wrapper，点 “Use local Gradle distribution”。
2. 命令行 debug 包：`gradle assembleDebug`。
3. 命令行 release 包（必须带签名参数，否则产物未签名、装不上）：
   ```sh
   keytool -genkeypair -keystore your.keystore -alias appcast -keyalg RSA -keysize 2048 -validity 10000
   gradle assembleRelease -PRELEASE_STORE_FILE=/绝对路径/your.keystore \
       -PRELEASE_STORE_PASSWORD=<口令> -PRELEASE_KEY_ALIAS=appcast -PRELEASE_KEY_PASSWORD=<口令>
   ```
   四个 `-P` 参数不传时 `build.gradle` 里的 release 签名配置就不生效，行为和原来一致。**升级务必继续用同一把 keystore**，签名变了要先卸载再装。
4. `local.properties`（`sdk.dir=...`）是各人机器自己的路径，不要提交。

## 手机上启用

1. 设置 → 无障碍 → 已下载的应用 → **AppCast Monitor** → 开启。
2. 建议：设置 → 安全 → **使用情况访问权限** → AppCast Monitor → 允许。不给也能用，但事件被系统丢掉时无法自愈（看板会一直显示上一个应用，直到你下次切换）。
3. 打开 App 点「发一条测试上报」，看板应立刻出现「手动测试」；首页两行状态会告诉你两个权限各是什么情况。
4. 保活：设置 → 应用 → AppCast Monitor → 电池 → **无限制**，并允许**自启动**。
   MIUI / ColorOS 还需要在「省电策略」里选无限制，否则息屏后进程被冻结、看板显示离线。

## 之后要上 HTTPS 的话

把 `Cfg.BASE` 改成 `https://...`，并删掉清单里的 `android:usesCleartextTraffic="true"`。
