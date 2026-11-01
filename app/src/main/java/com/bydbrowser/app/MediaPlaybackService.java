package com.bydbrowser.app;

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
import android.os.IBinder;

/**
 * Keeps the app alive while a tab plays audio, and shows a media notification with
 * previous / play-pause / next / close. The MediaSession also routes the car's
 * steering-wheel media buttons to the playing tab.
 */
public class MediaPlaybackService extends Service {

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 7;

    static final String A_UPDATE = "update", A_PLAY = "play", A_PAUSE = "pause",
            A_TOGGLE = "toggle", A_NEXT = "next", A_PREV = "prev", A_CLOSE = "close";

    private MediaSession session;
    private String title = "", subtitle = "";
    private boolean playing;

    static void update(Context c, String title, String subtitle, boolean playing) {
        Intent i = new Intent(c, MediaPlaybackService.class).setAction(A_UPDATE)
                .putExtra("title", title).putExtra("sub", subtitle).putExtra("playing", playing);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // Android 12+ refuses to start a foreground service from the background;
            // the next update made while the app is on screen will start it.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, MediaPlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Now playing",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "DriveBrowser");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { control(A_PLAY); }
            @Override public void onPause() { control(A_PAUSE); }
            @Override public void onSkipToNext() { control(A_NEXT); }
            @Override public void onSkipToPrevious() { control(A_PREV); }
            @Override public void onStop() { control(A_CLOSE); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (A_UPDATE.equals(action)) {
            title = intent.getStringExtra("title");
            subtitle = intent.getStringExtra("sub");
            playing = intent.getBooleanExtra("playing", false);
        } else if (action != null) {
            control(action);
        }
        post();
        return START_NOT_STICKY;
    }

    private void control(String action) {
        MainActivity a = MainActivity.instance;
        if (a == null) { stopSelf(); return; }
        switch (action) {
            case A_PLAY: a.mediaPlay(); break;
            case A_PAUSE: a.mediaPause(); break;
            case A_TOGGLE: if (playing) a.mediaPause(); else a.mediaPlay(); break;
            case A_NEXT: a.mediaNext(); break;
            case A_PREV: a.mediaPrev(); break;
            case A_CLOSE: a.mediaClose(); break;
        }
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, MediaPlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action btn(int icon, String label, String a, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(a, code)).build();
    }

    @SuppressWarnings("deprecation")
    private void post() {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHOW_MEDIA_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(title == null || title.isEmpty() ? "Drive Browser" : title)
                .setContentText(subtitle)
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(btn(android.R.drawable.ic_media_previous, "Previous", A_PREV, 1))
                .addAction(playing
                        ? btn(android.R.drawable.ic_media_pause, "Pause", A_PAUSE, 2)
                        : btn(android.R.drawable.ic_media_play, "Play", A_PLAY, 3))
                .addAction(btn(android.R.drawable.ic_media_next, "Next", A_NEXT, 4))
                .addAction(btn(android.R.drawable.ic_menu_close_clear_cancel, "Close", A_CLOSE, 5))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();

        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else
                startForeground(NOTIFICATION_ID, n);
        } catch (Exception e) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        session.setActive(false);
        session.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
