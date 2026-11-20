package net.airplus.music.bm.model;

import java.util.Collections;
import java.util.List;

/**
 * Timed lyrics. Lines are sorted by start time; a line may carry per-word timing (NetEase "yrc")
 * for the word-by-word fill, and a translation.
 */
public final class Lyrics {
    public static final Lyrics EMPTY = new Lyrics(Collections.<Line>emptyList(), false);

    public final List<Line> lines;
    /** At least one line has per-word timing. */
    public final boolean wordTimed;

    public Lyrics(List<Line> lines, boolean wordTimed) {
        this.lines = Collections.unmodifiableList(lines);
        this.wordTimed = wordTimed;
    }

    public boolean isEmpty() {
        return this.lines.isEmpty();
    }

    /** Index of the line playing at {@code ms}, or -1 before the first line. */
    public int indexAt(long ms) {
        int lo = 0, hi = this.lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (this.lines.get(mid).startMs <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    public static final class Line {
        public final long startMs;
        /** How long the line is sung; for plain LRC, the gap to the next line. */
        public final long durationMs;
        public final String text;
        public final String translation;
        /** Per-word timing, empty when the line only has a start time. */
        public final List<Word> words;

        public Line(long startMs, long durationMs, String text, String translation, List<Word> words) {
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.text = text == null ? "" : text;
            this.translation = translation == null ? "" : translation;
            this.words = words == null ? Collections.<Word>emptyList() : Collections.unmodifiableList(words);
        }

        public Line withTranslation(String translation) {
            return new Line(this.startMs, this.durationMs, this.text, translation, this.words);
        }

        public Line withDuration(long durationMs) {
            return new Line(this.startMs, durationMs, this.text, this.translation, this.words);
        }

        public boolean wordTimed() {
            return !this.words.isEmpty();
        }

        /** Whether the line is being sung (or held) at {@code ms}. */
        public boolean activeAt(long ms) {
            return ms >= this.startMs && ms < this.startMs + Math.max(1, this.durationMs);
        }
    }

    public static final class Word {
        public final long startMs, durationMs;
        public final String text;

        public Word(long startMs, long durationMs, String text) {
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.text = text == null ? "" : text;
        }

        /** 0 before the word, 1 once sung, linear in between. */
        public float progress(long ms) {
            if (ms <= this.startMs) return 0;
            if (this.durationMs <= 0 || ms >= this.startMs + this.durationMs) return 1;
            return (ms - this.startMs) / (float) this.durationMs;
        }
    }
}
