package com.rp.rptranscription.translation;

import java.util.ArrayList;
import java.util.List;

public final class TextSynchronizer {
    public static final class SegmentMapping {
        public final int sourceStart;
        public final int sourceEnd;
        public final int translationStart;
        public final int translationEnd;
        public SegmentMapping(int ss, int se, int ts, int te) {
            this.sourceStart = ss;
            this.sourceEnd = se;
            this.translationStart = ts;
            this.translationEnd = te;
        }
    }

    private final StringBuilder sourceBuffer = new StringBuilder();
    private final StringBuilder translationBuffer = new StringBuilder();
    private final List<SegmentMapping> mappings = new ArrayList<>();

    public synchronized void appendFinalSource(String text) {
        int ss = sourceBuffer.length();
        if (sourceBuffer.length() > 0) sourceBuffer.append('\n');
        sourceBuffer.append(text == null ? "" : text);
        int se = sourceBuffer.length();
        mappings.add(new SegmentMapping(ss, se, translationBuffer.length(), translationBuffer.length()));
    }

    public synchronized void appendFinalTranslation(String text) {
        int ts = translationBuffer.length();
        if (translationBuffer.length() > 0) translationBuffer.append('\n');
        translationBuffer.append(text == null ? "" : text);
        int te = translationBuffer.length();
        if (!mappings.isEmpty()) {
            SegmentMapping last = mappings.get(mappings.size() - 1);
            mappings.set(mappings.size() - 1, new SegmentMapping(last.sourceStart, last.sourceEnd, ts, te));
        }
    }

    public synchronized String getSourceText() {
        return sourceBuffer.toString();
    }

    public synchronized String getTranslationText() {
        return translationBuffer.toString();
    }

    public synchronized List<SegmentMapping> getMappings() {
        return new ArrayList<>(mappings);
    }

    public synchronized void reset() {
        sourceBuffer.setLength(0);
        translationBuffer.setLength(0);
        mappings.clear();
    }
}
