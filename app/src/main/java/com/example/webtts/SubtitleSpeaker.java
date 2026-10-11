package com.example.webtts;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dieu khien Google TTS he thong de doc phu de theo timeline video.
 *
 * V1.6 FIX tre luy tien — hoc dung triet ly file Python:
 * - Python do raw_duration THAT bang ffprobe roi speed = raw/target.
 * - Android khong co ffmpeg: TU DO toc do that lan dau gap cau
 *   (onStart->onDone o rate 1.0x), luu theo do dai chu (~chars).
 *   Lan sau: estimated = chars * secPerChar -> speed = estimated/slot.
 * - Chua do kip: dung he so an toan 8 chu/giay (cham hon 11 cu de
 *   tranh tinh thieu toc do gay lo slot).
 * - Van kep 1.0..2.5 nhu MAX_SPEED Python, chi tang khong giam.
 */
public class SubtitleSpeaker {

    public interface Listener {
        void onSpeakStart(int index);
        void onSpeakDone(int index);
        void onTtsError(int index);
    }

    /** Callback hien toc do thuc te len UI. */
    public interface RateDisplay {
        void onRate(float effectiveRate);
    }

    private final TextToSpeech tts;
    private final Listener listener;
    private volatile RateDisplay rateDisplay;

    /** Offset giay: + doc truoc phu de, - doc sau. Port tu audio_offset. */
    private volatile float audioOffset = 0f;
    /** Nguong seek giay. Port tu SEEK_THRESHOLD = 1.2. */
    private volatile float seekThreshold = 1.2f;
    /** Toc do doc 0.5 - 2.5. Thay cho ffmpeg atempo MAX_SPEED=2.5. */
    private volatile float speechRate = 1.0f;
    /** Tran tu dong tang toc cho cau dai (port MAX_SPEED Python). */
    private static final float MAX_AUTO_RATE = 2.5f;
    /**
     * He so D PHONG khi chua do that: 8 chu/giay (cham hon 11 cu).
     * Tinh du toc do de tranh lo slot gay tre luy tien.
     */
    private static final float SAFE_CHARS_PER_SEC = 8.0f;
    private volatile float lastEffectiveRate = 1.0f;
    /**
     * Volume rieng cua giong doc sub (0.0 - 1.0). Dung de noi giong sub
     * khi chay song song Netflix (van chung STREAM_MUSIC, xem GHI CHU
     * trong TtsPlaybackService ve gioi han Android).
     */
    private volatile float ttsVolume = 1.0f;
    /** PARAM_STREAM (API 21+): tach sub ra khoi stream media cua Netflix. */
    private volatile int ttsStream = android.media.AudioManager.STREAM_MUSIC;

    // ----- tu hieu chinh toc do that (thay ffprobe) -----
    /**
     * So giay de doc 1 ky tu o rate 1.0x, hoc tu lan doc that dau tien.
     * Mac dinh = 1/8s (he so an toan). Sau vai cau se hoi tu ve dung
     * toc do that cua giong Google TTS tren may user.
     */
    private volatile float secPerCharAt1x = 1.0f / SAFE_CHARS_PER_SEC;
    /** Thoi diem bat dau doc cau hien tai (do that). */
    private volatile long speakStartMs = 0;
    private volatile int speakStartChars = 0;
    private volatile float speakStartRate = 1.0f;
    /** Khoa hoc de tranh do sai khi speak chong nhau. */
    private final Object calibLock = new Object();
    /** Luu them theo bucket do dai de on dinh hon (tuy chon). */
    private final ConcurrentHashMap<Integer, Float> bucketSecPerChar =
            new ConcurrentHashMap<>();

    private volatile List<SubtitleCue> subtitles =
            java.util.Collections.emptyList();
    private volatile int currentIndex = -1;
    private volatile double lastPosition = -1;
    private volatile boolean playing = false;

    private final AtomicInteger utterSeq = new AtomicInteger(0);

    public SubtitleSpeaker(TextToSpeech tts, Listener listener) {
        this.tts = tts;
        this.listener = listener;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                int idx = idxOf(id);
                if (listener != null && idx >= 0) listener.onSpeakStart(idx);
            }
            @Override public void onDone(String id) {
                int idx = idxOf(id);
                onUtterDone();
                if (listener != null && idx >= 0) listener.onSpeakDone(idx);
            }
            @Override public void onError(String id) {
                int idx = idxOf(id);
                if (listener != null && idx >= 0) listener.onTtsError(idx);
            }
        });
    }

    /**
     * Hoc toc do that: lay thoi gian doc that vua xong o rate R,
     * quy ve rate 1.0x: secPerChar = elapsed * R / chars.
     * Chi hoc khi cau doc tron ven (>= 0.4s, <= 30s, rate hop le).
     */
    private void onUtterDone() {
        long start;
        int chars;
        float rateUsed;
        synchronized (calibLock) {
            start = speakStartMs;
            chars = speakStartChars;
            rateUsed = speakStartRate;
            speakStartMs = 0;
        }
        if (start <= 0 || chars < 5) return;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < 400 || elapsed > 30000) return;
        if (rateUsed < 0.5f || rateUsed > 2.6f) return;
        float spc = (elapsed / 1000.0f) * rateUsed / chars;
        // Loc nhieu: chi nhan gia tri 0.03..0.5s/ky tu (2..33 chu/giay).
        if (spc < 0.03f || spc > 0.5f) return;
        synchronized (calibLock) {
            // Trung binh truot: 70% cu + 30% moi de on dinh.
            secPerCharAt1x = secPerCharAt1x * 0.7f + spc * 0.3f;
            int bucket = (chars / 20) * 20;
            Float old = bucketSecPerChar.get(bucket);
            bucketSecPerChar.put(bucket,
                    old == null ? spc : old * 0.7f + spc * 0.3f);
        }
    }

    private static int idxOf(String utteranceId) {
        try {
            // dinh dang "webtts-<index>-<seq>"
            String[] p = utteranceId.split("-");
            return Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    public void setSubtitles(List<SubtitleCue> subs) {
        subtitles = subs == null
                ? java.util.Collections.emptyList() : subs;
        currentIndex = -1;
        lastPosition = -1;
        stop();
    }

    /** Chi reset khi la list moi (tranh mat vi tri khi poll lien tuc). */
    public void setSubtitlesIfNew(List<SubtitleCue> subs) {
        if (subs != subtitles) setSubtitles(subs);
    }

    public void setAudioOffset(float v) { audioOffset = v; }
    public float getAudioOffset() { return audioOffset; }
    public void setSeekThreshold(float v) { seekThreshold = v; }

    /** Doi toc do doc + ap dung ngay vao TTS he thong. */
    public void setSpeechRate(float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.5f) v = 2.5f;
        speechRate = v;
        try {
            tts.setSpeechRate(v);
        } catch (Exception ignored) {}
    }
    public float getSpeechRate() { return speechRate; }

    /** Toc do thuc te vua ap cho cau gan nhat (de UI hien thi). */
    public float getLastEffectiveRate() { return lastEffectiveRate; }

    public void setRateDisplay(RateDisplay d) { rateDisplay = d; }

    /** Volume giong doc 0..100% (rieng, khong anh huong app Netflix). */
    public void setTtsVolume(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        ttsVolume = percent / 100.0f;
    }
    public int getTtsVolume() { return Math.round(ttsVolume * 100); }

    /** Stream phat giong doc: MUSIC (chung) hoac ALARM (tach khoi Netflix). */
    public void setTtsStream(int stream) { ttsStream = stream; }
    public int getTtsStream() { return ttsStream; }

    /**
     * Tinh rate can thiet de doc het text trong slot giay.
     * Giong create_speed_audio() Python: speed = raw/target, kep 1..2.5.
     * raw o day = uoc luong tu toc do THAT da hoc (thay ffprobe).
     */
    public float rateFor(String text, double slotSec) {
        if (text == null || text.isEmpty()) return speechRate;
        if (slotSec <= 0.05) return MAX_AUTO_RATE;
        int chars = text.codePointCount(0, text.length());
        float spc;
        synchronized (calibLock) {
            int bucket = (chars / 20) * 20;
            Float b = bucketSecPerChar.get(bucket);
            spc = (b != null) ? b : secPerCharAt1x;
        }
        float estimated = chars * spc;
        float need = estimated / (float) slotSec;
        float base = speechRate <= 0 ? 1.0f : speechRate;
        float want = base * need;
        // Chi tang toc, khong giam: cau ngan giu giong doc tu nhien.
        if (want < base) want = base;
        if (want > MAX_AUTO_RATE) want = MAX_AUTO_RATE;
        return want;
    }

    public void setPlaying(boolean p) {
        boolean was = playing;
        playing = p;
        if (was && !p) {
            stop();
        }
    }

    public void stop() {
        try {
            tts.stop();
        } catch (Exception ignored) {}
        currentIndex = -1;
        synchronized (calibLock) {
            speakStartMs = 0;
        }
    }

    /** Cat cau cu, bat buoc doc lai cau tai position ke tiep (dung khi Play/nudge). */
    public void resetIndex() {
        try {
            tts.stop();
        } catch (Exception ignored) {}
        currentIndex = -1;
    }

    public int getCurrentIndex() { return currentIndex; }

    /**
     * Goi moi khi biet vi tri video hien tai (giay).
     * - Che do thu cong: user bam Play/Pause + keo SeekBar trong app.
     * - Che do WebView: goi tu onProgressChanged / JS hook.
     */
    public void onPosition(double position) {
        List<SubtitleCue> subs = subtitles;
        if (subs.isEmpty()) return;

        // Phat hien seek dot ngot -> huy cau cu (port handle_seek).
        if (lastPosition >= 0
                && Math.abs(position - lastPosition) > seekThreshold
                && !(lastPosition > 60 && position < 3)) {
            // quang cao / chuyen doan: giu nguyen, khong coi la seek tay
            if (!(lastPosition > 60 && position < 3)) {
                stop();
            }
        }
        lastPosition = position;

        int idx = SubtitleParser.findIndex(subs, position + audioOffset);
        if (idx < 0) return;
        if (idx == currentIndex) return;

        currentIndex = idx;
        if (!playing) return;

        SubtitleCue cue = subs.get(idx);
        String text = cue.text;
        if (text.isEmpty()) return;

        // Slot con lai cua cau: tu position hien tai den end cue.
        // Port get_timeline() Python nhung tru phan da troi qua de tranh
        // doc cham dan o cau dai. Kep toi thieu 0.5s (thay vi 0.3) de
        // tranh tang toc dot ngot vo ly khi vao cuoi cau.
        double slot = cue.end - position - audioOffset;
        if (slot < 0.5) slot = 0.5;
        double cueLen = cue.duration();
        if (slot > cueLen) slot = cueLen;
        float effRate = rateFor(text, slot);
        lastEffectiveRate = effRate;
        RateDisplay rd = rateDisplay;
        if (rd != null) {
            try {
                rd.onRate(effRate);
            } catch (Exception ignored) {}
        }

        try {
            tts.setLanguage(new Locale("vi", "VN"));
        } catch (Exception ignored) {}
        try {
            tts.setSpeechRate(effRate);
        } catch (Exception ignored) {}

        try {
            tts.stop();
            String utterId = "webtts-" + idx + "-" + utterSeq.incrementAndGet();
            synchronized (calibLock) {
                speakStartMs = System.currentTimeMillis();
                speakStartChars = text.codePointCount(0, text.length());
                speakStartRate = effRate;
            }
            Bundle b = new Bundle();
            b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utterId);
            // Volume rieng giong doc (minSdk 29 nen API 21+ luon dung).
            b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);
            // Tach stream: ALARM thi phim cung media khong anh huong sub.
            b.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, ttsStream);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, utterId);
        } catch (Exception e) {
            if (listener != null) listener.onTtsError(idx);
        }
    }
}
