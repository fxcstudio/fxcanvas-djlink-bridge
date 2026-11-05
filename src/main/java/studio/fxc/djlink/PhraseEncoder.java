package studio.fxc.djlink;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.osc.OscMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns rekordbox song structure into {@link PhraseData}, and {@link PhraseData}
 * into {@code /phrases/{player}} OSC chunks.
 *
 * Chunk layout (protocol 1):
 * {@code i trackId, s mood, s bank, i endBeat, i total, i offset, i count,}
 * then {@code count} times {@code i startBeat, s rawKind, s label, i fill, i fillBeat}.
 */
public final class PhraseEncoder {
    /** Phrases per OSC message. About 2.5 KB, well under the macOS 9216-byte UDP default. */
    public static final int CHUNK_SIZE = 64;

    private PhraseEncoder() {
    }

    /**
     * Read a parsed {@code PSSI} tag.
     *
     * @return the structure, or {@code null} when the tag holds no phrases
     */
    public static PhraseData fromTag(RekordboxAnlz.SongStructureTag tag, int trackId) {
        if (tag == null || tag.body() == null) return null;
        RekordboxAnlz.SongStructureBody body = tag.body();
        List<RekordboxAnlz.SongStructureEntry> raw = body.entries();
        if (raw == null || raw.isEmpty()) return null;

        String mood = body.mood() == null ? "unknown" : body.mood().name().toLowerCase(Locale.ROOT);
        String bank = body.bank() == null ? "" : body.bank().name().toLowerCase(Locale.ROOT);

        List<PhraseData.Entry> entries = new ArrayList<>(raw.size());
        for (RekordboxAnlz.SongStructureEntry e : raw) {
            String rawKind = rawKind(e);
            String label;
            try {
                label = Util.phraseLabel(e);
            } catch (RuntimeException ex) {
                label = null;
            }
            if (label == null || label.isEmpty()) label = rawKind;
            entries.add(new PhraseData.Entry(e.beat(), rawKind, label, e.fill() != 0, e.beatFill()));
        }
        return new PhraseData(trackId, mood, bank, body.endBeat(), entries);
    }

    static String rawKind(RekordboxAnlz.SongStructureEntry e) {
        Object kind = e.kind();
        Enum<?> id = null;
        if (kind instanceof RekordboxAnlz.PhraseHigh) {
            id = ((RekordboxAnlz.PhraseHigh) kind).id();
        } else if (kind instanceof RekordboxAnlz.PhraseMid) {
            id = ((RekordboxAnlz.PhraseMid) kind).id();
        } else if (kind instanceof RekordboxAnlz.PhraseLow) {
            id = ((RekordboxAnlz.PhraseLow) kind).id();
        }
        return id == null ? "unknown" : id.name().toLowerCase(Locale.ROOT);
    }

    /** The {@code /phrases/{player}} messages for one structure, in order. */
    public static List<OscMessage> encode(int player, PhraseData data) {
        String address = "/phrases/" + player;
        int total = data.entries.size();
        List<OscMessage> chunks = new ArrayList<>();
        int offset = 0;
        do {
            int count = Math.min(CHUNK_SIZE, total - offset);
            List<Object> args = new ArrayList<>(7 + count * 5);
            args.add(data.trackId);
            args.add(data.mood);
            args.add(data.bank);
            args.add(data.endBeat);
            args.add(total);
            args.add(offset);
            args.add(count);
            for (int i = offset; i < offset + count; i++) {
                PhraseData.Entry e = data.entries.get(i);
                args.add(e.startBeat);
                args.add(e.rawKind);
                args.add(e.label);
                args.add(e.fill ? 1 : 0);
                args.add(e.fillBeat);
            }
            chunks.add(new OscMessage(address, args));
            offset += count;
        } while (offset < total);
        return chunks;
    }

    public static OscMessage none(int player, int trackId) {
        return OscMessage.of("/phrases/" + player + "/none", trackId);
    }
}
