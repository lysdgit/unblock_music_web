package com.jiehui.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 媒体前台服务 —— 本工程解决「后台播放几分钟就自动暂停」的核心。
 *
 * <p>为什么需要它：
 * Android 8.0(API 26) 起，应用退到后台后会被判定为「可缓存进程」，
 * 系统随后会冻结它（Android 11+ 的 App Freezer）以省电，几分钟内 WebView 中的
 * 音频就被掐断。挂上前台服务（带常驻通知）后，进程被提升为「前台进程」级别，
 * 系统不会冻结，音频得以持续播放。
 *
 * <p>另外两件事一起做了：
 * <ul>
 *   <li>MediaSession：满足 Android 14 对 mediaPlayback 类型前台服务的要求，
 *       同时让锁屏 / 通知栏 / 耳机线控能控制播放。</li>
 *   <li>PARTIAL_WAKE_LOCK：息屏后 CPU 仍能跑，保证「一曲结束自动下一首」的逻辑不断。</li>
 * </ul>
 *
 * <p><b>⚠️ 这里刻意<b>不做</b> AudioFocus（音频焦点）管理，这是踩过的坑：</b>
 * 安卓的音焦点是按「进程」划分的，而 WebView 的音频播放跑在独立的沙箱渲染进程里，
 * Chromium 内部已经实现了完整的音焦点管理（来电话自动暂停、结束后恢复它自己会做）。
 * 如果宿主 App 再去 requestAudioFocus(AUDIOFOCUS_GAIN)，系统会把 Chromium 那份焦点
 * 判为 AUDIOFOCUS_LOSS，而 Chromium 收到 LOSS 的标准反应就是暂停 &lt;audio&gt; ——
 * 表现为「点播放只响一下就被掐断、进度条不走」。
 * 所以：WebView 音乐壳千万不要自己去抢音焦点。
 */
public class PlaybackService extends Service {

    // ---------- 外部指令 ----------
    public static final String ACTION_START = "com.jiehui.music.action.START";
    public static final String ACTION_STOP = "com.jiehui.music.action.STOP";
    public static final String ACTION_TOGGLE = "com.jiehui.music.action.TOGGLE";
    public static final String ACTION_NEXT = "com.jiehui.music.action.NEXT";
    public static final String ACTION_PREV = "com.jiehui.music.action.PREV";
    public static final String ACTION_DIAG = "com.jiehui.music.action.DIAG";

    // ---------- 下发给页面的 JS ----------
    // 页面里就是一个 <audio id="audio">，直接操作它最稳，不依赖页面内部函数作用域
    public static final String JS_PLAY =
            "(function(){var a=document.getElementById('audio');if(a&&a.paused){a.play().catch(function(){});}})()";
    public static final String JS_PAUSE =
            "(function(){var a=document.getElementById('audio');if(a&&!a.paused){a.pause();}})()";
    public static final String JS_TOGGLE =
            "(function(){var a=document.getElementById('audio');if(!a)return;"
                    + "if(a.paused){a.play().catch(function(){});}else{a.pause();}})()";
    public static final String JS_NEXT =
            "(function(){var b=document.getElementById('nextBtn');if(b&&b.click)b.click();})()";
    public static final String JS_PREV =
            "(function(){var b=document.getElementById('prevBtn');if(b&&b.click)b.click();})()";
    /** 切出页面内的诊断浮层（播放异常时用来查看音频元素真实状态） */
    public static final String JS_TOGGLE_DIAG =
            "(function(){if(window.__jhToggleDiag)window.__jhToggleDiag();})()";

    private static final String CHANNEL_ID = "jiehui_playback";
    private static final int NOTIFICATION_ID = 1001;

    /** 运行中的服务实例（同一时刻只应有一个） */
    private static volatile PlaybackService sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NotificationManager notificationManager;
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;

    private boolean foregroundStarted = false;
    private boolean playing = false;
    private String title = "";
    private String artist = "";

    // =====================================================================
    // 对外静态入口
    // =====================================================================

    /** 启动（或唤醒）前台服务。必须在 Activity 处于前台时调用，Android 12+ 不允许后台启动前台服务。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }

    /**
     * 页面通过 JS 桥上报播放状态，用于刷新通知栏与媒体会话。
     * 该方法可能运行在 WebView 的 JS 线程，必须切回主线程。
     */
    public static void updateState(final boolean isPlaying, final String newTitle, final String newArtist) {
        final PlaybackService service = sInstance;
        if (service == null) {
            return;
        }
        service.mainHandler.post(new Runnable() {
            @Override
            public void run() {
                service.applyState(isPlaying, newTitle, newArtist);
            }
        });
    }

    // =====================================================================
    // Service 生命周期
    // =====================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        createWakeLock();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent 为 null 说明是系统回收后重启的，此时 WebView 早已不存在，
        // 直接收尾退出，避免留下一个没有声音的空壳通知。
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction() == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }

        // 进入前台（必须在 startForegroundService 后 5 秒内调用）
        enterForeground();

        switch (action) {
            case ACTION_TOGGLE:
                WebViewHolder.eval(JS_TOGGLE);
                break;
            case ACTION_NEXT:
                WebViewHolder.eval(JS_NEXT);
                break;
            case ACTION_PREV:
                WebViewHolder.eval(JS_PREV);
                break;
            case ACTION_DIAG:
                WebViewHolder.eval(JS_TOGGLE_DIAG);
                break;
            case ACTION_START:
            default:
                break;
        }

        // 不自动重启：没有 WebView 就没有播放，重启毫无意义
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 用户从「最近任务」里划掉应用 —— 此时页面已不可用，收尾退出 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        WebViewHolder.eval(JS_PAUSE);
        stopPlayback();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Throwable ignored) {
            }
            mediaSession = null;
        }
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    // =====================================================================
    // 前台化 / 通知
    // =====================================================================

    private void enterForeground() {
        if (foregroundStarted) {
            return;
        }
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 显式声明前台服务类型；Android 14 必须与清单一致，否则 SecurityException
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            acquireWakeLock();
        } catch (Throwable t) {
            // 万一系统拒绝（例如没有通知权限的极端情况），不要崩，退化为后台服务即可
            foregroundStarted = false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        PendingIntent toggleIntent = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE), piFlags);
        PendingIntent prevIntent = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_PREV), piFlags);
        PendingIntent nextIntent = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).setAction(ACTION_NEXT), piFlags);
        PendingIntent diagIntent = PendingIntent.getService(this, 4,
                new Intent(this, PlaybackService.class).setAction(ACTION_DIAG), piFlags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(artist.isEmpty() ? getString(R.string.notification_idle) : artist)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            builder.addAction(buildAction(R.drawable.ic_stat_prev, getString(R.string.action_prev), prevIntent));
            builder.addAction(buildAction(playing ? R.drawable.ic_stat_pause : R.drawable.ic_stat_play,
                    getString(R.string.action_toggle), toggleIntent));
            builder.addAction(buildAction(R.drawable.ic_stat_next, getString(R.string.action_next), nextIntent));
            // 第 4 个动作：展开通知才看得到，点一下在页面底部弹出播放状态诊断浮层
            builder.addAction(buildAction(R.drawable.ic_stat_diag, getString(R.string.action_diag), diagIntent));

            builder.setStyle(new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.getSessionToken()));
        }

        return builder.build();
    }

    private Notification.Action buildAction(int iconRes, CharSequence label, PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Notification.Action.Builder(
                    Icon.createWithResource(this, iconRes), label, intent).build();
        }
        return new Notification.Action.Builder(iconRes, label, intent).build();
    }

    private void refreshNotification() {
        if (!foregroundStarted || notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 状态同步（由页面 JS 上报驱动）
    // =====================================================================

    private void applyState(boolean isPlaying, String newTitle, String newArtist) {
        boolean changed = (isPlaying != playing);
        playing = isPlaying;

        if (newTitle != null && !newTitle.isEmpty() && !newTitle.equals(title)) {
            title = newTitle;
            changed = true;
        }
        if (newArtist != null && !newArtist.isEmpty() && !newArtist.equals(artist)) {
            artist = newArtist;
            changed = true;
        }
        if (!changed) {
            return;
        }

        // 注意：这里不申请音频焦点，交给 Chromium 自己管理（见类注释）
        updateMediaSession();
        refreshNotification();
    }

    // =====================================================================
    // MediaSession
    // =====================================================================

    private void createMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        mediaSession = new MediaSession(this, "JieHuiMusic");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                WebViewHolder.eval(JS_PLAY);
            }

            @Override
            public void onPause() {
                WebViewHolder.eval(JS_PAUSE);
            }

            @Override
            public void onStop() {
                WebViewHolder.eval(JS_PAUSE);
            }

            @Override
            public void onSkipToNext() {
                WebViewHolder.eval(JS_NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                WebViewHolder.eval(JS_PREV);
            }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;

        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(state);

        if (!title.isEmpty()) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .build();
            mediaSession.setMetadata(metadata);
        }
    }

    // =====================================================================
    // WakeLock
    // =====================================================================

    private void createWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JieHuiMusic::Playback");
        wakeLock.setReferenceCounted(false);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                // 30 分钟自动释放兜底，避免异常路径下一直耗电；服务存活期间会周期性续期
                wakeLock.acquire(30 * 60 * 1000L);
            } catch (Throwable ignored) {
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // 收尾
    // =====================================================================

    private void stopPlayback() {
        releaseWakeLock();
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        foregroundStarted = false;
        stopSelf();
    }
}
