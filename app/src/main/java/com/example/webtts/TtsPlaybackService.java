package com.example.webtts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Locale;

/**
 * V1.7: Service doc sub CHAY NEN + nut noi dieu khien + 2 giong.
 *
 * - Giong GOOGLE (offline): TextToSpeech + SubtitleSpeaker nhu cu.
 * - Giong HOAIMY Edge (online): EdgeVoiceEngine tai mp3 + preload 35 cau,
 *   do raw THAT bang MediaPlayer, speed = raw/target (port Python).
 * - MainActivity chi gui lenh (play/pause/seek/offset/rate/volume/voice) + sub.
 * - Nut noi tron mo 50% luon tren cung (TYPE_APPLICATION_OVERLAY),
 *   keo di duoc. Bam vao bung panel: Play/Pause, -1s/+1s, gio chay,
 *   volume giong doc, chon giong, Stop.
 * - Ticker chay doc lap 250ms nen tat man / sang app Netflix van doc.
 */
public class TtsPlaybackService extends Service
        implements TextToSpeech.OnInitListener {

    // ----- lenh tu Activity / nut noi -----
    public static final String ACTION_PLAY = "com.example.webtts.PLAY";
    public static final String ACTION_PAUSE = "com.example.webtts.PAUSE";
    public static final String ACTION_TOGGLE = "com.example.webtts.TOGGLE";
    public static final String ACTION_STOP_ALL = "com.example.webtts.STOP_ALL";
    public static final String ACTION_NUDGE = "com.example.webtts.NUDGE";
    public static final String ACTION_SHOW_FLOAT = "com.example.webtts.SHOW_FLOAT";
    public static final String ACTION_HIDE_FLOAT = "com.example.webtts.HIDE_FLOAT";
    public static final String EXTRA_DELTA = "delta";

    private static final String CH_ID = "webtts_playback";
    private static final int NOTIF_ID = 1001;

    // Do mo nut noi: 50%, nen den, bo tron (ban Netflix).
    private static final float FLOAT_ALPHA = 0.5f;

    // ----- state dung chung (static de Activity doc nhanh) -----
    private static List<SubtitleCue> sharedSubs =
            java.util.Collections.emptyList();
    private static int sharedCount = 0;

    public static void setSharedSubtitles(List<SubtitleCue> subs) {
        sharedSubs = subs == null
                ? java.util.Collections.emptyList() : subs;
        sharedCount = sharedSubs.size();
        if (running != null) running.applyShared();
        // Nap sub moi: moc gio ve 00:00 + dung doc + chot moc moi.
        if (running != null) running.resetToStart();
    }

    /** Nap sub moi -> ve 00:00, dung doc, san sang Play tu dau. */
    private void resetToStart() {
        setPlaying(false);
        position = 0;
        playBase = 0;
        playStartElapsed = android.os.SystemClock.elapsedRealtime();
        lastCueText = "...";
        if (speaker != null) speaker.resetIndex();
        if (edge != null) edge.stopPlayer();
        refreshFloatTime();
        updateNotif();
    }

    private static TtsPlaybackService running;

    public static boolean isRunning() { return running != null; }
    public static boolean isPlayingNow() {
        return running != null && running.playing;
    }
    public static String currentCueText() {
        if (running == null) return "...";
        return running.lastCueText;
    }

    // ----- instance -----
    private TextToSpeech tts;
    private SubtitleSpeaker speaker;
    private boolean ttsReady = false;

    /** 0 = Google offline, 1 = HoaiMy Edge online. */
    public static final int VOICE_GOOGLE = 0;
    public static final int VOICE_HOAIMY = 1;
    private int voice = VOICE_GOOGLE;
    private EdgeVoiceEngine edge;

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    /** Dong ho that (thay position += 0.25): moc he thong khi Play. */
    private long playStartElapsed = 0;
    private double playBase = 0;
    private float offset = 0f;
    private float rate = 1.0f;
    private int ttsVol = 100;
    /** true = sub ra loa bao thuc (tach khoi Netflix), false = chung media. */
    private boolean splitAudio = true;
    private String lastCueText = "...";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playing && duration > 0) {
                // Dong ho he thong (sai so ms), khong cong tay de het lech tich luy.
                position = playBase
                        + (android.os.SystemClock.elapsedRealtime()
                                - playStartElapsed) / 1000.0;
                if (position >= duration) {
                    position = duration;
                    setPlaying(false);
                }
                driveEngines();
                updateNotif();
            }
            refreshFloatTime();
            handler.postDelayed(this, 100);
        }
    };

    /** Day position cho engine dang chon (Google hoac HoaiMy). */
    private void driveEngines() {
        if (voice == VOICE_HOAIMY && edge != null) {
            int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
            if (idx >= 0) {
                SubtitleCue cue = sharedSubs.get(idx);
                if (idx != edge.getCurrentIndex()) {
                    double slot = cue.end - position - offset;
                    if (slot < 0.5) slot = 0.5;
                    if (slot > cue.duration()) slot = cue.duration();
                    int stream = splitAudio
                            ? android.media.AudioManager.STREAM_ALARM
                            : android.media.AudioManager.STREAM_MUSIC;
                    boolean ok = edge.speak(idx, slot, stream);
                    if (ok) {
                        lastCueText = cue.text;
                        if (floatCue != null) floatCue.setText(lastCueText);
                        edge.preloadFrom(idx);
                    }
                }
            }
        } else if (ttsReady && speaker != null) {
            speaker.onPosition(position);
        }
    }

    // ----- nut noi -----
    private WindowManager wm;
    private View floatRoot;
    private Button floatDot;
    private LinearLayout floatPanel;
    private TextView floatCue;
    private TextView floatTime;
    private Button floatPlayPause;
    private Button floatVoiceBtn;
    private WindowManager.LayoutParams floatParams;
    private boolean floatShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        loadPrefs();
        startFg();
        tts = new TextToSpeech(getApplicationContext(), this);
        edge = new EdgeVoiceEngine(this, new EdgeVoiceEngine.Listener() {
            @Override public void onReady(int index, int cached, int total) {
                handler.post(() -> updateNotif());
            }
            @Override public void onError(int index, String msg) {
                handler.post(() -> updateNotif());
            }
            @Override public void onSpeakStart(int index) {
                handler.post(() -> {
                    if (index >= 0 && index < sharedSubs.size()) {
                        lastCueText = sharedSubs.get(index).text;
                        if (floatCue != null) floatCue.setText(lastCueText);
                    }
                });
            }
            @Override public void onSpeakDone(int index) {}
        });
        edge.setSubtitles(sharedSubs);
        edge.setBaseRate(rate);
        edge.setTtsVolume(ttsVol);
        handler.post(ticker);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        splitAudio = sp.getBoolean("splitAudio", true);
        voice = sp.getInt("voice", VOICE_GOOGLE);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts.setLanguage(new Locale("vi", "VN"));
            } catch (Exception ignored) {}
            ttsReady = true;
            speaker = new SubtitleSpeaker(tts, new SubtitleSpeaker.Listener() {
                @Override public void onSpeakStart(int index) {
                    handler.post(() -> {
                        if (index >= 0 && index < sharedSubs.size()) {
                            lastCueText = sharedSubs.get(index).text;
                            if (floatCue != null) floatCue.setText(lastCueText);
                        }
                    });
                }
                @Override public void onSpeakDone(int index) {}
                @Override public void onTtsError(int index) {}
            });
            speaker.setSubtitles(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
            applySplitAudio();
            recomputeDuration();
            updateNotif();
        }
    }

    /**
     * Tach/gop stream am thanh giong doc.
     * GHI CHU (gioi han Android): Google TTS mac dinh phat tren STREAM_MUSIC
     * chung voi Netflix -> phim cung to/nho theo. Dat PARAM_STREAM=ALARM thi
     * phim cung media khong anh huong sub; sub doi sang volume bao thuc.
     * Nhuoc: may de che do im lang / volume bao thuc nho thi sub nho theo.
     */
    private void applySplitAudio() {
        if (speaker == null) return;
        try {
            if (splitAudio) {
                speaker.setTtsStream(android.media.AudioManager.STREAM_ALARM);
            } else {
                speaker.setTtsStream(android.media.AudioManager.STREAM_MUSIC);
            }
        } catch (Exception ignored) {}
    }

    public static void setSplitAudio(boolean v) {
        if (running != null) {
            running.splitAudio = v;
            running.applySplitAudio();
        }
    }

    public static boolean getSplitAudio() {
        return running == null || running.splitAudio;
    }

    /** "mm:ss / mm:ss" de can dong bo voi Netflix. */
    public static String timeText() {
        if (running == null) return "00:00 / 00:00";
        return fmtTime(running.position) + " / " + fmtTime(running.duration);
    }

    private static String fmtTime(double sec) {
        if (sec < 0) sec = 0;
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void startFg() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Web TTS", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Intent tgI = new Intent(this, TtsPlaybackService.class);
        tgI.setAction(ACTION_TOGGLE);
        PendingIntent tgPi = PendingIntent.getService(this, 2, tgI, flags);

        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Web TTS dang chay nen")
                .setContentText(notifText())
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", tgPi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private String notifText() {
        String v = voice == VOICE_HOAIMY ? "HoaiMy" : "Google";
        if (sharedCount > 0) {
            String base = (playing ? "Dang doc" : "Tam dung") + " "
                    + sharedCount + " cau (" + v + ")";
            if (voice == VOICE_HOAIMY && edge != null) {
                if (edge.readyCount() > 0) {
                    base += " - tai " + edge.readyCount() + " cau";
                } else if (edge.getLastError() != null) {
                    String e = edge.getLastError();
                    if (e.length() > 60) e = e.substring(0, 60);
                    base += " - loi: " + e;
                } else {
                    base += " - dang tai...";
                }
            }
            return base;
        }
        return "Cho nap phu de .srt (" + v + ")";
    }

    /** Loi HoaiMy gan nhat de MainActivity hien (null = khong loi). */
    public static String edgeError() {
        if (running == null || running.edge == null) return null;
        return running.edge.getLastError();
    }

    private void updateNotif() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Intent open = new Intent(this, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
            Notification n = new NotificationCompat.Builder(this, CH_ID)
                    .setContentTitle("Web TTS dang chay nen")
                    .setContentText(notifText())
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    // ----- nhan lenh -----
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_PLAY.equals(a)) setPlaying(true);
            else if (ACTION_PAUSE.equals(a)) setPlaying(false);
            else if (ACTION_TOGGLE.equals(a)) setPlaying(!playing);
            else if (ACTION_STOP_ALL.equals(a)) {
                setPlaying(false);
                hideFloat();
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_NUDGE.equals(a)) {
                double d = intent.getDoubleExtra(EXTRA_DELTA, 0);
                nudge(d);
            } else if (ACTION_SHOW_FLOAT.equals(a)) {
                showFloat();
            } else if (ACTION_HIDE_FLOAT.equals(a)) {
                hideFloat();
            }
        }
        startFg();
        return START_STICKY;
    }

    private void setPlaying(boolean p) {
        if (p && sharedSubs.isEmpty()) {
            playing = false;
            updateNotif();
            return;
        }
        if (p && !playing) {
            // Bam Play: chot moc gio that (het lech tich luy).
            syncClock();
        }
        if (!p && playing) {
            // Bam Pause: dong bang position theo dong ho that.
            position = clockNow();
        }
        playing = p;
        if (speaker != null) speaker.setPlaying(p);
        if (edge != null) edge.setPlaying(p);
        if (!p) {
            if (tts != null) {
                try { tts.stop(); } catch (Exception ignored) {}
            }
            if (edge != null) edge.stopPlayer();
        } else {
            // Bam Play: cat cau cu + preload ngay 35 cau HoaiMy.
            if (speaker != null) speaker.resetIndex();
            if (edge != null) edge.stopPlayer();
            driveEngines();
            if (voice == VOICE_HOAIMY && edge != null) {
                int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
                if (idx < 0) idx = 0;
                edge.preloadFrom(idx);
            }
        }
        refreshFloatPlayBtn();
        updateNotif();
    }

    /** Chot moc: gio = position hien tai, dem tu bay gio. */
    private void syncClock() {
        playBase = position;
        playStartElapsed = android.os.SystemClock.elapsedRealtime();
    }

    /** Gio that hien tai theo moc (dung khi Pause/nudge). */
    private double clockNow() {
        if (!playing) return position;
        return playBase + (android.os.SystemClock.elapsedRealtime()
                - playStartElapsed) / 1000.0;
    }

    private void nudge(double delta) {
        // Nudge theo dong ho that + chot moc lai de khong troi gio.
        double now = playing ? clockNow() : position;
        position = now + delta;
        if (position < 0) position = 0;
        if (duration > 0 && position > duration) position = duration;
        if (playing) syncClock();
        if (voice == VOICE_HOAIMY && edge != null) {
            edge.stopPlayer();
            int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
            if (idx >= 0) edge.preloadFrom(idx);
            driveEngines();
        } else if (speaker != null) {
            speaker.resetIndex();
            speaker.onPosition(position);
        }
        refreshFloatTime();
    }

    private void applyShared() {
        if (speaker != null) {
            speaker.setSubtitlesIfNew(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
        }
        if (edge != null) {
            edge.setSubtitles(sharedSubs);
            edge.setBaseRate(rate);
            edge.setTtsVolume(ttsVol);
        }
        recomputeDuration();
        updateNotif();
    }

    private void recomputeDuration() {
        duration = 0;
        for (SubtitleCue c : sharedSubs) duration = Math.max(duration, c.end);
    }

    // ----- API static cho Activity / nut noi -----
    public static void cmd(Context ctx, String action) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(action);
        startSvc(ctx, i);
    }

    public static void cmdNudge(Context ctx, double delta) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(ACTION_NUDGE);
        i.putExtra(EXTRA_DELTA, delta);
        startSvc(ctx, i);
    }

    private static void startSvc(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void setOffset(float v) {
        if (running != null) {
            running.offset = v;
            if (running.speaker != null) running.speaker.setAudioOffset(v);
        }
    }

    public static float getOffset() {
        return running != null ? running.offset : 0f;
    }

    public static void setRate(float v) {
        if (running != null) {
            running.rate = v;
            if (running.speaker != null) running.speaker.setSpeechRate(v);
            if (running.edge != null) running.edge.setBaseRate(v);
        }
    }

    public static void setTtsVolume(int p) {
        if (running != null) {
            running.ttsVol = p;
            if (running.speaker != null) running.speaker.setTtsVolume(p);
            if (running.edge != null) running.edge.setTtsVolume(p);
        }
    }

    public static int getTtsVolume() {
        return running != null ? running.ttsVol : 100;
    }

    /** 0 = Google offline, 1 = HoaiMy Edge online. */
    public static void setVoice(int v) {
        if (running != null) {
            boolean changed = running.voice != v;
            running.voice = v;
            if (changed) {
                // Doi giong: dung engine cu, preload lai engine moi.
                if (running.speaker != null) running.speaker.stop();
                if (running.tts != null) {
                    try { running.tts.stop(); } catch (Exception ignored) {}
                }
                if (running.edge != null) running.edge.stopPlayer();
                if (v == VOICE_HOAIMY && running.edge != null
                        && running.playing) {
                    int idx = SubtitleParser.findIndex(sharedSubs,
                            running.position + running.offset);
                    if (idx < 0) idx = 0;
                    running.edge.preloadFrom(idx);
                }
                running.updateNotif();
                running.refreshFloatVoiceBtn();
            }
        }
    }

    public static int getVoice() {
        return running != null ? running.voice : VOICE_GOOGLE;
    }

    public static String voiceName() {
        return getVoice() == VOICE_HOAIMY ? "HoaiMy (online)" : "Google (offline)";
    }

    /** So cau HoaiMy da tai xong de hien tien do preload. */
    public static String edgeProgress() {
        if (running == null || running.edge == null) return "";
        return running.edge.readyCount() + " cau";
    }

    /** Tong MB cache HoaiMy de hien tren nut Xoa cache. */
    public static String cacheText() {
        if (running == null || running.edge == null) return "Xoa cache (0MB)";
        long b = running.edge.cacheBytes();
        long mb = b / (1024 * 1024);
        return "Xoa cache (" + mb + "MB)";
    }

    /** Xoa sach mp3 HoaiMy da tai. Tra ve so file da xoa. */
    public static int clearCache() {
        if (running == null || running.edge == null) return 0;
        return running.edge.clearCache();
    }

    public static void setPosition(double pos) {
        if (running != null) {
            running.position = pos;
            // Seek tu Activity/WebView: chot moc lai + cat cau cu.
            if (running.playing) running.syncClock();
            if (running.speaker != null) running.speaker.resetIndex();
            if (running.edge != null) running.edge.stopPlayer();
            running.driveEngines();
        }
    }

    public static double getPosition() {
        if (running == null) return 0;
        // Tra gio that (dung cho UI), khong phai bien position cu.
        return running.playing ? running.clockNow() : running.position;
    }

    // ----- nut noi -----
    public static boolean canDrawOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    private void showFloat() {
        if (floatShown) return;
        if (!canDrawOverlay(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            buildFloatViews();
            int type;
            if (Build.VERSION.SDK_INT >= 26) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_PHONE;
            }
            floatParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            floatParams.gravity = Gravity.TOP | Gravity.END;
            floatParams.x = 16;
            floatParams.y = 200;
            wm.addView(floatRoot, floatParams);
            floatShown = true;
        } catch (Exception ignored) {}
    }

    private void hideFloat() {
        if (!floatShown) return;
        try {
            if (wm != null && floatRoot != null) wm.removeView(floatRoot);
        } catch (Exception ignored) {}
        floatShown = false;
        floatRoot = null;
    }

    private void buildFloatViews() {
        Context ctx = this;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        floatDot = new Button(ctx);
        floatDot.setText("TTS");
        floatDot.setTextSize(13);
        floatDot.setAlpha(FLOAT_ALPHA);
        // Ban Netflix: nen den + bo tron (thay mau xanh cu).
        try {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setCornerRadius(200f);
            floatDot.setBackground(bg);
        } catch (Exception e) {
            floatDot.setBackgroundColor(0xFF000000);
        }
        floatDot.setTextColor(0xFFFFFFFF);
        floatDot.setPadding(24, 24, 24, 24);
        floatDot.setOnClickListener(v -> togglePanel());
        floatDot.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (floatParams == null || wm == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = floatParams.x - e.getRawX();
                        dy = floatParams.y - e.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        floatParams.x = (int) (e.getRawX() + dx);
                        floatParams.y = (int) (e.getRawY() + dy);
                        try {
                            wm.updateViewLayout(floatRoot, floatParams);
                        } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });

        floatPanel = new LinearLayout(ctx);
        floatPanel.setOrientation(LinearLayout.VERTICAL);
        floatPanel.setBackgroundColor(0xEE222222);
        floatPanel.setPadding(12, 12, 12, 12);
        floatPanel.setVisibility(View.GONE);

        floatCue = new TextView(ctx);
        floatCue.setText(lastCueText);
        floatCue.setTextColor(0xFFFFFFFF);
        floatCue.setTextSize(13);
        floatCue.setMaxLines(2);
        floatPanel.addView(floatCue);

        // Dong ho chay de can dong bo voi Netflix: "mm:ss / mm:ss".
        floatTime = new TextView(ctx);
        floatTime.setText(timeText());
        floatTime.setTextColor(0xFF90CAF9);
        floatTime.setTextSize(13);
        floatPanel.addView(floatTime);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        floatPlayPause = new Button(ctx);
        floatPlayPause.setText(playing ? "Pause" : "Play");
        floatPlayPause.setTextSize(12);
        floatPlayPause.setOnClickListener(v -> setPlaying(!playing));
        Button bBack = new Button(ctx);
        bBack.setText("-1s");
        bBack.setTextSize(12);
        bBack.setOnClickListener(v -> nudge(-1.0));
        Button bFwd = new Button(ctx);
        bFwd.setText("+1s");
        bFwd.setTextSize(12);
        bFwd.setOnClickListener(v -> nudge(1.0));
        row1.addView(floatPlayPause);
        row1.addView(bBack);
        row1.addView(bFwd);
        floatPanel.addView(row1);

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(ctx);
        lb.setText("Sub:");
        lb.setTextColor(0xFFCCCCCC);
        lb.setTextSize(12);
        SeekBar vol = new SeekBar(ctx);
        vol.setMax(100);
        vol.setProgress(ttsVol);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vol.setLayoutParams(lp);
        final TextView vv = new TextView(ctx);
        vv.setText(ttsVol + "%");
        vv.setTextColor(0xFFFFFFFF);
        vv.setTextSize(12);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                ttsVol = p;
                vv.setText(p + "%");
                getSharedPreferences("webtts", MODE_PRIVATE)
                        .edit().putInt("ttsVol", p).apply();
                if (speaker != null) speaker.setTtsVolume(p);
                if (edge != null) edge.setTtsVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row2.addView(lb);
        row2.addView(vol);
        row2.addView(vv);
        floatPanel.addView(row2);

        // Hang chon giong: Google offline / HoaiMy online.
        LinearLayout rowVoice = new LinearLayout(ctx);
        rowVoice.setOrientation(LinearLayout.HORIZONTAL);
        floatVoiceBtn = new Button(ctx);
        floatVoiceBtn.setTextSize(12);
        refreshFloatVoiceBtn();
        floatVoiceBtn.setOnClickListener(v -> {
            int nv = (voice == VOICE_GOOGLE) ? VOICE_HOAIMY : VOICE_GOOGLE;
            setVoice(nv);
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putInt("voice", nv).apply();
        });
        rowVoice.addView(floatVoiceBtn);
        floatPanel.addView(rowVoice);

        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button bHide = new Button(ctx);
        bHide.setText("An nut");
        bHide.setTextSize(12);
        bHide.setOnClickListener(v -> hideFloat());
        Button bSplit = new Button(ctx);
        bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
        bSplit.setTextSize(12);
        bSplit.setOnClickListener(v -> {
            splitAudio = !splitAudio;
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putBoolean("splitAudio", splitAudio).apply();
            applySplitAudio();
            bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
            android.widget.Toast.makeText(ctx, splitAudio
                    ? "Sub ra loa bao thuc — phim cung media khong anh huong."
                    : "Sub chung loa media — phim cung to/nho theo.",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        Button bStop = new Button(ctx);
        bStop.setText("Dung doc");
        bStop.setTextSize(12);
        bStop.setOnClickListener(v -> {
            setPlaying(false);
            hideFloat();
        });
        Button bOpen = new Button(ctx);
        bOpen.setText("Cai dat");
        bOpen.setTextSize(12);
        bOpen.setOnClickListener(v -> {
            // Ban Netflix: chi 1 man Cai dat.
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        row3.addView(bHide);
        row3.addView(bSplit);
        row3.addView(bStop);
        row3.addView(bOpen);
        floatPanel.addView(row3);

        root.addView(floatDot);
        root.addView(floatPanel);
        floatRoot = root;
    }

    private void togglePanel() {
        if (floatPanel == null) return;
        boolean show = floatPanel.getVisibility() != View.VISIBLE;
        floatPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (floatCue != null) floatCue.setText(lastCueText);
            refreshFloatTime();
        }
    }

    /** Cap nhat "mm:ss / mm:ss" tren nut noi de can voi Netflix. */
    private void refreshFloatTime() {
        if (!floatShown || floatTime == null) return;
        try {
            floatTime.setText(timeText());
        } catch (Exception ignored) {}
    }

    private void refreshFloatPlayBtn() {
        if (floatPlayPause != null) {
            floatPlayPause.setText(playing ? "Pause" : "Play");
        }
    }

    private void refreshFloatVoiceBtn() {
        if (floatVoiceBtn != null) {
            floatVoiceBtn.setText(voice == VOICE_HOAIMY
                    ? "Giong: HoaiMy (online)"
                    : "Giong: Google (offline)");
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        hideFloat();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        try {
            if (edge != null) edge.shutdown();
        } catch (Exception ignored) {}
        running = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
