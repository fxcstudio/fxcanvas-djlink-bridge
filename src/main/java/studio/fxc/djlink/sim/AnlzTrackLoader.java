package studio.fxc.djlink.sim;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import studio.fxc.djlink.PhraseEncoder;
import studio.fxc.djlink.model.PhraseData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a {@link SimTrack} from a real rekordbox analysis file, the
 * {@code ANLZ0000.EXT} (phrases) and {@code ANLZ0000.DAT} (beat grid) found in
 * {@code PIONEER/USBANLZ/...} on an exported USB stick.
 *
 * Give either file: the other one in the same folder is read too. The track
 * plays at the beat grid's first tempo (the simulator keeps tempo constant).
 */
public final class AnlzTrackLoader {
    private AnlzTrackLoader() {
    }

    public static SimTrack load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No such analysis file: " + file);
        }
        Path folder = file.toAbsolutePath().getParent();
        String name = file.getFileName().toString();
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        int trackId = 100000 + Math.floorMod(file.toAbsolutePath().toString().hashCode(), 900000);

        RekordboxAnlz.SongStructureTag structure = null;
        RekordboxAnlz.BeatGridTag grid = null;
        for (String ext : new String[]{name.substring(name.lastIndexOf('.') + 1), "EXT", "DAT"}) {
            Path candidate = folder.resolve(base + "." + ext);
            if (!Files.isRegularFile(candidate)) continue;
            RekordboxAnlz anlz = RekordboxAnlz.fromFile(candidate.toString());
            for (RekordboxAnlz.TaggedSection section : anlz.sections()) {
                if (structure == null && section.body() instanceof RekordboxAnlz.SongStructureTag) {
                    structure = (RekordboxAnlz.SongStructureTag) section.body();
                }
                if (grid == null && section.body() instanceof RekordboxAnlz.BeatGridTag) {
                    grid = (RekordboxAnlz.BeatGridTag) section.body();
                }
            }
        }

        PhraseData phrases = structure == null ? null : PhraseEncoder.fromTag(structure, trackId);
        float bpm = 128f;
        long firstBeatMs = 0;
        int beatCount;
        if (grid != null && !grid.beats().isEmpty()) {
            List<RekordboxAnlz.BeatGridBeat> beats = grid.beats();
            bpm = Math.max(1, beats.get(0).tempo()) / 100.0f;
            firstBeatMs = beats.get(0).time();
            beatCount = beats.size();
        } else if (phrases != null) {
            beatCount = phrases.endBeat - 1 + 8;
        } else {
            throw new IOException("Neither phrases nor a beat grid in " + file);
        }
        String title = folder.getFileName() == null ? base : folder.getFileName().toString();
        return new SimTrack(trackId, "Analysis " + title, "rekordbox export", bpm, firstBeatMs, beatCount, phrases);
    }
}
