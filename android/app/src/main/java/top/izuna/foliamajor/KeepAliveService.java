package top.izuna.foliamajor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * 媒体播放前台服务：MediaSession + MediaStyle 通知。
 *
 * 功能（对照网易云/QQ 音乐的通知）：
 *   - 通知栏显示正在播放的歌曲名/歌手/专辑/封面
 *   - 通知上带 上一首 / 播放暂停 / 下一首 三个按钮
 *   - 保活：后台播放不中断
 *   - 支持 MediaSession 的设备（含灵动岛类形态）自动获得系统级媒体控件
 *
 * 数据流：
 *   JS（useMediaSessionBridge.ts）→ window.FoliaMedia.updateMedia/updatePlayback
 *   → 本类更新 MediaSessionCompat + 重建 MediaStyle 通知
 *   通知按钮 → PendingIntent.getService(本类) → onStartCommand →
 *   evaluateJavascript 调 window.__foliaMediaCommands.play/pause/prev/next
 *   （由 useMediaSessionBridge.ts 在安卓端挂到全局，桥接回播放器的控制方法）
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_PLAY = "top.izuna.foliamajor.ACTION_PLAY";
    public static final String ACTION_PAUSE = "top.izuna.foliamajor.ACTION_PAUSE";
    public static final String ACTION_NEXT = "top.izuna.foliamajor.ACTION_NEXT";
    public static final String ACTION_PREV = "top.izuna.foliamajor.ACTION_PREV";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 由 MainActivity 注入（系统创建服务实例，无法走构造器传参）。 */
    private static WebView sharedWebView;
    private WebView webView;
    private MediaSessionCompat session;
    private boolean isPlaying = false;
    private boolean hasSession = false;
    private String lastTitle = "";
    private String lastArtist = "";
    private String lastAlbum = "";
    private String lastArtUri = "";
    private android.graphics.Bitmap lastBitmap;
    private String lastBitmapUrl = "";

    /** JS 桥：前端把当前歌曲与播放状态推给原生。 */
    private class Api {
        @JavascriptInterface
        public void updateMedia(String title, String artist, String album, String artUri) {
            mainHandler.post(() -> {
                android.util.Log.i("FoliaMedia", "收到的歌曲信息: title=" + lastTitle + " artist=" + lastArtist);
                lastTitle = title == null ? "" : title;
                lastArtist = artist == null ? "" : artist;
                lastAlbum = album == null ? "" : album;
                lastArtUri = artUri == null ? "" : artUri;
                publishSessionMetadata();
            });
        }

        @JavascriptInterface
        public void updatePlayback(boolean playing, double positionMs) {
            mainHandler.post(() -> {
                isPlaying = playing;
                publishPlaybackState(playing ? PlaybackStateCompat.STATE_PLAYING
                                             : PlaybackStateCompat.STATE_PAUSED,
                        (long) positionMs);
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        setupMediaSession();
        startForegroundInternal();
        if (sharedWebView != null) {
            webView = sharedWebView;
            webView.addJavascriptInterface(new Api(), "FoliaMedia");
        }
    }

    /** MainActivity 在启动服务前注入 WebView。 */
    public static void setSharedWebView(WebView view) {
        sharedWebView = view;
    }

    /** MainActivity 创建服务时传入 WebView，用于把通知按钮的指令回调给页面。 */
    public void attach(WebView webView) {
        this.webView = webView;
        webView.addJavascriptInterface(new Api(), "FoliaMedia");
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台播放音乐时保持运行");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private void setupMediaSession() {
        session = new MediaSessionCompat(this, "FoliaPlayback");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { dispatch("play"); }
            @Override public void onPause() { dispatch("pause"); }
            @Override public void onSkipToNext() { dispatch("next"); }
            @Override public void onSkipToPrevious() { dispatch("prev"); }
            @Override
            public void onSeekTo(long pos) {
                dispatch("seek", String.valueOf(pos));
            }
        });
        session.setActive(true);
        hasSession = true;
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台播放音乐时保持运行");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(lastTitle.isEmpty() ? "Folia" : lastTitle)
                .setContentText(lastTitle.isEmpty() ? "音乐播放中" : lastArtist)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                if (lastBitmap != null) builder.setLargeIcon(lastBitmap);

        if (hasSession && session != null) {
            builder.setStyle(new MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
            builder.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "上一首",
                    actionPendingIntent(ACTION_PREV, 1002)));
            builder.addAction(new NotificationCompat.Action(
                    isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    isPlaying ? "暂停" : "播放",
                    actionPendingIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 1003)));
            builder.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "下一首",
                    actionPendingIntent(ACTION_NEXT, 1004)));
        }
        return builder.build();
    }


    private void publishSessionMetadata() {
        if (!hasSession || session == null) return;
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        lastTitle.isEmpty() ? "Folia" : lastTitle);
        if (!lastArtist.isEmpty()) b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist);
        if (!lastAlbum.isEmpty()) b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, lastAlbum);
        if (!lastArtUri.isEmpty()) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, lastArtUri);
            // [folia 补丁] MediaStyle 通知要真正显示封面，需要把**位图**放进
            // METADATA_KEY_ALBUM_ART —— 部分场景只给 URI 时系统不会自动加载，
            // 通知就没有封面图。这里异步下载后刷新通知。
            if (lastBitmap == null || !lastArtUri.equals(lastBitmapUrl)) {
                final String url = lastArtUri;
                new Thread(() -> {
                    try {
                        java.net.URL u = new java.net.URL(url);
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                                .decodeStream(u.openConnection().getInputStream());
                        if (bmp != null) {
                            lastBitmap = bmp;
                            lastBitmapUrl = url;
                            mainHandler.post(() -> publishSessionMetadata());
                        }
                    } catch (Exception e) {
                        android.util.Log.w("FoliaMedia", "封面下载失败", e);
                    }
                }).start();
            }
        }
        if (lastBitmap != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, lastBitmap);
        session.setMetadata(b.build());
        refreshNotification();
    }

    private void publishPlaybackState(int state, long positionMs) {
        if (!hasSession || session == null) return;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, positionMs, 1.0f)
                .build());
        refreshNotification();
    }

    /** 通知内容/按钮随播放状态变化，需要重新发布。 */
    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private PendingIntent actionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, KeepAliveService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    /** 通知按钮 → 页面全局命令（useMediaSessionBridge.ts 挂到 window 上）。 */
    private void dispatch(String command, String... arg) {
        if (webView == null) return;
        StringBuilder js = new StringBuilder(
                "window.__foliaMediaCommands && window.__foliaMediaCommands." + command);
        if (arg != null && arg.length > 0) {
            js.append("(").append(arg[0]).append(")");
        } else {
            js.append("()");
        }
        js.append(";");
        String script = js.toString();
        mainHandler.post(() -> webView.evaluateJavascript(script, null));
    }

    private void dispatch(String command) {
        dispatch(command, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) dispatch("play");
        else if (ACTION_PAUSE.equals(action)) dispatch("pause");
        else if (ACTION_NEXT.equals(action)) dispatch("next");
        else if (ACTION_PREV.equals(action)) dispatch("prev");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
