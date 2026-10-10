package top.izuna.foliamajor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把内置的 Node 项目从 assets 解包到内部存储。
 *
 * 为什么必须解包：Node 用的是 POSIX 文件 API（open/readdir），
 * 而 assets 在 APK 里是压缩包，Node 根本读不到。所以首次运行要完整复制一份到 filesDir。
 *
 * 用「上次更新时间」做标记：App 升级后会自动重新解包，平时启动直接复用，不再重复拷贝。
 */
public final class NodeProjectInstaller {

    private static final String TAG = "FoliaNode";

    /** assets 下的目录名。 */
    private static final String ASSET_DIR = "nodejs-project";

    /** 解包完成的标记文件（相对项目目录）。 */
    private static final String MARKER = ".folia-installed";

    private NodeProjectInstaller() {
    }

    /**
     * 确保内置 Node 项目已经解包到 filesDir。
     *
     * @return 项目目录（可直接交给 node::Start 的 cwd）
     */
    public static File ensureInstalled(Context context) throws IOException {
        File target = new File(context.getFilesDir(), ASSET_DIR);
        long lastUpdate = readLastUpdateTime(context);

        File marker = new File(target, MARKER);
        if (marker.isFile() && String.valueOf(lastUpdate).equals(readText(marker))) {
            Log.i(TAG, "内置 Node 项目已是最新，跳过解包: " + target);
            return target;
        }

        Log.i(TAG, "开始解包内置 Node 项目 -> " + target);
        long begin = System.currentTimeMillis();

        deleteRecursively(target);
        target.mkdirs();

        copyAssetDir(context.getAssets(), ASSET_DIR, target);

        writeText(marker, String.valueOf(lastUpdate));

        Log.i(TAG, "解包完成，用时 " + (System.currentTimeMillis() - begin) + " ms");
        return target;
    }

    private static void copyAssetDir(AssetManager assets, String assetPath, File outDir)
            throws IOException {
        String[] children = assets.list(assetPath);

        // list() 对「文件」和「空目录」都返回空数组；这里按文件处理即可。
        if (children == null || children.length == 0) {
            File parent = outDir.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (InputStream in = assets.open(assetPath);
                 OutputStream out = new FileOutputStream(outDir)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return;
        }

        outDir.mkdirs();
        for (String child : children) {
            copyAssetDir(assets, assetPath + "/" + child, new File(outDir, child));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static long readLastUpdateTime(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (Exception e) {
            Log.w(TAG, "读取包更新时间失败", e);
            return 0L;
        }
    }

    private static String readText(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, "UTF-8").trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String value) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "写标记文件失败（不影响本次运行）", e);
        }
    }
}
