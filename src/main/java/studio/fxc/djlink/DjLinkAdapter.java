package studio.fxc.djlink;

import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.BeatFinder;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.DeviceAnnouncement;
import org.deepsymmetry.beatlink.DeviceAnnouncementListener;
import org.deepsymmetry.beatlink.DeviceFinder;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.MasterListener;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.deepsymmetry.beatlink.data.AnalysisTagFinder;
import org.deepsymmetry.beatlink.data.AnalysisTagUpdate;
import org.deepsymmetry.beatlink.data.BeatGridFinder;
import org.deepsymmetry.beatlink.data.CrateDigger;
import org.deepsymmetry.beatlink.data.DeckReference;
import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.beatlink.data.SearchableItem;
import org.deepsymmetry.beatlink.data.TimeFinder;
import org.deepsymmetry.beatlink.data.TrackMetadata;
import org.deepsymmetry.beatlink.data.TrackPositionBeatListener;
import org.deepsymmetry.beatlink.data.TrackPositionUpdate;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Connects beat-link to {@link BridgeState}.
 *
 * beat-link calls listeners on its own threads. Every listener here copies what
 * it needs into plain values and posts the update to the bridge's single event
 * thread, so {@link BridgeState} never runs concurrently and a slow OSC send
 * never blocks a beat-link thread.
 */
public final class DjLinkAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DjLinkAdapter.class);

    /** The name the bridge announces on the DJ Link network. FXCanvas filters it out of its device list. */
    public static final String DEVICE_NAME = "FXCanvas-Link";
    static final String PHRASE_FILE = ".EXT";
    static final String PHRASE_TAG = "PSSI";

    private final BridgeState state;
    private final Executor events;
    private final Options options;

    private final DeviceFinder deviceFinder = DeviceFinder.getInstance();
    private final VirtualCdj virtualCdj = VirtualCdj.getInstance();
    private final BeatFinder beatFinder = BeatFinder.getInstance();
    private final MetadataFinder metadataFinder = MetadataFinder.getInstance();

    public DjLinkAdapter(BridgeState state, Executor events, Options options) {
        this.state = state;
        this.events = events;
        this.options = options;
    }

    public void start() throws Exception {
        if (options.deviceNumber == 0 && options.realPlayer) {
            virtualCdj.setUseStandardPlayerNumber(true);
            logger.info("Virtual player will try to use a standard player number (1-4)");
        }
        virtualCdj.setDeviceName(DEVICE_NAME);

        deviceFinder.addDeviceAnnouncementListener(new DeviceAnnouncementListener() {
            @Override
            public void deviceFound(DeviceAnnouncement a) {
                final int number = a.getDeviceNumber();
                final String name = a.getDeviceName();
                final String ip = a.getAddress().getHostAddress();
                logger.info("Device found: {}", a);
                events.execute(() -> state.deviceFound(number, name, ip));
                ensureVirtualCdj();
            }

            @Override
            public void deviceLost(DeviceAnnouncement a) {
                final int number = a.getDeviceNumber();
                final String name = a.getDeviceName();
                final String ip = a.getAddress().getHostAddress();
                logger.info("Device lost: {}", a);
                events.execute(() -> state.deviceLost(number, name, ip));
                if (deviceFinder.getCurrentDevices().isEmpty()) {
                    logger.info("Last device gone, stopping the virtual player");
                    virtualCdj.stop();
                }
            }
        });

        virtualCdj.addUpdateListener(this::onStatus);

        beatFinder.addBeatListener(beat -> {
            final int device = beat.getDeviceNumber();
            final int beatInBar = beat.getBeatWithinBar();
            final float effective = (float) beat.getEffectiveTempo();
            final float track = beat.getBpm() / 100.0f;
            final float pitch = (float) Util.pitchToMultiplier(beat.getPitch());
            events.execute(() -> state.beat(device, beatInBar, effective, track, pitch));
        });

        virtualCdj.addMasterListener(new MasterListener() {
            @Override
            public void masterChanged(DeviceUpdate update) {
                final Integer device = update == null ? null : update.getDeviceNumber();
                events.execute(() -> state.masterChanged(device));
            }

            @Override
            public void tempoChanged(double tempo) {
                final float t = (float) tempo;
                events.execute(() -> state.masterTempo(t));
            }

            @Override
            public void newBeat(Beat beat) {
                final int device = beat.getDeviceNumber();
                final int beatInBar = beat.getBeatWithinBar();
                final float effective = (float) beat.getEffectiveTempo();
                final float track = beat.getBpm() / 100.0f;
                final float pitch = (float) Util.pitchToMultiplier(beat.getPitch());
                events.execute(() -> state.masterBeat(device, beatInBar, effective, track, pitch));
            }
        });

        metadataFinder.addTrackMetadataListener(update -> {
            final int player = update.player;
            final TrackInfo info = toTrackInfo(update.metadata);
            events.execute(() -> state.metadata(player, info));
        });

        AnalysisTagFinder.getInstance().addAnalysisTagListener(this::onAnalysis, PHRASE_FILE, PHRASE_TAG);

        for (int player = 1; player <= BridgeState.MAX_PLAYER; player++) {
            TimeFinder.getInstance().addTrackPositionListener(player, new PositionListener(player));
        }

        beatFinder.start();
        deviceFinder.start();
        logger.info("Waiting for Pro DJ Link devices...");
    }

    public void stop() {
        TimeFinder.getInstance().stop();
        AnalysisTagFinder.getInstance().stop();
        BeatGridFinder.getInstance().stop();
        CrateDigger.getInstance().stop();
        metadataFinder.stop();
        virtualCdj.stop();
        beatFinder.stop();
        deviceFinder.stop();
    }

    /** Start the virtual player (and the finders that depend on it) once a device is seen. */
    private void ensureVirtualCdj() {
        if (virtualCdj.isRunning()) return;
        Thread starter = new Thread(() -> {
            synchronized (DjLinkAdapter.this) {
                if (virtualCdj.isRunning()) return;
                try {
                    if (!virtualCdj.start((byte) options.deviceNumber)) {
                        logger.warn("Virtual player failed to start");
                        return;
                    }
                    int number = virtualCdj.getDeviceNumber();
                    logger.info("Virtual player running as device {}", number);
                    metadataFinder.start();
                    // A real player number can query the players' databases directly.
                    // Otherwise read the exported database and analysis files over NFS,
                    // which works whatever number we use and with four real players.
                    metadataFinder.setPassive(number > 4);
                    CrateDigger.getInstance().start();
                    BeatGridFinder.getInstance().start();
                    AnalysisTagFinder.getInstance().start();
                    TimeFinder.getInstance().start();
                } catch (Throwable t) {
                    logger.error("Problem starting the virtual player", t);
                }
            }
        }, "virtual-cdj-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void onStatus(DeviceUpdate status) {
        final int device = status.getDeviceNumber();
        final float tempo = status.getBpm() != 0xffff ? (float) status.getEffectiveTempo() : Float.NaN;
        final boolean isCdj = status instanceof CdjStatus;
        final boolean playing = isCdj && ((CdjStatus) status).isPlaying();
        final boolean onAir = isCdj && ((CdjStatus) status).isOnAir();
        events.execute(() -> state.status(device, tempo, isCdj, playing, onAir));
    }

    private void onAnalysis(AnalysisTagUpdate update) {
        final int player = update.player;
        final int trackId = loadedTrackId(player);
        PhraseData data = null;
        try {
            if (update.taggedSection != null
                    && update.taggedSection.body() instanceof RekordboxAnlz.SongStructureTag) {
                data = PhraseEncoder.fromTag((RekordboxAnlz.SongStructureTag) update.taggedSection.body(), trackId);
            }
        } catch (RuntimeException e) {
            logger.warn("Unable to read phrase analysis for player {}", player, e);
        }
        final PhraseData result = data;
        events.execute(() -> {
            if (result != null) {
                state.phrases(player, result);
            } else {
                state.phrasesNone(player, trackId);
            }
        });
    }

    /** The rekordbox id of the track on a player's deck, 0 when unknown. */
    private int loadedTrackId(int player) {
        try {
            Map<DeckReference, Map<String, AnalysisTagFinder.CacheEntry>> loaded =
                    AnalysisTagFinder.getInstance().getLoadedAnalysisTags();
            for (Map.Entry<DeckReference, Map<String, AnalysisTagFinder.CacheEntry>> e : loaded.entrySet()) {
                if (e.getKey().player != player || e.getKey().hotCue != 0) continue;
                for (AnalysisTagFinder.CacheEntry entry : e.getValue().values()) {
                    if (PHRASE_TAG.equals(entry.typeTag) && entry.dataReference != null) {
                        return entry.dataReference.rekordboxId;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the player status
        }
        return statusTrackId(player);
    }

    private int statusTrackId(int player) {
        try {
            DeviceUpdate status = virtualCdj.getLatestStatusFor(player);
            if (status instanceof CdjStatus) {
                return ((CdjStatus) status).getRekordboxId();
            }
        } catch (RuntimeException ignored) {
            // not running
        }
        return 0;
    }

    static TrackInfo toTrackInfo(TrackMetadata md) {
        if (md == null) return null;
        return new TrackInfo(
                md.trackReference == null ? 0 : md.trackReference.rekordboxId,
                md.getTitle(),
                label(md.getArtist()),
                label(md.getAlbum()),
                md.getColor() == null ? "" : md.getColor().colorName,
                md.getComment(),
                md.getDuration(),
                label(md.getGenre()),
                label(md.getKey()),
                label(md.getLabel()),
                label(md.getOriginalArtist()),
                md.getRating(),
                label(md.getRemixer()),
                md.getTempo() / 100.0f);
    }

    private static String label(SearchableItem item) {
        return item == null ? null : item.label;
    }

    /** Positions for one player: every beat, and every unexpected movement. */
    private final class PositionListener implements TrackPositionBeatListener {
        private final int player;

        PositionListener(int player) {
            this.player = player;
        }

        @Override
        public void movementChanged(TrackPositionUpdate update) {
            post(update, PositionData.REASON_MOVEMENT);
        }

        @Override
        public void newBeat(Beat beat, TrackPositionUpdate update) {
            post(update, PositionData.REASON_BEAT);
        }

        private void post(TrackPositionUpdate update, int reason) {
            if (update == null || update.beatNumber <= 0) return;
            final PositionData p = new PositionData(statusTrackId(player), update.beatNumber,
                    update.playing, update.definitive, reason);
            events.execute(() -> state.position(player, p));
        }
    }
}
