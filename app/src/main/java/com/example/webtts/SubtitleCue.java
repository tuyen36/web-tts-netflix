package com.example.webtts;

/** 1 cue phu de: start/end (giay) + text da lam sach. Port tu dict Python. */
public class SubtitleCue {
    public final double start;
    public final double end;
    public final String text;

    public SubtitleCue(double start, double end, String text) {
        this.start = start;
        this.end = end;
        this.text = text == null ? "" : text;
    }

    public double duration() {
        return Math.max(0.05, end - start);
    }
}
