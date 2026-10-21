# appcast —— 手机当前应用实时看板

手机把「现在在哪个应用上」发一台服务器，网页**无需刷新**实时显示应用名、已持续时长、切换记录和今日时长估算。

```
手机(MacroDroid / Tasker / 极简App)
      │  HTTP GET /api/report?t=<token>&pkg=com.tencent.mm
      ▼
本服务器  Node 单文件服务 :4173
      │  SSE /api/stream?k=<viewKey>   （断线自动退回轮询 /api/state）
      ▼
浏览器看板  http://39.96.193.209:4173/?k=bPhCada64Off
```

安卓端怎么发，看 **[docs/安卓端接入指南.md](docs/安卓端接入指南.md)**。

## 当前部署状态（这台机器）

- 已注册为 systemd 服务并设为开机自启：`systemctl status appcast` / `restart appcast` / `journalctl -u appcast -f`
- 监听 `0.0.0.0:4173`，本机 iptables 为 ACCEPT；4173/tcp 在阿里云安全组此前已放行（换端口就要重新加规则）
- 服务以 root 运行（`data/` 需要写权限）
- 看板：`http://39.96.193.209:4173/?k=bPhCada64Off`

## 目录

```
server/server.mjs     唯一的后端文件，零依赖，只用 Node 内置模块（约 260 行）
server/config.json    端口、Token、设备列表、离线阈值
web/index.html        看板页面，单文件，无框架、无外部 CDN
data/state.json       运行态（每台设备最近 150 条记录），自动写盘
docs/                 安卓端接入指南
android/              可选的极简安卓 App 源码（无障碍服务方案）
deploy/appcast.service systemd 单元样例
start.sh              启动脚本
```

## 本机已有环境的重要一条

这台机器的 `NODE_OPTIONS` 里带着 `--use-system-ca`，Node 20 不接受这个值，**直接 `node xxx.mjs` 会启动失败**。
`start.sh` 和 systemd 单元里都已经把 `NODE_OPTIONS` 置空，别绕过它们手工裸跑。

## 跑起来

```sh
sh /proj/Qoderprojs/appcast/start.sh          # 前台运行，配置见 server/config.json
```

常驻：

```sh
cp deploy/appcast.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now appcast
journalctl -u appcast -f
```

改完 `server/config.json` 需要 `systemctl restart appcast`。

## 对外访问

服务监听 `0.0.0.0:4173`，本机 iptables 是 ACCEPT，**4173/tcp 已在阿里云安全组放行**，手机可直接连。以后若换端口，需要重新加入方向规则：
ECS → 安全组 → 入方向 → 自定义 TCP `<端口>/<端口>`，源填你手机/家庭宽带的 IP（图省事就 `0.0.0.0/0`）。

已有域名并想走 80/443 的话，反代配置：

```nginx
server {
    listen 80;
    server_name your.domain;              # 建议同时配好 HTTPS
    location / {
        proxy_pass http://127.0.0.1:4173;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;              # SSE 必需，否则实时推送会被 nginx 攒住
        proxy_cache off;
        proxy_read_timeout 3600s;
    }
}
```

上 HTTPS 后：手机侧把 URL 换成 `https://…`；`Cfg.kt` 里同时删掉 `usesCleartextTraffic`。

## API

| 接口 | 说明 |
|---|---|
| `GET/POST /api/report` | 上报。`t`（Token，也可用 `X-Token` 头或 JSON body 的 `token`）、`pkg`、`name`、`screen=on/off/lock`、`ts` |
| `GET /api/state?k=` | 全量快照 JSON |
| `GET /api/stream?k=` | SSE，`event: state`，每 25 秒一次注释心跳 |
| `GET /healthz` | 存活探测 |
| `GET /` | 看板页面（密钥错会显示输入框） |

同一设备重复上报同一 `pkg` 只刷新在线时间、不产生新记录，所以手机端定时 45～60 秒心跳是安全的。
超过 `offlineAfterSec`（默认 90 秒）没有任何请求，页面显示「离线」。

## 配置

```json
{
  "host": "0.0.0.0",
  "port": 4173,
  "viewKey": "看板访问密钥",
  "offlineAfterSec": 90,
  "historySize": 150,
  "labels": { "com.some.app": "我的应用" },
  "devices": [{ "id": "phone", "name": "我的手机", "token": "上报Token" }]
}
```

- `labels`：包名→显示名的自定义覆盖表，优先级高于手机发来的 `name`，也高于内置 `APP_LABELS`。加应用只改这里，不用动代码。
- URL query 里出现**未编码的中文**会被 Node 的请求行解析直接挡成 400（`name=微信` ❌ / `name=%E5%BE%AE%E4%BF%A1` ✅）；POST 正文里放原始中文是安全的。所以手机侧推荐只发 `pkg`。

- 加第二台设备：往 `devices` 里加一项（`id` 必须唯一），重启，看板自动多一张卡片。
- 重新生成两个密钥：
  ```sh
  NODE_OPTIONS= node -e 'const c=require("crypto");console.log(c.randomBytes(12).toString("hex"),c.randomBytes(9).toString("base64url"))'
  ```
- 包名→中文名：内置表在 `server/server.mjs` 的 `APP_LABELS`，日常补充用 `config.json` 的 `labels` 就够了（优先级更高）。
- 临时换端口/网卡做测试：环境变量 `APPCAST_HOST`、`APPCAST_PORT` 会覆盖配置文件里的同名项。

## 隐私与数据

- 数据只落在这台机器的 `data/state.json`，不上传任何第三方，无埋点、无外部资源。
- 只记录**应用包名/名称 + 时间 + 息屏状态**，不读屏幕内容、不读通知正文。
- 看板链接带 `?k=` 即为口令，谁拿到谁能看到你何时在用什么应用，别外传。
- 明文 HTTP 下 Token 会出现在 URL 与访问日志里；认真用就配 HTTPS，并把 Token 改走 `X-Token` 头。

## 已知限制

- 今日时长是**基于切换事件的估算**：息屏后手机停止上报，那段时间不计入任何应用；单段最长计 4 小时。要精确请用 `screen=off` 触发器。
- 息屏瞬间若手机被系统冻结，最后一条记录可能缺失。
- 页面不做多用户/权限分级，就是一块自用的屏幕。
