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
import org.deepsymmetry.beatlink.data.AlbumArt;
import org.deepsymmetry.beatlink.data.AnalysisTagFinder;
import org.deepsymmetry.beatlink.data.AnalysisTagUpdate;
import org.deepsymmetry.beatlink.data.ArtFinder;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.BeatGridFinder;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformDetailUpdate;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.deepsymmetry.beatlink.data.WaveformListener;
import org.deepsymmetry.beatlink.data.WaveformPreview;
import org.deepsymmetry.beatlink.data.WaveformPreviewUpdate;
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
import studio.fxc.djlink.assets.AssetCodec;
import studio.fxc.djlink.assets.AssetStore;
import studio.fxc.djlink.assets.BeatGridData;
import studio.fxc.djlink.assets.WaveformConverter;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.DeckState;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    /** Track length per player, from metadata (written on a beat-link thread, read on others). */
    private final Map<Integer, Long> durationsMs = new ConcurrentHashMap<>();
    /** Serializes passive-mode changes (device listeners and the virtual player starter). */
    private final Object passiveLock = new Object();
    /** The mode last applied by {@link #updatePassive}, null before the first time. Guarded by passiveLock. */
    private Boolean loggedPassive;

    private final AssetStore assets;
    /** Set from the event thread when a client starts or stops streaming /waveforms or /art. */
    private volatile boolean wantWaveforms;
    private volatile boolean wantArt;
    /** Starts and stops the waveform and art finders, which can block on the network. */
    private final ExecutorService demandWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "asset-demand");
        t.setDaemon(true);
        return t;
    });

    public DjLinkAdapter(BridgeState state, Executor events, Options options, AssetStore assets) {
        this.state = state;
        this.events = events;
        this.options = options;
        this.assets = assets;
    }

    /** {@link BridgeState.DemandListener}: called on the event thread. */
    public void demandChanged(String topic, boolean wanted) {
        if (BridgeState.WAVEFORMS.equals(topic)) wantWaveforms = wanted;
        if (BridgeState.ART.equals(topic)) wantArt = wanted;
        logger.info("{} {}", topic, wanted ? "requested: fetching from the players" : "no longer requested");
        demandWorker.execute(this::applyDemand);
    }

    /** Start or stop the finders to match demand. Runs on the demand worker. */
    private void applyDemand() {
        if (!metadataFinder.isRunning()) return;   // ensureVirtualCdj() calls this again once it is
        try {
            WaveformFinder waveforms = WaveformFinder.getInstance();
            if (wantWaveforms && !waveforms.isRunning()) {
                waveforms.setPreferredStyle(WaveformFinder.WaveformStyle.THREE_BAND);
                waveforms.setFindDetails(true);
                waveforms.start();
                // Beat grids are always loaded (positions need them): send the current ones.
                for (int player = 1; player <= BridgeState.MAX_PLAYER; player++) {
                    BeatGrid grid = BeatGridFinder.getInstance().getLatestBeatGridFor(player);
                    if (grid != null) onBeatGrid(player, grid);
                }
            } else if (!wantWaveforms && waveforms.isRunning()) {
                waveforms.stop();
            }
            ArtFinder art = ArtFinder.getInstance();
            if (wantArt && !art.isRunning()) {
                art.setRequestHighResolutionArt(true);
                art.start();
            } else if (!wantArt && art.isRunning()) {
                art.stop();
            }
        } catch (Throwable t) {
            logger.error("Problem starting the waveform or album art finder", t);
        }
    }

    private void onPreview(int player, WaveformPreview preview) {
        if (!wantWaveforms) return;
        if (preview == null) {
            final int trackId = statusTrackId(player);
            events.execute(() -> state.waveformNone(player, trackId, WaveformData.Kind.PREVIEW));
            return;
        }
        try {
            final int trackId = preview.dataReference.rekordboxId;
            final WaveformData data = WaveformConverter.fromPreview(preview, trackId,
                    durationsMs.getOrDefault(player, 0L));
            publishWaveform(player, data);
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to export the waveform preview for player {}", player, e);
        }
    }

    private void onDetail(int player, WaveformDetail detail) {
        if (!wantWaveforms) return;
        if (detail == null) {
            final int trackId = statusTrackId(player);
            events.execute(() -> state.waveformNone(player, trackId, WaveformData.Kind.DETAIL));
            return;
        }
        try {
            publishWaveform(player, WaveformConverter.fromDetail(detail, detail.dataReference.rekordboxId));
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to export the waveform detail for player {}", player, e);
        }
    }

    private void publishWaveform(int player, WaveformData data) throws IOException {
        final String path = assets.write("p" + player + "-" + data.kind.oscName(), "fxwf",
                AssetCodec.encode(data)).toString();
        events.execute(() -> state.waveform(player, data, path));
    }

    private void onBeatGrid(int player, BeatGrid grid) {
        if (!wantWaveforms) return;
        if (grid == null) {
            final int trackId = statusTrackId(player);
            events.execute(() -> state.beatGridNone(player, trackId));
            return;
        }
        try {
            final int trackId = grid.dataReference == null ? statusTrackId(player) : grid.dataReference.rekordboxId;
            final BeatGridData data = WaveformConverter.fromBeatGrid(grid, trackId);
            final String path = assets.write("p" + player + "-grid", "fxbg", AssetCodec.encode(data)).toString();
            events.execute(() -> state.beatGrid(player, trackId, data.beatCount(), path));
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to export the beat grid for player {}", player, e);
        }
    }

    private void onArt(int player, AlbumArt art) {
        if (!wantArt) return;
        if (art == null) {
            final int trackId = statusTrackId(player);
            events.execute(() -> state.artNone(player, trackId));
            return;
        }
        try {
            final int trackId = art.artReference.rekordboxId;
            final ByteBuffer raw = art.getRawBytes();
            final byte[] bytes = new byte[raw.remaining()];
            raw.get(bytes);
            final BufferedImage image = art.getImage();
            final int width = image == null ? 0 : image.getWidth();
            final int height = image == null ? 0 : image.getHeight();
            final String path = assets.write("p" + player + "-art", "jpg", bytes).toString();
            events.execute(() -> state.art(player, trackId, width, height, path));
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to export the album art for player {}", player, e);
        }
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
                updatePassive();
            }

            @Override
            public void deviceLost(DeviceAnnouncement a) {
                final int number = a.getDeviceNumber();
                final String name = a.getDeviceName();
                final String ip = a.getAddress().getHostAddress();
                logger.info("Device lost: {}", a);
                events.execute(() -> state.deviceLost(number, name, ip));
                updatePassive();
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
            if (info != null && info.duration > 0) {
                durationsMs.put(player, info.duration * 1000L);
            } else {
                durationsMs.remove(player);
            }
            events.execute(() -> state.metadata(player, info));
        });

        AnalysisTagFinder.getInstance().addAnalysisTagListener(this::onAnalysis, PHRASE_FILE, PHRASE_TAG);

        for (int player = 1; player <= BridgeState.MAX_PLAYER; player++) {
            TimeFinder.getInstance().addTrackPositionListener(player, new PositionListener(player));
        }

        // Track assets. The listeners are always in place; the waveform and art
        // finders only run while a client streams /waveforms or /art.
        WaveformFinder.getInstance().addWaveformListener(new WaveformListener() {
            @Override
            public void previewChanged(WaveformPreviewUpdate update) {
                onPreview(update.player, update.preview);
            }

            @Override
            public void detailChanged(WaveformDetailUpdate update) {
                onDetail(update.player, update.detail);
            }
        });
        BeatGridFinder.getInstance().addBeatGridListener(update -> onBeatGrid(update.player, update.beatGrid));
        ArtFinder.getInstance().addAlbumArtListener(update -> onArt(update.player, update.art));

        beatFinder.start();
        deviceFinder.start();
        logger.info("Waiting for Pro DJ Link devices...");
    }

    public void stop() {
        demandWorker.shutdownNow();
        WaveformFinder.getInstance().stop();
        ArtFinder.getInstance().stop();
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
                    updatePassive();
                    CrateDigger.getInstance().start();
                    BeatGridFinder.getInstance().start();
                    AnalysisTagFinder.getInstance().start();
                    TimeFinder.getInstance().start();
                    demandWorker.execute(DjLinkAdapter.this::applyDemand);
                } catch (Throwable t) {
                    logger.error("Problem starting the virtual player", t);
                }
            }
        }, "virtual-cdj-start");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Choose how the finders get track data.
     *
     * A standard player number (1-4) can query every player's database
     * directly. Above 4, CDJ-3000, CDJ-3000X and XDJ-AZ players still answer
     * queries, but older players only answer three other devices, so the
     * bridge then reads the exported database and analysis files over NFS
     * (passive mode). Passive mode cannot read the encrypted SQLite export
     * that the CDJ-3000X and XDJ-AZ use without a database key, so the bridge
     * stays active whenever every player on the network accepts the queries.
     */
    static boolean usePassiveMetadata(int deviceNumber, boolean limitedPlayerPresent) {
        return deviceNumber > 4 && limitedPlayerPresent;
    }

    /** Apply {@link #usePassiveMetadata} to the devices now on the network. */
    private void updatePassive() {
        if (!virtualCdj.isRunning()) return;   // ensureVirtualCdj() calls this again once it is
        synchronized (passiveLock) {
            final boolean passive = usePassiveMetadata(virtualCdj.getDeviceNumber(),
                    deviceFinder.isAnyDeviceLimitedToThreeDatabaseClients());
            if (loggedPassive == null || passive != loggedPassive) {
                loggedPassive = passive;
                metadataFinder.setPassive(passive);
                logger.info(passive
                        ? "Reading track data from the players' exported files (an older player is on the network)"
                        : "Requesting track data from the players directly");
            }
        }
    }

    private void onStatus(DeviceUpdate status) {
        final int device = status.getDeviceNumber();
        final float tempo = status.getBpm() != 0xffff ? (float) status.getEffectiveTempo() : Float.NaN;
        final boolean isCdj = status instanceof CdjStatus;
        final boolean playing = isCdj && ((CdjStatus) status).isPlaying();
        final boolean onAir = isCdj && ((CdjStatus) status).isOnAir();
        final DeckState deck = isCdj ? toDeckState((CdjStatus) status) : null;
        events.execute(() -> {
            state.status(device, tempo, isCdj, playing, onAir);
            if (deck != null) state.deckState(device, deck);
        });
    }

    static DeckState toDeckState(CdjStatus s) {
        final CdjStatus.PlayState1 play = s.getPlayState1();
        final String playState = play == null ? "unknown" : play.name().toLowerCase(Locale.ROOT);
        long loopStart = 0;
        long loopEnd = 0;
        int loopBeats = 0;
        // Loop bounds come from players that report them (CDJ-3000); others
        // still report "looping" through their play state.
        if (s.canReportLooping()) {
            loopStart = s.getLoopStart();
            loopEnd = s.getLoopEnd();
            loopBeats = s.getActiveLoopBeats();
        }
        // An emergency loop (the player lost its media and loops what it has
        // buffered) counts as looping, so FXCanvas holds the current section.
        final boolean looping = s.isLooping() || play == CdjStatus.PlayState1.EMERGENCY_LOOP;
        return new DeckState(s.getRekordboxId(), playState, looping, loopStart, loopEnd, loopBeats,
                s.isAtEnd(), s.isCued());
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
            final int beatCount = update.beatGrid == null ? 0 : update.beatGrid.beatCount;
            final PositionData p = new PositionData(statusTrackId(player), update.beatNumber,
                    update.playing, update.definitive, reason, update.milliseconds,
                    durationsMs.getOrDefault(player, 0L), beatCount, (float) update.pitch);
            events.execute(() -> state.position(player, p));
        }
    }
}
