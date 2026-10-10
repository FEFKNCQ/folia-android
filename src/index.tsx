import { Buffer } from 'buffer';
import { installGlobalVisualizerFrameRateLimiter } from './utils/frameRateLimiter';
import { installCapacitorBodianBridge } from './platform/capacitorBodianBridge';
import { installAndroidMusicBridge } from './platform/androidMusicBridge';
import { installViewRouteLogger } from './platform/viewRouteLogger';
import { installConsoleLogCapture } from './utils/consoleLogBuffer';
import { installDebugModule } from './services/debug/debugModule';
import { installMemorySampleFeed } from './services/debug/memorySamples';
// import { installCoverSizeAudit } from './services/debug/coverSizeSamples';
// @ts-ignore
globalThis.Buffer = Buffer;
// First, so the debug overlay's console tab has the startup lines too - they are where a failure
// to reach a library or restore a session shows up.
installConsoleLogCapture();
// Right after it, so the startup lines reach the runtime log file too and not only the in-memory
// buffer. Both no-op off Electron. See services/debug/debugModule.ts.
installDebugModule();
installMemorySampleFeed();
// Cover size audit, left wired but switched off: it answered whether the provider CDNs honour the
// size in a cover URL - they do - and that is not a question worth re-asking every session. The
// collector and its panel are still in the tree, and `?probe=coverSizeAudit` still reaches them.
// To bring the tab back, uncomment this line and the four sites in DevDebugOverlay.tsx. It was dev
// only even then: a packaged build has nothing to do with the answer, so it should not pay the
// observer or the rows it retains.
// if (import.meta.env.DEV) installCoverSizeAudit();
installGlobalVisualizerFrameRateLimiter();

// ---- 安卓（Capacitor）专属桥接：必须赶在 bootstrap 之前装好 ----
// 波点音源：0.7.16 的 bodianTransport 只认 window.electron.bodianRequest，
//           安卓上用独立全局 __foliaBodianRequest 提供同签名的实现。
installCapacitorBodianBridge();
// 本地音乐导入：安卓 WebView 没有 File System Access API，
//           这里用 SAF 桥把它模拟出来，否则"导入文件夹"点了没反应。
installAndroidMusicBridge();

// 把当前视图打出来，方便远程定位"用户停在哪个界面"。
installViewRouteLogger();

void import('./bootstrap');
