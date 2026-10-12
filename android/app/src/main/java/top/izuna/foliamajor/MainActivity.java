package top.izuna.foliamajor;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

/**
 * Folia Android 宿主 Activity。
 *
 * 在原生 Capacitor BridgeActivity 之上做了四件事：
 * 1. 横竖屏自适应：Manifest 用 fullSensor，代码里再兜一层，跟随重力感应切换。
 * 2. 沉浸式全屏：隐藏状态栏与导航栏，下拉才临时唤出；并且在 onCreate / onResume /
 *    onWindowFocusChanged 以及若干延时点重复收起——Capacitor 的 StatusBar 插件和启动图收起
 *    都会在 Activity 起来之后把系统栏重新显示出来，只收一次是不够的。
 * 3. WebView 视口收敛：关闭捏合缩放、开启整页适配，解决“画面被放大、比例不对、铺不满屏”。
 *    横屏额外做整页缩放（见 LAYOUT_ZOOM_LANDSCAPE）。
 * 4. NDK 原生模块：加载 libfolia_native.so 并输出本机 ABI / CPU 能力。
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "FoliaNative";

    /** 本地音乐导入桥：SAF 选目录 → 拷贝到 filesDir/local-music。 */
    private LocalMusicBridge localMusicBridge;

    /**
     * 横屏整页缩放。1.0 = 原始大小，0.75 = 整个界面缩小到 75%。
     *
     * 实现方式：把页面布局宽度按 1/zoom 放大，再让 WebView 等比缩回屏幕宽度。
     * 手机横屏的 CSS 视口只有大约 754x347（2640px / 3.5 密度），桌面版布局在这个尺寸下
     * 偏大，所以横屏默认缩到 75%。
     */
    private static final float LAYOUT_ZOOM_LANDSCAPE = 0.75f;

    /** 竖屏不缩放：手机竖屏的 CSS 视口本来就很窄，界面本身就是按窄屏排的。 */
    private static final float LAYOUT_ZOOM_PORTRAIT = 1.0f;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable immersiveTask = this::applyImmersiveFullscreen;

    static {
        try {
            System.loadLibrary("folia_native");
        } catch (Throwable t) {
            // 原生库缺失不应拖垮 WebView 主体，降级为仅记录日志。
            Log.w(TAG, "libfolia_native 加载失败，原生能力不可用: " + t.getMessage());
        }
    }

    /** 由 src/main/cpp/folia_native.cpp 实现，返回本机 ABI / 核心数 / NEON 支持摘要。 */
    private static native String nativeRuntimeInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 横竖屏都支持：跟随重力感应自动切换（竖屏 + 左右两个横屏方向）。
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        configureWebView();
        applyImmersiveFullscreen();
        scheduleImmersiveReapply();
        logNativeRuntimeInfo();
        // 本地音乐导入桥必须在 WebView 加载页面之前注册。
        // 注意：WebView 不是字段，要从 Capacitor 的 Bridge 上取（与 configureWebView 一致）。
        Bridge musicBridge = getBridge();
        WebView musicWebView = musicBridge == null ? null : musicBridge.getWebView();
        if (musicWebView != null) {
            localMusicBridge = new LocalMusicBridge(this, musicWebView);
            localMusicBridge.attach();
        } else {
            Log.w(TAG, "WebView 尚未就绪，本地音乐导入桥未注册");
        }

        // Android 13+：通知权限自动请求 —— 未授权时启动即弹系统授权框。
        // （不授权的话，媒体通知/常驻通知都会被系统静默丢弃。）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2002);
        }

        // 前台服务：常驻通知 + 保活 + 媒体会话，后台播放不中断。
        Bridge mediaBridge = getBridge();
        WebView mediaWebView = mediaBridge == null ? null : mediaBridge.getWebView();
        if (mediaWebView != null) {
            KeepAliveService.setSharedWebView(mediaWebView);
            KeepAliveService.attachWebViewNow(mediaWebView);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, KeepAliveService.class));
        } else {
            startService(new Intent(this, KeepAliveService.class));
        }

        startEmbeddedBackends();
    }

    /**
     * 解包内置 Node 项目并把它拉起来 —— 四个音源后端（网易云/酷狗/QQ/波点）
     * 就这样直接跑在手机本地，App 通过 http://127.0.0.1:&lt;port&gt; 访问，
     * 不需要数据线、adb reverse 或者任何外部服务器。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        // 选目录走的是 SAF，先交给本地音乐桥；不是它的请求再交回父类。
        if (localMusicBridge != null
                && localMusicBridge.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void startEmbeddedBackends() {
        Thread thread = new Thread(() -> {
            try {
                java.io.File projectDir = NodeProjectInstaller.ensureInstalled(getApplicationContext());
                NodeRunner.start(projectDir.getAbsolutePath(), "index.js");
            } catch (Throwable t) {
                Log.e(TAG, "内置后端启动失败", t);
            }
        }, "folia-node-install");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyImmersiveFullscreen();
        scheduleImmersiveReapply();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 从通知栏、多任务切回来之后系统栏会重新出现，这里重新收起。
        if (hasFocus) {
            applyImmersiveFullscreen();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Activity 声明了 configChanges，旋转不会重建；这里把全屏和缩放按新方向重新应用一遍。
        applyImmersiveFullscreen();
        scheduleImmersiveReapply();

        Bridge bridge = getBridge();
        WebView webView = bridge == null ? null : bridge.getWebView();
        if (webView != null) {
            applyLayoutZoom(webView);
        }
    }

    /** 在几个时间点重复收起系统栏，压过 Capacitor 插件与启动图带来的重新显示。 */
    private void scheduleImmersiveReapply() {
        uiHandler.removeCallbacks(immersiveTask);
        for (long delay : new long[]{300L, 1200L, 2500L}) {
            uiHandler.postDelayed(immersiveTask, delay);
        }
    }

    private void applyImmersiveFullscreen() {
        // 让内容真正铺满整屏（含状态栏与导航栏区域），而不是被系统栏挤出一圈边。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void configureWebView() {
        try {
            Bridge bridge = getBridge();
            WebView webView = bridge == null ? null : bridge.getWebView();
            if (webView == null) {
                Log.w(TAG, "WebView 尚未就绪，跳过视口配置");
                return;
            }

            WebSettings settings = webView.getSettings();

            // 关键：Capacitor 默认允许 WebView 被捏合放大，一旦缩放级别不是 1，页面就会
            // 看起来“比例变大、铺不满屏幕”。这里直接关掉缩放。
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);

            // 让 WebView 按整页来布局：内容宽于屏幕时整体缩小以适配，而不是只显示局部。
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setTextZoom(100);
            webView.setInitialScale(0);

            // 页面本体是 https://localhost，而自托管后端（网易云 API）是 http，
            // 默认会被 WebView 当成混合内容拦掉，这里放行。
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

            settings.setMediaPlaybackRequiresUserGesture(false);

            // 横竖屏各自的缩放由 injectLayoutZoom 内部按当前方向决定（见 LAYOUT_ZOOM_*）。
            applyLayoutZoom(webView);
        } catch (Throwable t) {
            Log.w(TAG, "配置 WebView 视口失败", t);
        }
    }

    /**
     * 按当前方向注入整页缩放（横屏缩小、竖屏保持原样），并延时重复几次。
     *
     * 虚拟宽度由 WebView 当前的实际像素宽度换算得出（而不是用启动瞬间的 DisplayMetrics，
     * 那时窗口可能还停在另一个方向），所以旋转后重算才准确。
     */
    private void applyLayoutZoom(final WebView webView) {
        for (long delay : new long[]{300L, 600L, 1200L, 2000L, 3200L}) {
            uiHandler.postDelayed(() -> injectLayoutZoom(webView), delay);
        }
    }

    private void injectLayoutZoom(final WebView webView) {
        try {
            int widthPx = webView.getWidth();
            int heightPx = webView.getHeight();
            if (widthPx <= 0 || heightPx <= 0) {
                return;
            }

            boolean landscape = widthPx > heightPx;
            float zoom = landscape ? LAYOUT_ZOOM_LANDSCAPE : LAYOUT_ZOOM_PORTRAIT;

            String viewportContent;
            if (zoom >= 0.999f) {
                // 竖屏：还原成标准视口，不做任何缩放。
                viewportContent = "width=device-width, initial-scale=1.0, viewport-fit=cover";
            } else {
                float density = getResources().getDisplayMetrics().density;
                int virtualWidth = Math.round(widthPx / density / zoom);
                viewportContent = "width=" + virtualWidth
                        + ", initial-scale=" + zoom
                        + ", viewport-fit=cover";
            }

            // 注意：**必须删掉旧的 meta 再插一个新的**。
            // Chromium 只在页面加载时读 <meta name=viewport>，事后修改已有标签的 content
            // 通常不会触发视口重算 —— 这正是"横竖屏切换后整体比例没更新"的原因。
            // 移除旧节点 + 插入新节点才会让它重新计算。
            String js = "(function(){"
                    + "var old=document.querySelectorAll('meta[name=viewport]');"
                    + "for(var i=0;i<old.length;i++){old[i].parentNode.removeChild(old[i]);}"
                    + "var m=document.createElement('meta');"
                    + "m.setAttribute('name','viewport');"
                    + "m.setAttribute('content','" + viewportContent + "');"
                    + "document.head.appendChild(m);"
                    // 强制一次样式/布局失效，确保缩放被重新应用。
                    + "void document.documentElement.offsetHeight;"
                    + "})();";
            webView.evaluateJavascript(js, null);
            Log.i(TAG, "布局缩放=" + zoom + " (" + (landscape ? "横屏" : "竖屏") + ") viewport=" + viewportContent);
        } catch (Throwable t) {
            Log.w(TAG, "注入布局缩放失败", t);
        }
    }

    private void logNativeRuntimeInfo() {
        try {
            Log.i(TAG, nativeRuntimeInfo());
        } catch (Throwable t) {
            Log.w(TAG, "读取原生运行时信息失败", t);
        }
    }
}
