package studio.fxc.djlink.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The song structure (phrase analysis) of one track. */
public final class PhraseData {
    /** One phrase. */
    public static final class Entry {
        public final int startBeat;
        public final String rawKind;
        public final String label;
        public final boolean fill;
        public final int fillBeat;

        public Entry(int startBeat, String rawKind, String label, boolean fill, int fillBeat) {
            this.startBeat = startBeat;
            this.rawKind = rawKind;
            this.label = label;
            this.fill = fill;
            this.fillBeat = fill ? fillBeat : 0;
        }
    }

    public final int trackId;
    public final String mood;
    public final String bank;
    public final int endBeat;
    public final List<Entry> entries;

    public PhraseData(int trackId, String mood, String bank, int endBeat, List<Entry> entries) {
        this.trackId = trackId;
        this.mood = mood;
        this.bank = bank == null ? "" : bank;
        this.endBeat = endBeat;
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> e.startBeat));
        this.entries = Collections.unmodifiableList(sorted);
    }
}
