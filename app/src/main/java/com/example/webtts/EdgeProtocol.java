package com.example.webtts;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Port DRM + SSML tu edge-tts Python (drm.py master 10/2026).
 *
 * V2 (10/2026): Microsoft bat buoc Sec-MS-GEC v2, token cu v1 (64 hex)
 * bi 403. V2 = CS64 + SHA256 dang "<16 hex>-<64 hex>", version "2-...".
 * Xem rany2/edge-tts PR #492. Vector verify:
 * generateSecMsGecV2(1791888000) =
 * '492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876'
 */
public class EdgeProtocol {

    public static final String BASE_URL =
            "speech.platform.bing.com/consumer/speech/synthesize/readaloud";
    public static final String TRUSTED_CLIENT_TOKEN =
            "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    public static final String WSS_URL =
            "wss://" + BASE_URL + "/edge/v1?TrustedClientToken="
                    + TRUSTED_CLIENT_TOKEN;

    public static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    public static final String CHROMIUM_MAJOR_VERSION = "143";
    public static final String SEC_MS_GEC_VERSION =
            "2-" + CHROMIUM_FULL_VERSION;

    public static final String VOICE_HOAIMY = "vi-VN-HoaiMyNeural";

    private static final double WIN_EPOCH = 11644473600.0;
    private static final long MASK = 0xFFFFFFFFL;

    private static final long[] SHA256_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L };
    private static final long[] SHA256_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L };
    private static final long[] SHA256_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L };
    private static final long[] SHA256_ROUNDS = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L };
    private static final long SHA256_ADJ = 0xC9DC06A7L;
    private static final long SIGMA0_ADJ = 0x059C0B80L;
    private static final long SIGMA1_ADJ = 0x000B92C0L;
    private static final long[][] CS64_COEFF = {
            {0xE82E91B1L, 0xB32700D0L}, {0x44DE24C6L, 0x88DFF51DL},
            {0x3BEB65BCL, 0x29514038L}, {0xC89C5933L, 0xB328CBDFL},
            {0x8D1EC211L, 0xD405B4D5L}, {0x314EF5D5L, 0x26E55F3CL},
            {0xE92491B1L, 0x607801C6L}, {0xF0A5B9D5L, 0x82B2C1FBL},
            {0x06064CF3L, 0xFA51A3DAL}, {0xDA79FA60L, 0x207CC464L},
            {0xBED20560L, 0x3C3C74F3L}, {0x89F9C9F8L, 0xD2D14B61L},
            {0x35FA38C6L, 0xBB04A2C1L}, {0xCB49E3C6L, 0xC5626167L} };
    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJ0 = 0xF2F90B1AL;
    private static final long CS64_ADJ1 = 0x0B1A0000L;

    private static byte[] cacheInitKey;
    private static long[] cacheInitVal;
    private static byte[] cacheHashKey;
    private static String cacheHashCs64;
    private static String cacheHashSha;

    private static long mul32(long a, long b) {
        return Integer.toUnsignedLong((int) a * (int) b);
    }

    private static long rol16(long v) {
        v &= MASK;
        return ((v << 16) | (v >>> 16)) & MASK;
    }

    private static long rep64(long v) {
        v &= MASK;
        return (v << 32) | v;
    }

    private static List<Long> wordsLe4Even(byte[] data) {
        int pad = (-data.length) & 3;
        int total = data.length + pad;
        List<Long> out = new ArrayList<>((total + 3) / 4 + 1);
        for (int i = 0; i < total; i += 4) {
            long w = 0;
            for (int k = 0; k < 4; k++) {
                int idx = i + k;
                int b = idx < data.length ? (data[idx] & 0xFF) : 0;
                w |= ((long) b) << (8 * k);
            }
            out.add(w & MASK);
        }
        if (out.size() % 2 == 1) out.add(0L);
        return out;
    }

    private static long sigma0(long v) {
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & MASK;
    }

    private static long sigma1(long v) {
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & MASK;
    }

    /**
     * Ban khop 100% Python: t1,t2 giu full 64-bit roi moi & MASK khi gan.
     * Dung ban nay cho ca init (firstRound=14) va digest (firstRound=0).
     */
    private static long[] shaRoundsExact(long[] st, List<Long> words, int firstRound) {
        long a = st[0] & MASK, b = st[1] & MASK, c = st[2] & MASK, d = st[3] & MASK;
        long e = st[4] & MASK, f = st[5] & MASK, g = st[6] & MASK, h = st[7] & MASK;
        for (int i = firstRound; i < 64; i++) {
            long cn = (firstRound == 14 && i == 14) ? SHA256_ADJ : SHA256_ROUNDS[i];
            long rotE = rep64(e);
            long rotA = rep64(a);
            long t1 = h + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (g ^ (e & (f ^ g))) + cn + (words.get(i) & MASK);
            long t2 = ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22))
                    + ((a & b) | (c & (a | b)));
            long na = (t1 + t2) & MASK;
            long ne = (d + t1) & MASK;
            h = g & MASK; g = f & MASK; f = e & MASK; e = ne;
            d = c & MASK; c = b & MASK; b = a & MASK; a = na;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static void expandSchedule(List<Long> words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = words.get(i - 2) & MASK, y = words.get(i - 15) & MASK;
            long s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words.add((s1 + (words.get(i - 7) & MASK) + s0 + (words.get(i - 16) & MASK)) & MASK);
        }
    }

    private static synchronized long[] sha256Init(byte[] data7) {
        if (cacheInitKey != null && java.util.Arrays.equals(cacheInitKey, data7)) {
            return cacheInitVal.clone();
        }
        List<Long> w = new ArrayList<>(64);
        for (int i = 0; i < 14; i++) w.add(0L);
        long u24 = (((long) (data7[0] & 0xFF)) << 16)
                | (((long) (data7[1] & 0xFF)) << 8) | ((long) (data7[2] & 0xFF));
        long u32 = (((long) (data7[3] & 0xFF)) << 24)
                | (((long) (data7[4] & 0xFF)) << 16)
                | (((long) (data7[5] & 0xFF)) << 8) | ((long) (data7[6] & 0xFF));
        w.add(u24 & MASK);
        w.add(u32 & MASK);
        for (int i = 16; i < 30; i++) {
            long v = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                long s = sigma1(w.get(i - 2));
                if (i == 16) s ^= SIGMA1_ADJ;
                v = (v + s) & MASK;
            }
            if (i - 7 >= 14) v = (v + w.get(i - 7)) & MASK;
            if (i - 15 >= 14) {
                long s = sigma0(w.get(i - 15));
                if (i == 29) s ^= SIGMA0_ADJ;
                v = (v + s) & MASK;
            }
            w.add(v & MASK);
        }
        w.add((sigma1(w.get(28)) + w.get(23) + sigma0(w.get(15)) + w.get(14)
                + SHA256_ADJ - SHA256_ROUNDS[14]) & MASK);
        expandSchedule(w, 31);
        long[] wk = shaRoundsExact(SHA256_STATE, w, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = (SHA256_INITIAL[i] + wk[i]) & MASK;
        cacheInitKey = data7.clone();
        cacheInitVal = out.clone();
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("salt>=7");
        int len = 57 + data.length;
        int padZeros = ((55 - len) % 64 + 64) % 64;
        int total = (data.length - 7) + 1 + padZeros + 8;
        byte[] padded = new byte[total];
        int p = 0;
        for (int i = 7; i < data.length; i++) padded[p++] = data[i];
        padded[p++] = (byte) 0x80;
        p += padZeros;
        long bitLen = ((long) len) * 8L;
        for (int i = 7; i >= 0; i--) {
            padded[p++] = (byte) ((bitLen >>> (8 * i)) & 0xFF);
        }
        byte[] head7 = new byte[7];
        System.arraycopy(data, 0, head7, 0, 7);
        long[] state = sha256Init(head7);
        for (int off = 0; off < padded.length; off += 64) {
            List<Long> w = new ArrayList<>(64);
            for (int i = 0; i < 16; i++) {
                int o = off + i * 4;
                w.add(((((long) (padded[o] & 0xFF)) << 24)
                        | (((long) (padded[o + 1] & 0xFF)) << 16)
                        | (((long) (padded[o + 2] & 0xFF)) << 8)
                        | ((long) (padded[o + 3] & 0xFF))) & MASK);
            }
            expandSchedule(w, 16);
            long[] wk = shaRoundsExact(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] = (state[i] + wk[i]) & MASK;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) ((state[i] >>> 24) & 0xFF);
            out[i * 4 + 1] = (byte) ((state[i] >>> 16) & 0xFF);
            out[i * 4 + 2] = (byte) ((state[i] >>> 8) & 0xFF);
            out[i * 4 + 3] = (byte) (state[i] & 0xFF);
        }
        return out;
    }

    private static long[] cs64Digest(byte[] data, byte[] digest32) {
        long keyA = (((long) (digest32[0] & 0xFF))
                | (((long) (digest32[1] & 0xFF)) << 8)
                | (((long) (digest32[2] & 0xFF)) << 16)
                | (((long) (digest32[3] & 0xFF)) << 24)) & MASK;
        long keyB = (((long) (digest32[4] & 0xFF))
                | (((long) (digest32[5] & 0xFF)) << 8)
                | (((long) (digest32[6] & 0xFF)) << 16)
                | (((long) (digest32[7] & 0xFF)) << 24)) & MASK;
        keyA |= 1;
        keyB |= 1;
        long f0 = mul32(keyA, CS64_FACTOR);
        long f1 = mul32(keyB, CS64_FACTOR);
        List<Long> terms = new ArrayList<>();
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < CS64_COEFF.length; i++) {
            terms.add(mul32(CS64_COEFF[i][0], (i % 2 == 0) ? f0 : f1));
            offs.add(CS64_COEFF[i][1] & MASK);
        }
        byte[] pre = new byte[data.length + 1];
        pre[0] = 0;
        System.arraycopy(data, 0, pre, 1, data.length);
        List<Long> words = wordsLe4Even(pre);
        for (int i = 0; i < words.size(); i++) {
            long word = words.get(i) & MASK;
            long key = (i % 2 == 0) ? keyA : keyB;
            long term = mul32(word, key);
            long off = mul32(rol16(word),
                    (i % 2 == 0) ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term = (term + mul32(CS64_ADJ0, f0)) & MASK;
                off = (off + CS64_ADJ1) & MASK;
            }
            terms.add(term & MASK);
            offs.add(off & MASK);
        }
        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < terms.size(); i += 2) {
            long x = terms.get(i) & MASK, y = terms.get(i + 1) & MASK;
            long t = rol16((offs.get(i) + x) & MASK);
            long q = mul32(t, 0x76F50F34L);
            swapAcc = (swapAcc + ((mul32(rol16(q), 0xC3EFEA97L) + q) & MASK)) & MASK;
            t = rol16((offs.get(i + 1) + y) & MASK);
            q = mul32(t, 0x1881CE3CL);
            swapLast = (mul32(rol16(q), 0x35BD1EC9L) + q) & MASK;
            swapAcc = (swapAcc + swapLast) & MASK;
            state = (mul32(state, keyA) + x) & MASK;
            state = mul32(rol16(state), 0xCF98B111L);
            state = mul32(rol16(state), 0x87085B9FL);
            state = mul32(rol16(state), 0x12CEB96DL);
            state = mul32(rol16(state), 0x257E1D83L);
            acc = (acc + state) & MASK;
            t = (mul32(state, keyB) + y) & MASK;
            t = mul32(rol16(t), 0xA27416F5L);
            t = mul32(rol16(t), 0xD38396FFL);
            state = mul32(rol16(mul32(rol16(t), 0x7C932B89L)), 0xBFA49F69L);
            acc = (acc + state) & MASK;
        }
        return new long[]{(swapLast ^ state) & MASK, (swapAcc ^ acc) & MASK};
    }

    private static synchronized String[] tokenHash(byte[] s) {
        if (cacheHashKey != null && java.util.Arrays.equals(cacheHashKey, s)) {
            return new String[]{cacheHashCs64, cacheHashSha};
        }
        byte[] digest = sha256Digest(s);
        long[] cs = cs64Digest(s, digest);
        StringBuilder csSb = new StringBuilder();
        for (long v : cs) {
            for (int i = 0; i < 4; i++) {
                csSb.append(String.format(Locale.US, "%02X", (v >>> (8 * i)) & 0xFF));
            }
        }
        StringBuilder shSb = new StringBuilder();
        for (byte b : digest) shSb.append(String.format(Locale.US, "%02X", b & 0xFF));
        cacheHashKey = s.clone();
        cacheHashCs64 = csSb.toString();
        cacheHashSha = shSb.toString();
        return new String[]{cacheHashCs64, cacheHashSha};
    }

    /** V2 cho timestamp tuy y (dung de test vector PR #492). */
    public static String generateSecMsGecV2(double unixSec) {
        double ticks = unixSec + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 10000000.0;
        String raw = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN;
        byte[] s = raw.getBytes(StandardCharsets.US_ASCII);
        String[] hh = tokenHash(s);
        return hh[0] + "-" + hh[1];
    }

    /** Port DRM.generate_sec_ms_gec() V2. Giu float path giong Python. */
    public static String generateSecMsGec() {
        try {
            double unixSec = System.currentTimeMillis() / 1000.0;
            return generateSecMsGecV2(unixSec);
        } catch (Exception e) {
            return "";
        }
    }

    public static String generateMuid() {
        try {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US);
        }
    }

    public static String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String dateToString() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat(
                        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                        Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    public static String mkssml(String voice, String rate, String text) {
        String esc = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
        StringBuilder sb = new StringBuilder(esc.length());
        for (int i = 0; i < esc.length(); i++) {
            char c = esc.charAt(i);
            if ((c >= 0 && c <= 8) || c == 11 || c == 12
                    || (c >= 14 && c <= 31)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
                + sb.toString()
                + "</prosody></voice></speak>";
    }

    public static String speechConfigRequest() {
        return "X-Timestamp:" + dateToString() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"true\","
                + "\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    public static String ssmlRequest(String ssml) {
        return "X-RequestId:" + connectId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + dateToString() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
    }

    public static String wsUrl() {
        return WSS_URL + "&ConnectionId=" + connectId()
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
    }
}
