package top.izuna.foliamajor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * 安卓本地音乐导入桥。
 *
 * 背景：Folia 的本地曲库（src/services/localMusicService.ts）整套建立在
 * 浏览器的 File System Access API 之上 —— `window.showDirectoryPicker()` 返回一个
 * `FileSystemDirectoryHandle`，再用 `handle.getFile()` 拿 File 去解析元数据、播放。
 *
 * 但 **Android WebView 没有这套 API**，所以桌面/网页版能用的"导入文件夹"在手机上
 * 点了没反应（localLibraryAvailability 会判定为 file-system-api-unavailable）。
 *
 * 本类负责补上前半段：
 *   1. 用 SAF（ACTION_OPEN_DOCUMENT_TREE）让用户选一个目录
 *   2. 递归枚举其中的音频文件
 *   3. 拷贝到 App 私有目录 filesDir/local-music/
 *   4. 把清单通过 evaluateJavascript 回调给网页
 *
 * 为什么要"拷贝"而不是原地引用 `content://`：
 * WebView 无法用 fetch 读取 content:// URI，音频播放也就无从谈起。
 * 落到私有目录后就能由本地 Node 服务用 HTTP 提供（见 nodejs-project/index.js 的 3101），
 * 前端再由 JS 侧把 URL 包装回 FileSystemFileHandle 的形状。
 *
 * JS 侧的配合见 src/platform/androidMusicBridge.ts。
 */
public class LocalMusicBridge {

    private static final String TAG = "FoliaMusic";

    /** 供 JS 调用的全局对象名。 */
    public static final String JS_INTERFACE_NAME = "FoliaMusic";

    /** 拷进来的文件放这里；由本地 Node 服务以 HTTP 暴露。 */
    public static final String LOCAL_MUSIC_DIR = "local-music";

    /** SAF 选择目录的请求码。 */
    private static final int REQUEST_PICK_FOLDER = 2001;

    private static final String PREFS = "folia_local_music";
    private static final String KEY_TREE_URI = "tree_uri";

    /** 认作音频的扩展名。 */
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".wma", ".ape", ".alac", ".aiff", ".dsf"
    };

    private final Activity activity;
    private final WebView webView;

    public LocalMusicBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** 声明为 @JavascriptInterface 才会被注入到页面（Android 4.2+ 的安全要求）。 */
    private static class Api {
        private final LocalMusicBridge bridge;

        Api(LocalMusicBridge bridge) {
            this.bridge = bridge;
        }

        @JavascriptInterface
        public void pickFolder() {
            bridge.startPicking();
        }

        /** 上次选过的目录（重启后仍可复用，因为拿了持久化权限）。 */
        @JavascriptInterface
        public void rescanSavedFolder() {
            bridge.rescanSaved();
        }

        @JavascriptInterface
        public boolean hasSavedFolder() {
            return bridge.savedTreeUri() != null;
        }

        @JavascriptInterface
        public void clearSavedFolder() {
            bridge.clearSaved();
        }
    }

    /** 注册到 WebView。必须在 loadUrl 之前调用。 */
    public void attach() {
        webView.addJavascriptInterface(new Api(this), JS_INTERFACE_NAME);
        Log.i(TAG, "FoliaMusic 桥已注册到 WebView");
    }

    // ------------------------------------------------------------------ 选目录

    private void startPicking() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                activity.startActivityForResult(intent, REQUEST_PICK_FOLDER);
            } catch (Exception e) {
                Log.e(TAG, "打开目录选择器失败", e);
                postResult("error", "无法打开目录选择器：" + e.getMessage(), null);
            }
        });
    }

    /** 由 MainActivity.onActivityResult 转发进来。 */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FOLDER) return false;

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            postResult("cancelled", null, null);
            return true;
        }

        Uri treeUri = data.getData();
        // 拿持久化权限，否则 App 重启后这个目录就失效了。
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "获取持久化权限失败（本次仍可用）", e);
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_TREE_URI, treeUri.toString()).apply();

        importTree(treeUri);
        return true;
    }

    private void rescanSaved() {
        Uri saved = savedTreeUri();
        if (saved == null) {
            postResult("error", "还没有选过文件夹", null);
            return;
        }
        importTree(saved);
    }

    private Uri savedTreeUri() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String raw = prefs.getString(KEY_TREE_URI, null);
        return raw == null ? null : Uri.parse(raw);
    }

    private void clearSaved() {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().remove(KEY_TREE_URI).apply();
    }

    // ------------------------------------------------------------------ 导入

    /** 递归枚举 + 拷贝，必须离开主线程。 */
    private void importTree(final Uri treeUri) {
        Thread worker = new Thread(() -> {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(activity, treeUri);
                if (root == null) {
                    postResult("error", "无法读取所选目录", null);
                    return;
                }

                File outDir = new File(activity.getFilesDir(), LOCAL_MUSIC_DIR);
                if (!outDir.isDirectory() && !outDir.mkdirs()) {
                    postResult("error", "无法创建本地音乐目录", null);
                    return;
                }

                JSONArray files = new JSONArray();
                int copied = 0;
                int skipped = 0;

                Queue<DocumentFile> queue = new ArrayDeque<>();
                queue.add(root);

                while (!queue.isEmpty()) {
                    DocumentFile current = queue.poll();
                    if (current == null) continue;

                    if (current.isDirectory()) {
                        DocumentFile[] children = current.listFiles();
                        if (children != null) {
                            for (DocumentFile child : children) queue.add(child);
                        }
                        continue;
                    }

                    String name = current.getName();
                    if (name == null || !isAudio(name)) {
                        skipped++;
                        continue;
                    }

                    // 用「相对路径的哈希_文件名」避免不同子目录里的同名文件互相覆盖，
                    // 同时保留原始文件名便于阅读。
                    String relative = relativePath(root, current, name);
                    String storedName = shortHash(relative) + "_" + sanitize(name);

                    File target = new File(outDir, storedName);
                    if (!target.isFile() || target.length() != current.length()) {
                        if (!copy(current.getUri(), target)) {
                            skipped++;
                            continue;
                        }
                    }

                    JSONObject item = new JSONObject();
                    item.put("name", name);
                    item.put("relativePath", relative);
                    item.put("storedName", storedName);
                    item.put("size", target.length());
                    item.put("lastModified", current.lastModified());
                    files.put(item);
                    copied++;
                }

                JSONObject payload = new JSONObject();
                payload.put("files", files);
                payload.put("copied", copied);
                payload.put("skipped", skipped);
                payload.put("treeUri", treeUri.toString());

                postResult("ok", null, payload);
            } catch (Exception e) {
                Log.e(TAG, "导入目录失败", e);
                postResult("error", String.valueOf(e.getMessage()), null);
            }
        }, "folia-music-import");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean copy(Uri source, File target) {
        File temp = new File(target.getAbsolutePath() + ".part");
        try (InputStream in = activity.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) return false;
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (Exception e) {
            Log.w(TAG, "拷贝失败: " + source, e);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        // 先写临时文件再改名，避免中途失败留下半个音频文件被扫描到。
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        return temp.renameTo(target);
    }

    private static String relativePath(DocumentFile root, DocumentFile file, String name) {
        StringBuilder builder = new StringBuilder(name);
        DocumentFile parent = file.getParentFile();
        int guard = 0;
        while (parent != null && !parent.getUri().equals(root.getUri()) && guard++ < 32) {
            String parentName = parent.getName();
            if (parentName != null) builder.insert(0, parentName + "/");
            parent = parent.getParentFile();
        }
        return builder.toString();
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", bytes[i]));
            return sb.toString();
        } catch (Exception e) {
            return Long.toString(Math.abs((long) value.hashCode()), 16);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_");
    }

    // ------------------------------------------------------------------ 回调

    /**
     * 把结果回传给网页。页面侧注册 window.__foliaOnMusicFolderPicked(payload)。
     */
    private void postResult(String status, String message, JSONObject payload) {
        if (webView == null) return;

        JSONObject result = new JSONObject();
        try {
            result.put("status", status);
            if (message != null) result.put("message", message);
            if (payload != null) {
                for (java.util.Iterator<String> it = payload.keys(); it.hasNext(); ) {
                    String key = it.next();
                    result.put(key, payload.get(key));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "构造回调结果失败", e);
        }

        String js = "window.__foliaOnMusicFolderPicked && window.__foliaOnMusicFolderPicked("
                + JSONObject.quote(result.toString()) + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}
