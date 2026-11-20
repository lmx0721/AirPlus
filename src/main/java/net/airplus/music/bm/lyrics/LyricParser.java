package net.airplus.music.bm.lyrics;

import net.airplus.music.bm.model.Lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the lyric formats NetEase returns: plain LRC ({@code [mm:ss.xx]text}, several time tags
 * per line allowed), the translation in the same format, and "yrc" word-timed lyrics
 * ({@code [lineStart,lineDur](wordStart,wordDur,0)word...}, milliseconds). Credit lines that
 * NetEase embeds as JSON ({@code {"t":0,"c":[...]}}) and LRC metadata tags are skipped.
 */
public final class LyricParser {
    private static final Pattern LRC_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)");
    /** How long the last line stays up when nothing follows it. */
    private static final long LAST_LINE_MS = 6000;
    /** A translation belongs to the main line starting within this distance. */
    private static final long TRANSLATION_SLACK_MS = 1200;

    private LyricParser() {
    }

    /**
     * @param lrc         plain LRC (may be empty)
     * @param translation translated LRC (may be empty)
     * @param yrc         word-timed lyrics (may be empty); preferred over {@code lrc} when present
     */
    public static Lyrics parse(String lrc, String translation, String yrc) {
        List<Lyrics.Line> lines = isBlank(yrc) ? Collections.<Lyrics.Line>emptyList() : parseYrc(yrc);
        boolean wordTimed = !lines.isEmpty();
        if (!wordTimed) lines = parseLrc(lrc);
        if (lines.isEmpty()) return Lyrics.EMPTY;
        TreeMap<Long, String> tr = timedText(translation);
        if (!tr.isEmpty()) {
            List<Lyrics.Line> merged = new ArrayList<>(lines.size());
            for (Lyrics.Line line : lines) {
                String t = nearest(tr, line.startMs);
                merged.add(t == null || t.equals(line.text) ? line : line.withTranslation(t));
            }
            lines = merged;
        }
        return new Lyrics(lines, wordTimed);
    }

    /** LRC lines with durations filled in from the next line's start. */
    public static List<Lyrics.Line> parseLrc(String lrc) {
        TreeMap<Long, String> timed = timedText(lrc);
        List<Lyrics.Line> lines = new ArrayList<>(timed.size());
        Long[] starts = timed.keySet().toArray(new Long[0]);
        for (int i = 0; i < starts.length; i++) {
            long start = starts[i];
            long end = i + 1 < starts.length ? starts[i + 1] : start + LAST_LINE_MS;
            String text = timed.get(start);
            if (text.isEmpty()) continue; // an empty line only marks a gap: nothing is shown there
            lines.add(new Lyrics.Line(start, Math.max(1, end - start), text, "", null));
        }
        return lines;
    }

    static List<Lyrics.Line> parseYrc(String yrc) {
        List<Lyrics.Line> lines = new ArrayList<>();
        for (String raw : yrc.split("\\r?\\n")) {
            String row = raw.trim();
            if (row.isEmpty() || row.startsWith("{")) continue;
            Matcher m = YRC_LINE.matcher(row);
            if (!m.matches()) continue;
            long start = Long.parseLong(m.group(1)), dur = Long.parseLong(m.group(2));
            String body = m.group(3);
            List<Lyrics.Word> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Matcher w = YRC_WORD.matcher(body);
            int textFrom = -1;
            long ws = 0, wd = 0;
            while (w.find()) {
                if (textFrom >= 0) addWord(words, text, ws, wd, body.substring(textFrom, w.start()));
                ws = Long.parseLong(w.group(1));
                wd = Long.parseLong(w.group(2));
                textFrom = w.end();
            }
            if (textFrom >= 0) addWord(words, text, ws, wd, body.substring(textFrom));
            if (text.toString().trim().isEmpty()) continue;
            lines.add(new Lyrics.Line(start, dur, text.toString(), "", words));
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.startMs, b.startMs));
        return lines;
    }

    private static void addWord(List<Lyrics.Word> words, StringBuilder text, long start, long dur, String word) {
        if (word.isEmpty()) return;
        words.add(new Lyrics.Word(start, dur, word));
        text.append(word);
    }

    /** Start time → text for every timed LRC line (one line may carry several tags). */
    private static TreeMap<Long, String> timedText(String lrc) {
        TreeMap<Long, String> out = new TreeMap<>();
        if (isBlank(lrc)) return out;
        for (String raw : lrc.split("\\r?\\n")) {
            String row = raw.trim();
            if (row.isEmpty() || row.startsWith("{")) continue;
            Matcher m = LRC_TAG.matcher(row);
            List<Long> times = new ArrayList<>(2);
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1)), sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue; // metadata such as [ar:...] or an untimed line
            String text = row.substring(end).trim();
            for (Long t : times) out.put(t, text);
        }
        return out;
    }

    private static String nearest(TreeMap<Long, String> map, long at) {
        Map.Entry<Long, String> lo = map.floorEntry(at), hi = map.ceilingEntry(at);
        Map.Entry<Long, String> best = lo;
        if (hi != null && (best == null || hi.getKey() - at < at - best.getKey())) best = hi;
        if (best == null || Math.abs(best.getKey() - at) > TRANSLATION_SLACK_MS) return null;
        String t = best.getValue();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
