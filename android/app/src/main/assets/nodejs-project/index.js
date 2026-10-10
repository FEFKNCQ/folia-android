#!/usr/bin/env node
/**
 * Folia 安卓内置后端的入口脚本（在手机上的 nodejs-mobile 里运行）。
 *
 * 四个音源各起一个本地 HTTP 服务，App 通过 http://127.0.0.1:<port> 访问：
 *   3000  网易云
 *   3001  酷狗
 *   3002  QQ 音乐
 *   3100  波点（把 bodian-music-api 这个 host-neutral 客户端包成 HTTP 服务）
 *
 * 注意：这几个包的端口都是从 process.env.PORT 读的（在两个包的 server 里），
 * 所以必须在 require 之前逐个设置，否则会全部挤到同一个端口上。
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------- 日志重定向
// Android 上的 App 没有控制台设备，Node 的 console.log 不会进 logcat —— 也就是说
// 不落盘的话后端出什么错我们完全看不到。这里把所有输出写到项目目录下的文件里，
// 之后用 `adb shell cat` 就能读到。
const LOG_FILE = path.join(__dirname, 'node-backend.log');

// Android 上根本没有 /tmp，Node 的 os.tmpdir() 会退化成 "/tmp"，
// 于是任何往临时目录写文件的库都会 ENOENT（网易云的 server 就要在 tmpdir 里
// 读写 anonymous_token，不修这个它直接起不来）。必须在 require 之前指到可写目录。
const TMP_DIR = path.join(__dirname, 'tmp');
try {
  fs.mkdirSync(TMP_DIR, { recursive: true });
} catch {
  /* ignore */
}
process.env.FOLIA_TMP_DIR = TMP_DIR;
process.env.TMPDIR = TMP_DIR;
process.env.TMP = TMP_DIR;
process.env.TEMP = TMP_DIR;

// netease 的 util/request.js 在**模块加载时**就会 readFileSync 这个 anonymous_token，
// 文件不存在直接抛 ENOENT。官方 app.js 启动时本来会先建一个空文件，我们是直接 require
// server 的，所以必须自己补这一步。App 侧稍后会调 /register/anonimous 去补真实 token。
try {
  const anonymousTokenFile = path.join(TMP_DIR, 'anonymous_token');
  if (!fs.existsSync(anonymousTokenFile)) {
    fs.writeFileSync(anonymousTokenFile, '', 'utf-8');
  }
} catch (error) {
  console.log('[folia-node] 创建 anonymous_token 失败:', error && error.message);
}

const toFile = (level, args) => {
  const line = `[${new Date().toISOString()}] [${level}] ` +
    args.map((a) => {
      if (a instanceof Error) return `${a.message}\n${a.stack}`;
      if (typeof a === 'object') {
        try {
          return JSON.stringify(a);
        } catch {
          return String(a);
        }
      }
      return String(a);
    }).join(' ') + '\n';
  try {
    fs.appendFileSync(LOG_FILE, line);
  } catch {
    /* 写不进去也不能让业务挂掉 */
  }
};

try {
  fs.writeFileSync(LOG_FILE, `folia backend log, node ${process.version}\n`);
} catch {
  /* ignore */
}

const rawLog = console.log.bind(console);
const rawError = console.error.bind(console);

console.log = (...args) => {
  toFile('log', args);
  rawLog(...args);
};
console.error = (...args) => {
  toFile('error', args);
  rawError(...args);
};

process.on('uncaughtException', (error) => {
  console.error('uncaughtException', error);
});
process.on('unhandledRejection', (reason) => {
  console.error('unhandledRejection', reason);
});

const log = (...args) => console.log('[folia-node]', ...args);

// ------------------------------------------------------- Web Crypto 兜底
// @yakult-green-tea/qq-music-api 构造 QIMEI 引导请求时**完全依赖 `globalThis.crypto`**
// （Web Crypto API）来做 MD5 / RSAES-PKCS1-v1_5 / AES-CBC —— 它源码里明确要求
// "Node >= 20"（Node 19 起才把 crypto 挂到 globalThis 上）。
//
// 而 nodejs-mobile 提供的是 **Node 18.20.4**，`globalThis.crypto` 是 undefined，
// 于是 QQ 在 device-bootstrap 这步**瞬间抛异常**（实测 durationMs 只有 3ms，
// 根本没发网络请求），表现为二维码永远出不来 + "频繁，30 秒后再试"的退避。
//
// Node 15+ 都带 require('crypto').webcrypto，直接挂上去即可。
if (!globalThis.crypto) {
  try {
    const { webcrypto } = require('node:crypto');
    if (webcrypto) {
      globalThis.crypto = webcrypto;
      log('已补 globalThis.crypto（Node', process.version, '默认没有）');
    }
  } catch (error) {
    log('补 globalThis.crypto 失败:', error && error.message);
  }
}

// ---------------------------------------------------------------- DNS 兜底
// Android 上没有 /etc/resolv.conf，Node 的 dns.lookup（走 libuv -> bionic getaddrinfo）
// 直接返回 getaddrinfo ENOTFOUND，导致网易云/酷狗/QQ 三个后端全都连不上外网
// （波点桥接不访问外网所以看起来正常）。
// 这里把 dns.lookup 换成基于 c-ares 的实现：c-ares 自己用 UDP 发 DNS 查询，
// 不依赖系统解析器，因此可以绕过这个问题。
const dns = require('node:dns');

const DNS_SERVERS = ['223.5.5.5', '119.29.29.29', '180.76.76.76'];
try {
  dns.setServers(DNS_SERVERS);
  log('DNS 服务器已设为:', DNS_SERVERS.join(', '));
} catch (error) {
  log('dns.setServers 失败:', error && error.message);
}

const originalLookup = dns.lookup.bind(dns);
const isIpLiteral = (host) =>
  /^\d+\.\d+\.\d+\.\d+$/.test(host) || host === 'localhost' ||
  host === '127.0.0.1' || host === '::1';

dns.lookup = (hostname, options, callback) => {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  const opts = options || {};

  if (isIpLiteral(hostname)) {
    return originalLookup(hostname, opts, callback);
  }

  // 【顺序很重要】先走系统解析器：联网正常时它最快也最准。
  // 反过来先走 c-ares 会踩坑 —— 不少网络会丢弃发往外部 DNS 的 UDP 包，
  // c-ares 得等超时才失败，等于给每一次请求都加上几秒延迟（实测表现为"加载慢死"）。
  // c-ares 只作为系统解析器失败时的兜底。
  originalLookup(hostname, opts, (systemError, address, family) => {
    if (!systemError && address) {
      return callback(null, address, family);
    }

    dns.resolve4(hostname, (aresError, addresses) => {
      if (aresError || !addresses || addresses.length === 0) {
        log('DNS 解析失败:', hostname,
          (systemError && systemError.message) || '',
          (aresError && aresError.message) || '');
        return callback(systemError || aresError);
      }
      if (opts.all) {
        return callback(null, addresses.map((item) => ({ address: item, family: 4 })));
      }
      return callback(null, addresses[0], 4);
    });
  });
};

// ---------------------------------------------------------------- 网易云 3000
function startNetease() {
  try {
    const pkgDir = path.dirname(require.resolve('@neteasecloudmusicapienhanced/api/package.json'));
    const { serveNcmApi } = require(path.join(pkgDir, 'server.js'));

    // 先把服务起起来，保证 /login/qr/key 立刻可用。
    serveNcmApi({ port: 3000, checkVersion: false });
    log('网易云 -> 3000');

    // 匿名 token（MUSIC_A）的注册要访问外网，可能很慢甚至超时。
    // **必须放在服务启动之后、后台跑**：之前把它 await 在 serveNcmApi 前面，
    // 结果外网一慢就把整个网易云后端卡住，表现就是"二维码加载不出来"。
    try {
      const generateConfig = require(path.join(pkgDir, 'generateConfig.js'));
      generateConfig()
        .then(() => log('网易云匿名 token 注册完成'))
        .catch((error) => log('网易云匿名 token 注册失败:', error && error.message));
    } catch (error) {
      log('网易云匿名 token 模块加载失败:', error && error.message);
    }
  } catch (error) {
    log('网易云启动失败:', error && error.message);
  }
}

// ------------------------------------------------------------------ 酷狗 3001
function startKugou() {
  try {
    process.env.PORT = '3001';
    // 与另外两个包不同：kugoumusicapi 的入口只是导出函数，**不会自动 listen**，
    // 必须显式调用 startService()，否则端口上什么都没有。
    const kugou = require('kugoumusicapi');
    if (typeof kugou.startService === 'function') {
      kugou.startService({});
      log('酷狗 -> 3001');
    } else {
      log('酷狗启动失败: 没有导出 startService');
    }
  } catch (error) {
    log('酷狗启动失败:', error && error.message);
  }
}

// --------------------------------------------------------------- QQ 音乐 3002
function startQq() {
  try {
    // QQ 登录态默认存在**内存**里（createMemoryAuthSessionRepository），
    // 所以 App 一重启登录就失效。这个包支持通过环境变量把会话落到文件：
    //   QQ_AUTH_SESSION_PATH  —— 会话文件路径
    //   QQ_SESSION_SECRET     —— 加密密钥（必须跨重启稳定，否则解不开）
    // 见 dist/src/services/auth/fileAuthSessionRepository.js。
    const crypto = require('node:crypto');
    const secretFile = path.join(__dirname, 'qq-session-secret.txt');
    let qqSecret = '';
    try {
      qqSecret = fs.readFileSync(secretFile, 'utf-8').trim();
    } catch {
      /* 首次运行 */
    }
    if (!qqSecret) {
      qqSecret = crypto.randomBytes(32).toString('hex');
      try {
        fs.writeFileSync(secretFile, qqSecret, 'utf-8');
      } catch (error) {
        log('QQ 会话密钥落盘失败（本次仍可用）:', error && error.message);
      }
    }
    process.env.QQ_AUTH_SESSION_PATH = path.join(__dirname, 'qq-auth-session.json');
    // 设备上下文（含 QIMEI）默认也是内存态（createMemoryDeviceContextRepository）。
    // 落盘后重启就不必重新做一次 device-bootstrap 引导，登录也更稳。
    process.env.QQ_AUTH_STATE_PATH = path.join(__dirname, 'qq-device-state.json');
    process.env.QQ_SESSION_SECRET = qqSecret;

    // 这个包在根部就会 app.listen()，端口取自 process.env.PORT，必须先设好。
    process.env.PORT = '3002';
    require('@yakult-green-tea/qq-music-api');

    // ★ 关键：光设环境变量不够！QQ 包的控制器用的是**单例服务**（qrLoginService），
    //   它默认拿的是内存会话仓。包源码里有一段注释明确写着这是给 Folia 的嵌入钩子：
    //   "Runtime hook for embedding hosts. Folia calls this immediately after loading the
    //    npm package, before the event loop can accept a request..."
    //   所以必须在 require 之后、任何请求进来之前，把文件仓注入到这个单例上。
    //   （这就是之前日志里出现两个 auth-session.ready——一个 memory 一个 file——的原因：
    //     文件仓的服务建了，但控制器用的单例还是内存的。）
    try {
      const qrNode = require('@yakult-green-tea/qq-music-api/dist/src/services/auth/qrLogin.node.js');
      const fileAuth = require('@yakult-green-tea/qq-music-api/dist/src/services/auth/fileAuthSessionRepository.js');
      qrNode.configureAuthSessionRepository(fileAuth.createConfiguredAuthSessionRepository());
      log('QQ 会话仓库已注入文件态（单例）');
    } catch (error) {
      log('QQ 会话仓库注入失败:', error && error.message);
    }

    log('QQ 音乐 -> 3002（会话已启用落盘）');
  } catch (error) {
    log('QQ 音乐启动失败:', error && error.message);
  }
}

// ------------------------------------------------------------------ 波点 3100
// ------------------------------------------------------- 本地音乐文件服务 3101
// Java 侧（LocalMusicBridge）把用户选的目录里的音频拷到 filesDir/local-music，
// 但 WebView 无法用 fetch 读设备文件系统；这里用 HTTP 把它提供出来，
// 前端再由 androidMusicBridge.ts 包装成 FileSystemFileHandle。
function startLocalMusicServer() {
  const os = require('node:os');
  const musicDir = path.join(__dirname, '..', 'local-music');
  try {
    fs.mkdirSync(musicDir, { recursive: true });
  } catch {
    /* ignore */
  }

  const MIME = {
    '.mp3': 'audio/mpeg', '.flac': 'audio/flac', '.m4a': 'audio/mp4',
    '.aac': 'audio/aac', '.wav': 'audio/wav', '.ogg': 'audio/ogg',
    '.opus': 'audio/ogg', '.wma': 'audio/x-ms-wma',
  };

  http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    const name = decodeURIComponent((req.url || '/').replace(/^\/local\//, '').split('?')[0]);
    // 只允许访问这个目录下的单层文件名，防目录穿越
    if (!name || name.includes('/') || name.includes('\\') || name.includes('..')) {
      res.writeHead(400);
      res.end('bad name');
      return;
    }
    const file = path.join(musicDir, name);
    fs.stat(file, (err, stat) => {
      if (err || !stat.isFile()) {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      const ext = path.extname(name).toLowerCase();
      const type = MIME[ext] || 'application/octet-stream';

      // 支持 Range，播放器拖动进度条要靠它
      const range = req.headers.range;
      if (range) {
        const m = /bytes=(\d*)-(\d*)/.exec(range) || [];
        const start = m[1] ? parseInt(m[1], 10) : 0;
        const end = m[2] ? parseInt(m[2], 10) : stat.size - 1;
        res.writeHead(206, {
          'Content-Type': type,
          'Content-Range': `bytes ${start}-${end}/${stat.size}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': end - start + 1,
        });
        fs.createReadStream(file, { start, end }).pipe(res);
        return;
      }

      res.writeHead(200, { 'Content-Type': type, 'Content-Length': stat.size, 'Accept-Ranges': 'bytes' });
      fs.createReadStream(file).pipe(res);
    });
  }).listen(3101, () => log('本地音乐 -> 3101'));
}

function startBodian() {
  const { createBodianApi } = require('bodian-music-api');

  // deviceId 必须是稳定的 32 位小写十六进制；这里从文件里读，保证重启后设备身份不变。
  const fs = require('node:fs');
  const crypto = require('node:crypto');
  const deviceIdFile = path.join(__dirname, 'bodian-device-id.txt');

  let deviceId = '';
  try {
    deviceId = fs.readFileSync(deviceIdFile, 'utf-8').trim();
  } catch {
    deviceId = '';
  }
  if (!/^[0-9a-f]{32}$/.test(deviceId)) {
    deviceId = crypto.randomBytes(16).toString('hex');
    try {
      fs.writeFileSync(deviceIdFile, deviceId, 'utf-8');
    } catch {
      /* 写不了也不影响本次运行 */
    }
  }

  // 会话必须落盘：原来只放在内存里（`let session = null`），Node 进程一随 App 重启就没，
  // 表现就是"波点登录之后重启软件就失效"。
  const sessionFile = path.join(__dirname, 'bodian-session.json');

  let revision = 0;
  let session = null;
  try {
    const raw = fs.readFileSync(sessionFile, 'utf-8');
    session = JSON.parse(raw);
    if (session) revision += 1;
    log('波点会话已从磁盘恢复');
  } catch {
    /* 首次运行没有文件 */
  }

  const persistSession = (value) => {
    try {
      if (value) {
        fs.writeFileSync(sessionFile, JSON.stringify(value), 'utf-8');
      } else {
        fs.unlinkSync(sessionFile);
      }
    } catch (error) {
      log('波点会话落盘失败:', error && error.message);
    }
  };

  const sessions = {
    get revision() {
      return revision;
    },
    get() {
      return session;
    },
    set(next) {
      session = next;
      revision += 1;
      persistSession(next);
    },
    clear() {
      session = null;
      revision += 1;
      persistSession(null);
    },
  };

  const api = createBodianApi({ deviceId, sessions });

  http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, service: 'folia-bodian' }));
      return;
    }
    if (req.method !== 'POST' || !req.url.startsWith('/request')) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: { code: 'unsupported', message: 'not found' } }));
      return;
    }

    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', async () => {
      try {
        const { operation, params } = JSON.parse(Buffer.concat(chunks).toString('utf-8') || '{}');
        const result = await api.request(operation, params || {});
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      } catch (error) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          ok: false,
          error: { code: 'unavailable', message: String((error && error.message) || error) },
        }));
      }
    });
  }).listen(3100, () => log('波点 -> 3100'));
}

log('Node', process.version, '启动中');

startNetease();
startKugou();
startQq();
startBodian();
startLocalMusicServer();

log('全部启动指令已下发');
