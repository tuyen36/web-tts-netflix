package com.example.webtts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lam sach text phu de + parse SRT.
 * Port tu clean_subtitle_text() / parse_srt() trong web_tts_v2_9_*.py.
 */
public class SubtitleParser {

    private static final Pattern HTML_TAG = Pattern.compile("<[^<>]+>");
    private static final Pattern ASS_TAG = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern ASS_BREAK = Pattern.compile("\\\\[Nnh]");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern TIME_RE =
            Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})");

    /** Go HTML/ASS tags, ky tu vo hinh, chuan hoa khoang trang. */
    public static String cleanText(String text) {
        if (text == null) return "";
        String s = text;
        s = unescapeHtml(s);
        s = ASS_TAG.matcher(s).replaceAll(" ");
        s = ASS_BREAK.matcher(s).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // \u200B-\u200F, \u202A-\u202E, \uFEFF
            if ((c >= 0x200B && c <= 0x200F)
                    || (c >= 0x202A && c <= 0x202E)
                    || c == 0xFEFF) {
                continue;
            }
            sb.append(c);
        }
        s = sb.toString().replace('\r', '\n');
        s = WS.matcher(s).replaceAll(" ");
        return s.trim();
    }

    private static String unescapeHtml(String s) {
        // Chi can cac entity pho bien trong phu de; tranh them lib.
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    public static double parseTime(String value) {
        Matcher m = TIME_RE.matcher(value);
        if (!m.find()) return 0.0;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        int sec = Integer.parseInt(m.group(3));
        int ms = Integer.parseInt(m.group(4));
        return h * 3600 + min * 60 + sec + ms / 1000.0;
    }

    /** Parse noi dung .srt thanh list cue da sap xep. */
    public static java.util.List<SubtitleCue> parseSrt(String content) {
        java.util.List<SubtitleCue> out = new java.util.ArrayList<>();
        if (content == null) return out;
        String[] blocks = content.trim().split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int timeIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timeIdx = i;
                    break;
                }
            }
            if (timeIdx < 0) continue;
            String[] parts = lines[timeIdx].split("-->");
            if (parts.length != 2) continue;
            double start = parseTime(parts[0]);
            double end = parseTime(parts[1]);
            if (end <= start) continue;
            StringBuilder tb = new StringBuilder();
            for (int i = timeIdx + 1; i < lines.length; i++) {
                tb.append(lines[i]).append('\n');
            }
            String text = cleanText(tb.toString());
            if (text.isEmpty()) continue;
            out.add(new SubtitleCue(start, end, text));
        }
        return out;
    }

    /** Tim cue dang phat tai position (nhi phan, port tu Python). */
    public static int findIndex(java.util.List<SubtitleCue> subs, double position) {
        int lo = 0, hi = subs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            SubtitleCue c = subs.get(mid);
            if (position < c.start) hi = mid - 1;
            else if (position >= c.end) lo = mid + 1;
            else return mid;
        }
        return -1;
    }
}
