package com.example.webtts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Port DRM + SSML tu edge-tts Python (drm.py + communicate.py).
 *
 * - WSS_URL: speech.platform.bing.com/consumer/speech/synthesize/readaloud
 * - Sec-MS-GEC = SHA256(winTicks + TRUSTED_CLIENT_TOKEN), lam tron 5 phut.
 * - SSML: <speak><voice name><prosody rate/volume>text</prosody></voice></speak>
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
            "1-" + CHROMIUM_FULL_VERSION;

    public static final String VOICE_HOAIMY = "vi-VN-HoaiMyNeural";

    private static final long WIN_EPOCH = 11644473600L;

    /** Port DRM.generate_sec_ms_gec(). */
    public static String generateSecMsGec() {
        try {
            long unixSec = System.currentTimeMillis() / 1000L;
            long ticks = unixSec + WIN_EPOCH;
            ticks -= ticks % 300;
            long winTicks = ticks * 10000000L;
            String raw = winTicks + TRUSTED_CLIENT_TOKEN;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Port date_to_string(): "Mon Oct 11 2026 12:00:00 GMT+0000 (UTC)". */
    public static String dateToString() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat(
                        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                        Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    /** Port mkssml(). rate dang "+0%" / "+20%" ... */
    public static String mkssml(String voice, String rate, String text) {
        String esc = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
        // Loai ky tu dieu khien nhu remove_incompatible_characters().
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
