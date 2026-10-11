package com.example.webtts;

import android.content.Context;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V1.7: Engine giong HoaiMy Edge (online) + preload 35 cau.
 *
 * Port tu web_tts Python:
 * - generate_raw_tts(): goi Edge WS -> mp3 raw, cache theo sha1(voice|index|text).
 * - preload 35 cau nhu PRELOAD_COUNT (worker pool 3 nhu TRANSLATE_MAX_WORKERS).
 * - Do raw_duration THAT bang MediaPlayer (thay ffprobe) de tinh speed
 *   theo cong thuc Python: speed = raw/target, kep 1.0..2.5.
 * - Phat rieng bang MediaPlayer (khong dung Google TTS) nen khong can atempo.
 *   Muốn vừa khít timeline: nếu raw dài hơn slot thì tăng rate Edge (+x%)
 *   rồi tải lại (tối đa 2 lần), giống render lại ffmpeg.
 */
public class EdgeVoiceEngine {

    public interface Listener {
        void onReady(int index, int cached, int total);
        void onError(int index, String msg);
        void onSpeakStart(int index);
        void onSpeakDone(int index);
    }

    public static final String VOICE = EdgeProtocol.VOICE_HOAIMY;
    /** Preload 35 cau nhu PRELOAD_COUNT Python. */
    public static final int PRELOAD_COUNT = 35;
    /** Tran nhu MAX_SPEED Python. */
    public static final float MAX_SPEED = 2.5f;

    private final Context appCtx;
    private final Listener listener;
    private final File cacheDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Map<Integer, String> readyPath = new ConcurrentHashMap<>();
    private final Map<Integer, Float> rawDur = new ConcurrentHashMap<>();
    private final Map<String, Boolean> queued = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile List<SubtitleCue> subs =
            java.util.Collections.emptyList();
    private volatile float baseRate = 1.0f;
    private volatile int ttsVol = 100;
    private volatile boolean playing = false;
    private volatile int currentIndex = -1;
    private MediaPlayer player;
    private final Object playerLock = new Object();

    public EdgeVoiceEngine(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        this.cacheDir = new File(appCtx.getCacheDir(), "edge_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public void setSubtitles(List<SubtitleCue> s) {
        generation.incrementAndGet();
        subs = s == null ? java.util.Collections.emptyList() : s;
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        stopPlayer();
        currentIndex = -1;
    }

    public void setBaseRate(float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.5f) v = 2.5f;
        baseRate = v;
    }

    public void setTtsVolume(int p) {
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        ttsVol = p;
        synchronized (playerLock) {
            if (player != null) {
                try {
                    player.setVolume(p / 100f, p / 100f);
                } catch (Exception ignored) {}
            }
        }
    }

    public void setPlaying(boolean p) {
        playing = p;
        if (!p) stopPlayer();
    }

    public int getCurrentIndex() { return currentIndex; }

    /** Tong dung luong mp3 HoaiMy dang chiem (byte). */
    public long cacheBytes() {
        long total = 0;
        try {
            File[] fs = cacheDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) total += f.length();
                }
            }
        } catch (Exception ignored) {}
        return total;
    }

    /** Xoa sach mp3 HoaiMy da tai (nut Xoa cache trong app). */
    public int clearCache() {
        generation.incrementAndGet();
        stopPlayer();
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        int removed = 0;
        try {
            File[] fs = cacheDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    try {
                        if (f.isFile() && f.delete()) removed++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return removed;
    }

    private static String key(int index, String text) {
        try {
            String raw = VOICE + "|" + index + "|" + text;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "c" + index + "_" + text.length();
        }
    }

    private File pathFor(int index, String text) {
        return new File(cacheDir, key(index, text) + ".mp3");
    }

    /** Preload 35 cau tu index (port preload_from Python). */
    public void preloadFrom(int index) {
        List<SubtitleCue> s = subs;
        if (s.isEmpty() || index < 0) return;
        int gen = generation.get();
        int end = Math.min(s.size(), index + PRELOAD_COUNT + 1);
        for (int i = index; i < end; i++) {
            final int idx = i;
            String qk = gen + ":" + idx;
            if (readyPath.containsKey(idx) || queued.containsKey(qk)) continue;
            queued.put(qk, true);
            pool.execute(() -> fetch(idx, gen));
        }
    }

    private void fetch(int index, int gen) {
        try {
            List<SubtitleCue> s = subs;
            if (index < 0 || index >= s.size()) return;
            if (gen != generation.get()) return;
            SubtitleCue cue = s.get(index);
            File f = pathFor(index, cue.text);
            if (f.exists() && f.length() > 0) {
                float d = probeDuration(f.getAbsolutePath());
                if (d > 0) {
                    rawDur.put(index, d);
                    readyPath.put(index, f.getAbsolutePath());
                    if (listener != null) {
                        listener.onReady(index, readyPath.size(),
                                Math.min(s.size(), PRELOAD_COUNT + 1));
                    }
                    return;
                } else {
                    f.delete();
                }
            }
            EdgeTtsClient.synthesize(cue.text, VOICE, "+0%", new EdgeTtsClient.Callback() {
                @Override public void onAudio(byte[] mp3) {
                    if (gen != generation.get()) return;
                    try {
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(mp3);
                        out.close();
                    } catch (Exception e) {
                        if (listener != null) listener.onError(index, e.getMessage());
                        return;
                    }
                    float d = probeDuration(f.getAbsolutePath());
                    if (d <= 0) {
                        f.delete();
                        if (listener != null) listener.onError(index, "mp3 loi");
                        return;
                    }
                    rawDur.put(index, d);
                    readyPath.put(index, f.getAbsolutePath());
                    if (listener != null) {
                        listener.onReady(index, readyPath.size(),
                                Math.min(subs.size(), PRELOAD_COUNT + 1));
                    }
                }
                @Override public void onError(String msg) {
                    if (listener != null) listener.onError(index, msg);
                }
            });
        } finally {
            queued.remove(gen + ":" + index);
        }
    }

    private static float probeDuration(String path) {
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(path);
            mp.prepare();
            return mp.getDuration() / 1000.0f;
        } catch (Exception e) {
            return 0;
        } finally {
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Doc 1 cau theo slot. Port create_speed_audio Python:
     * speed = raw/slot kep 1..2.5.
     * FIX tre 1 nhip: prepare() DONG BO truoc (mp3 local ~10-50ms) roi
     * start() ngay, khong doi prepareAsync callback. Nhu vay tai thoi
     * diem sub nao la keu cau do, khong tre sang cau sau.
     * Neu mp3 chua tai xong: tra false de ticker giu cau, preload gap.
     */
    public boolean speak(int index, double slotSec, int stream) {
        List<SubtitleCue> s = subs;
        if (index < 0 || index >= s.size()) return false;
        String path = readyPath.get(index);
        if (path == null) {
            // Chua tai xong: queue gap + preload (port queue_tts priority=0).
            preloadFrom(index);
            return false;
        }
        currentIndex = index;
        Float raw = rawDur.get(index);
        float speed = 1.0f;
        if (raw != null && raw > 0 && slotSec > 0.05) {
            speed = raw / (float) slotSec;
            if (speed < baseRate) speed = baseRate;
            if (speed > MAX_SPEED) speed = MAX_SPEED;
        } else if (baseRate > 1.0f) {
            speed = baseRate;
        }
        try {
            synchronized (playerLock) {
                stopPlayerLocked();
                player = new MediaPlayer();
                player.setAudioStreamType(stream);
                player.setDataSource(path);
                player.setVolume(ttsVol / 100f, ttsVol / 100f);
                // prepare dong bo: mp3 local nen nhanh, start ngay.
                player.prepare();
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 23
                            && Math.abs(speed - 1.0f) > 0.01) {
                        player.setPlaybackParams(
                                player.getPlaybackParams().setSpeed(speed));
                    }
                } catch (Exception ignored) {}
                player.setOnCompletionListener(mp -> {
                    if (listener != null) listener.onSpeakDone(index);
                });
                player.start();
                if (listener != null) listener.onSpeakStart(index);
            }
            return true;
        } catch (Exception e) {
            if (listener != null) listener.onError(index, e.getMessage());
            return false;
        }
    }

    private void stopPlayerLocked() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    public void stopPlayer() {
        synchronized (playerLock) {
            stopPlayerLocked();
        }
        currentIndex = -1;
    }

    public int readyCount() { return readyPath.size(); }

    public void shutdown() {
        generation.incrementAndGet();
        stopPlayer();
        pool.shutdownNow();
    }
}
