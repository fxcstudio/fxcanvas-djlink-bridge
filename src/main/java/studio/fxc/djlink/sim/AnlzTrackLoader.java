package studio.fxc.djlink.sim;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.data.DataReference;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.deepsymmetry.beatlink.data.WaveformPreview;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.PhraseEncoder;
import studio.fxc.djlink.assets.WaveformConverter;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.PhraseData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SimTrack} from a real rekordbox analysis file set, as found
 * in {@code PIONEER/USBANLZ/...} on an exported USB stick:
 * {@code ANLZ0000.DAT} (beat grid, blue preview), {@code ANLZ0000.EXT}
 * (phrases, color waveforms) and {@code ANLZ0000.2EX} (three-band waveforms,
 * rekordbox 6.7 and later).
 *
 * Give any one of them: the others in the same folder are read too. The track
 * plays at the beat grid's first tempo (the simulator keeps tempo constant).
 * Every waveform style present is read (three-band, color, blue), so the
 * simulator can serve the style a client asks for.
 */
public final class AnlzTrackLoader {
    private static final Logger logger = LoggerFactory.getLogger(AnlzTrackLoader.class);

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

        // Parse every file of the set that exists, keyed by extension.
        Map<String, RekordboxAnlz> files = new LinkedHashMap<>();
        for (String ext : new String[]{"DAT", "EXT", "2EX"}) {
            Path candidate = folder.resolve(base + "." + ext);
            if (!Files.isRegularFile(candidate)) {
                // Lower-case extensions on case-sensitive file systems.
                candidate = folder.resolve(base + "." + ext.toLowerCase());
                if (!Files.isRegularFile(candidate)) continue;
            }
            files.put(ext, RekordboxAnlz.fromFile(candidate.toString()));
        }
        if (files.isEmpty()) throw new IOException("No analysis files next to " + file);

        RekordboxAnlz.SongStructureTag structure = null;
        RekordboxAnlz.BeatGridTag grid = null;
        for (RekordboxAnlz anlz : files.values()) {
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
        SimTrack plain = new SimTrack(trackId, "Analysis " + title, "rekordbox export", bpm, firstBeatMs, beatCount,
                phrases);

        DataReference ref = new DataReference(1, CdjStatus.TrackSourceSlot.USB_SLOT, trackId);
        Map<WaveformData.Style, WaveformData> details = readDetails(files, ref, trackId);
        Map<WaveformData.Style, WaveformData> previews = readPreviews(files, ref, trackId, plain.durationMs());
        return new SimTrack(trackId, plain.title, plain.artist, bpm, firstBeatMs, beatCount, phrases, previews,
                details, null);
    }

    private static Map<WaveformData.Style, WaveformData> readDetails(Map<String, RekordboxAnlz> files,
                                                                     DataReference ref, int trackId) {
        Map<WaveformData.Style, WaveformData> out = new EnumMap<>(WaveformData.Style.class);
        Object[][] attempts = {
                {"2EX", WaveformFinder.WaveformStyle.THREE_BAND},
                {"EXT", WaveformFinder.WaveformStyle.RGB},
                {"EXT", WaveformFinder.WaveformStyle.BLUE},
        };
        for (Object[] a : attempts) {
            RekordboxAnlz anlz = files.get((String) a[0]);
            if (anlz == null) continue;
            try {
                WaveformDetail d = new WaveformDetail(ref, anlz, (WaveformFinder.WaveformStyle) a[1]);
                if (d.getFrameCount() > 0) {
                    WaveformData w = WaveformConverter.fromDetail(d, trackId);
                    out.putIfAbsent(w.style, w);
                }
            } catch (RuntimeException e) {
                logger.debug("No {} detail waveform in the {} file: {}", a[1], a[0], e.getMessage());
            }
        }
        return out;
    }

    private static Map<WaveformData.Style, WaveformData> readPreviews(Map<String, RekordboxAnlz> files,
                                                                      DataReference ref, int trackId,
                                                                      long durationMs) {
        Map<WaveformData.Style, WaveformData> out = new EnumMap<>(WaveformData.Style.class);
        Object[][] attempts = {
                {"2EX", WaveformFinder.WaveformStyle.THREE_BAND},
                {"EXT", WaveformFinder.WaveformStyle.RGB},
                {"DAT", WaveformFinder.WaveformStyle.BLUE},
        };
        for (Object[] a : attempts) {
            RekordboxAnlz anlz = files.get((String) a[0]);
            if (anlz == null) continue;
            try {
                WaveformPreview p = new WaveformPreview(ref, anlz, (WaveformFinder.WaveformStyle) a[1]);
                if (p.segmentCount > 0) {
                    WaveformData w = WaveformConverter.fromPreview(p, trackId, durationMs);
                    out.putIfAbsent(w.style, w);
                }
            } catch (RuntimeException e) {
                logger.debug("No {} preview waveform in the {} file: {}", a[1], a[0], e.getMessage());
            }
        }
        return out;
    }
}
