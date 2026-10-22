import { createServer } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, renameSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB_DIR = join(ROOT, 'web');
const DATA_DIR = join(ROOT, 'data');
const STATE_PATH = join(DATA_DIR, 'state.json');
const MAINT_FLAG = join(DATA_DIR, 'maintenance');

const cfg = JSON.parse(readFileSync(join(HERE, 'config.json'), 'utf8'));
if (process.env.APPCAST_HOST) cfg.host = process.env.APPCAST_HOST;
if (process.env.APPCAST_PORT) cfg.port = Number(process.env.APPCAST_PORT);
const OFFLINE_MS = (cfg.offlineAfterSec || 90) * 1000;
const HISTORY = cfg.historySize || 150;

// 手机只发包名时，用这张表显示中文名；命中不到就退化为包名末段。
const APP_LABELS = {
  'com.tencent.mm': '微信',
  'com.tencent.mobileqq': 'QQ',
  'com.tencent.qq': 'QQ',
  'com.tencent.tim': 'TIM',
  'com.tencent.qqlite': 'QQ Lite',
  'com.taobao.taobao': '淘宝',
  'com.tmall.wireless': '天猫',
  'com.jingdong.app.mall': '京东',
  'com.xunmeng.pinduoduo': '拼多多',
  'com.alibaba.android.rimet': '钉钉',
  'com.eg.android.AlipayGphone': '支付宝',
  'com.sankuai.meituan': '美团',
  'com.dianping.v1': '大众点评',
  'me.ele': '饿了么',
  'com.ss.android.ugc.aweme': '抖音',
  'com.ss.android.ugc.aweme.lite': '抖音极速版',
  'com.smile.gifmaker': '快手',
  'com.kuaishou.nebula': '快手极速版',
  'com.xiaohongshu.android': '小红书',
  'com.tencent.qqlive': '腾讯视频',
  'com.youku.phone': '优酷',
  'com.android.video': '爱奇艺',
  'com.qiyi.video': '爱奇艺',
  'com.bilibili.app.in': 'bilibili',
  'tv.danmaku.bili': 'bilibili',
  'com.dragon.read': '番茄免费小说',
  'com.qidian.QDReader': '起点读书',
  'com.zhihu.android': '知乎',
  'com.sina.weibo': '微博',
  'com.ss.android.article.news': '今日头条',
  'com.netease.newsreader.activity': '网易新闻',
  'com.tencent.news': '腾讯新闻',
  'com.baidu.BaiduMap': '百度地图',
  'com.autonavi.minimap': '高德地图',
  'com.sankuai.map': '美团外卖商家版',
  'com.didi.app.es': '滴滴出行',
  'com.sdu.didi.psnger': '滴滴出行',
  'com.baidu.searchbox': '百度',
  'com.quark.browser': '夸克',
  'com.UCMobile': 'UC浏览器',
  'com.tencent.mtt': 'QQ浏览器',
  'com.android.bankabc': '中国农业银行',
  'com.icbc': '中国工商银行',
  'com.cmbccicommonbank.ccbc': '招商银行',
  'com.hexin.plat.android': '同花顺',
  'com.xueqiu.android': '雪球',
  'com.netease.cloudmusic': '网易云音乐',
  'com.tencent.qqmusic': 'QQ音乐',
  'cmcc.dds': '和彩云',
  'com.xiaomi.market': '小米应用商店',
  'com.miui.home': '桌面',
  'com.android.settings': '设置',
  'com.android.camera': '相机',
  'com.miui.gallery': '相册',
  'com.android.gallery3d': '相册',
  'com.android.dialer': '电话',
  'com.miui.miservice': '小米服务',
  'com.lucky.luckyclient': '瑞幸咖啡',
  'com.starbucks.cn': '星巴克',
  'com.ctrip.wql.cqfw': '携程',
  'ctrip.android.view': '携程',
  'com.quwan.basichu': '基本计算器',
};

const state = { devices: {} };
for (const d of cfg.devices) {
  state.devices[d.id] = { id: d.id, name: d.name, pkg: null, app: null, screen: null, lastSeen: 0, events: [] };
}

try {
  const saved = JSON.parse(readFileSync(STATE_PATH, 'utf8'));
  for (const [id, dev] of Object.entries(saved.devices || {})) {
    if (state.devices[id]) state.devices[id] = { ...state.devices[id], ...dev };
  }
} catch {}

let flushTimer = null;
function persist() {
  clearTimeout(flushTimer);
  flushTimer = setTimeout(() => {
    try {
      mkdirSync(DATA_DIR, { recursive: true });
      const tmp = STATE_PATH + '.tmp';
      writeFileSync(tmp, JSON.stringify(state));
      renameSync(tmp, STATE_PATH);
    } catch (e) {
      console.error('persist failed', e.message);
    }
  }, 800);
}

function byToken(token) {
  const d = cfg.devices.find((x) => x.token === token);
  return d ? state.devices[d.id] : null;
}

// 包名大小写不敏感查表（安卓包名可能带大写字母，如 com.eg.android.AlipayGphone）
const BUILT_IN = new Map(Object.entries(APP_LABELS).map(([k, v]) => [k.toLowerCase(), v]));
const USER_LABELS = new Map(Object.entries(cfg.labels || {}).map(([k, v]) => [k.toLowerCase(), v]));

function resolveName(pkg, name) {
  if (pkg) {
    const hit = USER_LABELS.get(pkg.toLowerCase()) || BUILT_IN.get(pkg.toLowerCase());
    if (hit) return hit;
  }
  if (name) return name;
  if (!pkg) return null;
  return pkg.split('.').filter(Boolean).pop() || pkg;
}

function snapshot() {
  const now = Date.now();
  return {
    now,
    offlineAfterSec: cfg.offlineAfterSec || 90,
    devices: Object.values(state.devices).map((d) => ({
      ...d,
      online: now - d.lastSeen < OFFLINE_MS && d.lastSeen > 0,
      ageMs: d.lastSeen ? now - d.lastSeen : null,
    })),
  };
}

/* ------------------------------- SSE ------------------------------- */
const clients = new Set();
function broadcast(payload) {
  const body = `event: state\ndata: ${JSON.stringify(payload)}\n\n`;
  for (const res of clients) {
    res.write(body);
  }
}
setInterval(() => {
  for (const res of clients) res.write(': ping\n\n');
}, 25000);

/* ------------------------------ report ------------------------------ */
function applyReport(dev, q) {
  const now = Date.now();
  const pkg = (q.pkg || q.package || '').trim() || null;
  const rawName = (q.name || q.label || q.appname || '').trim();
  const app = resolveName(pkg, rawName);
  const screen = q.screen === 'off' || q.screen === 'lock' ? q.screen : q.screen === 'on' ? 'on' : pkg ? 'on' : null;

  dev.lastSeen = now;

  // 只有心跳（无 pkg、无屏幕状态）：不产生事件
  if (!pkg && !screen) return { ok: true, heartbeat: true, device: dev.id };

  const changed = pkg
    ? pkg.toLowerCase() !== String(dev.pkg || '').toLowerCase()
    : screen !== dev.screen;
  if (pkg) {
    dev.pkg = pkg;
    dev.app = app;
  }
  if (screen) dev.screen = screen;

  if (changed) {
    dev.events.push({ ts: now, pkg: dev.pkg, app: dev.app, screen: dev.screen });
    if (dev.events.length > HISTORY) dev.events.splice(0, dev.events.length - HISTORY);
    persist();
  }
  broadcast(snapshot());
  return { ok: true, device: dev.id, app: dev.app, changed };
}

/* ------------------------------- http ------------------------------- */
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 8192) {
        req.destroy();
        return resolve('');
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', () => resolve(''));
  });
}

function parseBody(text, contentType = '') {
  const out = {};
  if (!text) return out;
  if (contentType.includes('json') || text.trim().startsWith('{')) {
    try {
      Object.assign(out, JSON.parse(text));
      return out;
    } catch {}
  }
  try {
    for (const [k, v] of new URLSearchParams(text)) out[k] = v;
  } catch {}
  return out;
}

const indexHtml = () => readFileSync(join(WEB_DIR, 'index.html'));

// data/maintenance 存在 = 维护模式。touch 即停，rm 即恢复，无需重启。
function maintenance(res, p) {
  if (p === '/' || p === '/index.html') {
    let html;
    try {
      html = readFileSync(join(WEB_DIR, 'maintenance.html'), 'utf8');
    } catch {
      html = '<meta charset="utf-8"><h1 style="font-family:sans-serif">更新功能中</h1>';
    }
    res.writeHead(503, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    return res.end(html);
  }
  if (p === '/healthz') return json(res, 503, { ok: false, maintenance: true });
  return json(res, 503, { ok: false, error: 'maintenance' });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;

  try {
    if (existsSync(MAINT_FLAG)) return maintenance(res, p);

    if (p === '/' || p === '/index.html') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      return res.end(indexHtml());
    }
    if (p === '/healthz') return json(res, 200, { ok: true, ts: Date.now() });

    if (p === '/api/report') {
      const q = Object.create(null);
      for (const [k, v] of url.searchParams) q[k] = v;
      if (req.method !== 'GET') Object.assign(q, parseBody(await readBody(req), req.headers['content-type'] || ''));

      const token = String(q.t || q.token || req.headers['x-token'] || '').trim();
      const dev = byToken(token);
      if (!dev) return json(res, 403, { ok: false, error: 'bad token' });

      const result = applyReport(dev, q);
      return json(res, 200, result);
    }

    // 读取类接口需要 viewKey（页面 URL 上的 ?k=）
    if (p === '/api/state' || p === '/api/stream') {
      if (url.searchParams.get('k') !== cfg.viewKey) return json(res, 403, { ok: false, error: 'bad view key' });

      if (p === '/api/state') return json(res, 200, snapshot());

      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      });
      res.write('event: state\n');
      res.write(`data: ${JSON.stringify(snapshot())}\n\n`);
      clients.add(res);
      req.on('close', () => clients.delete(res));
      return;
    }

    json(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    console.error(e);
    if (!res.headersSent) json(res, 500, { ok: false, error: 'server error' });
    else res.end();
  }
});

server.listen(cfg.port, cfg.host, () => {
  console.log(`appcast listening on http://${cfg.host}:${cfg.port}  (devices: ${cfg.devices.length})`);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    clearTimeout(flushTimer);
    try {
      mkdirSync(DATA_DIR, { recursive: true });
      writeFileSync(STATE_PATH, JSON.stringify(state));
    } catch {}
    process.exit(0);
  });
}
