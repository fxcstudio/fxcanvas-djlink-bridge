# FXCanvas DJ Link Bridge

A small headless Java program that joins a Pro DJ Link network and reports
what the players are doing over OSC on the local machine. FXCanvas starts it
as a child process for its Pioneer DJ tempo source.

It is built on Deep Symmetry's [beat-link](https://github.com/Deep-Symmetry/beat-link)
and speaks the same OSC messages as Deep Symmetry's
[open-beat-control](https://github.com/Deep-Symmetry/open-beat-control) for the
features FXCanvas uses, plus these additions:

- `/version`: bridge name, version and protocol number.
- `/phrases`: rekordbox phrase analysis (song structure) for each loaded track.
- `/positions`: the beat-grid beat and time each player is on, every beat and after every jump.
- `/deck-state`: play state, loops and end of track per player.
- `/waveforms` and `/art`: waveform preview, waveform detail, beat grid and
  album art files for each loaded track (since 1.3.0).

> **Unofficial.** This project is not affiliated with, endorsed by, or supported
> by Pioneer DJ, AlphaTheta, or rekordbox. Use it at your own risk; see the
> compatibility notes in beat-link's README.

## Run

Java 11 or later.

```
java -jar fxc-djlink-bridge-1.5.0.jar [-o 17002] [-r] [-d NUM] [-L file.log]
```

The OSC server listens on `127.0.0.1` only. Send `/respond <port>` first to
choose where responses go (default: the sender's port plus one).

| Option | Default | Meaning |
|---|---|---|
| `-o, --osc-port PORT` | 17002 | OSC server port |
| `-r, --real-player` | off | Try to use player number 1-4 |
| `-d, --device-number NUM` | auto | Fixed device number (overrides `-r`) |
| `-L, --log-file PATH` | stderr | Log to a rotating file |
| `--asset-dir DIR` | `<temp>/fxc-djlink-bridge/port-<PORT>` | Folder for track asset files (emptied at start, removed at exit) |
| `-l, --latency MS` | 20 | Accepted for compatibility |
| `-B -a -b -c PORT` | | Accepted and ignored (FXCanvas bridges Ableton Link itself) |

## Simulator

Since 1.2.0 the bridge can simulate players instead of joining a network, so
you can test a client without CDJs. A client sees the same OSC as from real
players: device found messages for the players and a mixer (device 33),
status, beats, the tempo master, metadata, phrases, positions and deck state.

```
java -jar fxc-djlink-bridge-1.5.0.jar --simulate
java -jar fxc-djlink-bridge-1.5.0.jar --sim-decks 3 --sim-track a --sim-track short --sim-speed 4
java -jar fxc-djlink-bridge-1.5.0.jar --sim-track PIONEER/USBANLZ/P016/0000875E/ANLZ0000.EXT
```

| Option | Default | Meaning |
|---|---|---|
| `--simulate` | off | Simulate players; no network traffic |
| `--sim-decks N` | 2 | Number of players, 1-6 |
| `--sim-speed X` | 1 | Time multiplier, for fast tests |
| `--sim-track NAME\|FILE` | a, b, c, short, a, b | Track for the next player (repeatable). `--anlz` is the same option |
| `--sim-script FILE` | | Timed commands, one `<seconds> <command>` per line, `#` comments |
| `--sim-no-mixer` | off | No mixer (device 33) on the network. Every player stays off air, as real players do without a DJM on the link (since 1.5.0) |
| `--send "COMMAND"` | | Send a command to a running simulator on `-o PORT` and exit (repeatable) |

Since 1.5.0 the simulated players behave on the wire as real CDJ-3000s were
measured to: a `load` arrives in stages over half a second (the old track is
cleared, the deck reports `loading`, `no_track`, then the new track, then
its metadata, phrases, beat grid, art and waveforms), a paused deck reports
its slowing platter for half a second, and the tempo master moves to another
playing deck when the master stops.

Any `--sim-*` option implies `--simulate`. At start, player 1 plays as tempo
master and on air; the others are cued at their first beat. Every loaded
track also gets [track assets](#track-assets-since-130): a waveform shaped by
its phrases (kicks on the beat, quiet breakdowns, kick-free fill-ins), a beat
grid and generated cover art.

**Tracks.** Built-in tracks have a constant tempo and a phrase structure:

| Name | Tempo | Mood, bank | Phrases |
|---|---|---|---|
| `a` | 128 | high, club_1 | Intro 1, Up 1, Down, Chorus 1, Up 2, Chorus 2, Outro 1 |
| `b` | 124 | mid, hot | Intro, Verse 1, Chorus, Bridge, Verse 2, Chorus, Outro |
| `c` | 122 | low, cool | Intro, Verse 1, Verse 2, Chorus, Outro |
| `short` | 130 | high, vivid | Six 4-bar phrases, most with fill-ins, for quick tests |
| `plain` | 126 | | None: stands for a track without phrase analysis |

Phrases are 16 bars unless noted; some end in a 2-bar fill-in. A real
rekordbox analysis file also works: give the `ANLZ0000.EXT` or `ANLZ0000.DAT`
from an exported USB stick (`PIONEER/USBANLZ/...`) and the other file in the
same folder is read too (including `ANLZ0000.2EX`, which holds the
three-band waveforms), so the simulated deck sends that track's real
waveforms. The track plays at its beat grid's first tempo.

**Commands.** Type them on the bridge's stdin, put them in a script, send them
with `--send`, or send OSC `/sim/<command> args...` to the bridge port. Deck
numbers start at 1.

| Command | Effect |
|---|---|
| `play <deck> [0\|1]` | Start (default) or stop |
| `cue <deck>` | Stop and go back to the first beat |
| `seek <deck> <beat>` | Jump to a beat-grid beat |
| `phrase <deck> <n>` | Jump to one beat before phrase n |
| `end <deck> [bars]` | Jump to `bars` (default 8) before the end |
| `loop <deck> <beats>` | Loop from the current beat; `loop <deck> 0` ends the loop |
| `tempo <deck> <bpm>` | Change the pitch to reach this tempo |
| `load <deck> <name\|file> [bank]` | Load a track, optionally with another Lighting bank |
| `bank <deck> <bank>` | Change the loaded track's Lighting bank and resend its phrases |
| `info <deck> "title" ["artist"]` | Rename the loaded track (sends new metadata) |
| `art <deck> <file>` | Send a JPEG or PNG file as the loaded track's album art |
| `master <deck>` | Make the deck tempo master |
| `onair <deck> <0\|1>` | Channel fader up or down (an error while there is no mixer) |
| `mixer <0\|1>` | The mixer leaves (0) or joins (1) the network, no restart needed. Without a mixer no player is on air (since 1.5.0) |
| `state` | Print every deck (over OSC: one `/sim/deck` per deck) |
| `help` | List the commands |

Quote words that contain spaces: `load 1 "D:\My Stick\PIONEER\USBANLZ\...\ANLZ0000.EXT"`.

Over OSC every command answers `/sim/ok s address` or `/sim/error s message`
on the sender's response port. `/sim/deck` carries `i deck, s title, i beat,
i playing, i looping, f tempo, i master`. `--send` exits with 0 when every
command succeeded, 1 when one failed, and 2 when no simulator answered.

```
java -jar fxc-djlink-bridge-1.5.0.jar --send "loop 1 8" --send state
java -jar fxc-djlink-bridge-1.5.0.jar -o 17002 --send "bank 2 warm"
```

A script example (`show.txt`), run with `--sim-script show.txt`:

```
# Start deck 2 on the chorus and hand over after 30 seconds
10 phrase 2 3
12 play 2
12 onair 2 1
30 master 2
31 onair 1 0
```

## OSC protocol (version 1)

Subscriptions follow open-beat-control: send `/<topic> stream [port]`,
`/<topic> list [port]` or `/<topic> stop [port]`. `stream` also sends the
current state. Topics: `/devices /tempos /playing /on-air /metadata /master
/beats /phrases /positions /deck-state`.

### Compatible messages (unchanged from open-beat-control)

| Message | Arguments |
|---|---|
| `/device/found`, `/device/lost` | `i device, s name, s ip` |
| `/beat`, `/master/beat` | `i device, i beatInBar, f effectiveTempo, f trackTempo, f pitchMultiplier` |
| `/tempo/{device}` | `f effectiveTempo` |
| `/playing/{device}`, `/on-air/{device}` | `i 0/1` |
| `/master/player` | `i device` |
| `/master/tempo` | `f tempo` |
| `/master/playing` | `i 0/1` |
| `/master/none` | |
| `/metadata/{device}/{title,artist,album,color,comment,genre,key,label,original-artist,remixer}` | `s value` |
| `/metadata/{device}/{duration,rating}` | `i value` |
| `/metadata/{device}/tempo` | `f bpm` |
| `/metadata/{device}/none` | |

### New messages

`/version` (request, no arguments) answers `/version s name, s version, i protocol`.

`/phrases/{device}` carries a track's phrase list in chunks of at most 64 phrases:

```
i trackId, s mood, s bank, i endBeat, i total, i offset, i count,
then count times: i startBeat, s rawKind, s label, i fill, i fillBeat
```

- `mood`: `high`, `mid`, `low` or `unknown`.
- `bank`: `default cool natural hot subtle warm vivid club_1 club_2`, or empty.
- `rawKind`: crate-digger's enum name for the mood, for example `intro`,
  `verse_1b`, `up`, `chorus`.
- `label`: beat-link's `Util.phraseLabel()`, for example `Up 3`, `Verse 1`, `Chorus 2`.
- A structure is complete when `offset + count == total`.

`/phrases/{device}/none i trackId`: the track on that player has no phrase data
(not analyzed with phrase analysis, or unloaded with trackId 0).

`/position/{device} i trackId, i beatNumber, i playing, i definitive, i reason,
i positionMs, i durationMs, i beatCount, f pitch`:

- `reason` is 0 for a beat and 1 for a movement (cue, jump, loop, stop).
- Since 1.1.0 four arguments are appended (readers of the first five are
  unaffected): the playback position and the track length in milliseconds
  (length 0 when unknown), the number of beats in the beat grid (0 when
  unknown), and the playback speed (1.0 = normal). Together they give time
  remaining and beats remaining. Positions arrive on every beat and every
  movement; interpolate between them with `pitch`.

`/deck/{device} i trackId, s playState, i looping, i loopStartMs, i loopEndMs,
i loopBeats, i atEnd, i cued` (topic `/deck-state`, since 1.1.0), sent when any
of these change:

- `playState`: `no_track`, `loading`, `playing`, `looping`, `paused`, `cued`,
  `cue_playing`, `cue_scratching`, `searching`, `ended`, `emergency_loop`
  (the player lost its media and loops what it has buffered) or `unknown`.
- `looping`: 1 while a loop plays, including an emergency loop. Players that report loop bounds (CDJ-3000)
  also fill in `loopStartMs`, `loopEndMs` and `loopBeats`; others send 0.
- `atEnd`: 1 when the player reached the end of the track.

### Track assets (since 1.3.0)

A detail waveform (150 segments a second, so about 54,000 for a six-minute
track) does not fit in a UDP datagram, so the bridge writes waveforms, beat
grids and album art to files in its asset folder and sends their paths. Files
are written under a temporary name and renamed into place, so a file named in
a message is complete. The two newest files per player and kind are kept;
older ones are deleted.

The bridge only asks the players for waveforms and art while a client streams
`/waveforms` or `/art` (`stream`, `list` and `stop` as for every topic). The
beat grid comes with `/waveforms`.

**Waveform style (since 1.4.0).** Send `/waveforms s "style", s NAME` to pick
the style the bridge asks the players for, for example to match the waveform
mode set on the players:

| NAME | Style |
|---|---|
| `3band` (or `three_band`) | Three-band, as CDJ-3000 players show by default. The default until a client sends this command |
| `rgb` | RGB (color), as NXS2 players and CDJ-3000 players in RGB mode show |
| `blue` | Blue, the oldest style |

The style applies to every client and stays until the next command or a
restart. When it changes, the bridge fetches the waveforms of every loaded
track again and sends new `/waveform/{device}` messages; no track reload is
needed. The beat grid is not sent again. An unknown NAME answers `/error`
and changes nothing. The command is not a subscription: it does not start or
stop `/waveforms` streaming.

| Message | Arguments |
|---|---|
| `/waveform/{device}` | `i trackId, s kind, s style, i bands, i segments, f segmentsPerSecond, i durationMs, s path` |
| `/waveform/{device}/none` | `i trackId, s kind` |
| `/beat-grid/{device}` | `i trackId, i beatCount, s path` |
| `/beat-grid/{device}/none` | `i trackId` |
| `/art/{device}` | `i trackId, i width, i height, s path` (JPEG) |
| `/art/{device}/none` | `i trackId` |

- `kind`: `preview` (the whole track in a few hundred to 1200 columns) or
  `detail` (150 segments a second).
- `style`: the style actually sent: `three_band` (rekordbox 6.7+), `rgb` or
  `blue`. It can differ from the requested style. Like the players, when a
  track has no waveform of the requested style, the bridge sends its blue
  waveform. `/waveform/{device}/none` means the track has no waveform at all.
  The file name also contains the style, for example `p2-detail-rgb-41.fxwf`.
  With `blue`, the detail waveform often still arrives as `rgb`: beat-link
  reads the detail from the media's analysis files and takes the color
  version there. The preview is blue.
- `segmentsPerSecond` and `durationMs` are 0 when unknown.

Waveform file (`.fxwf`), little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `FXWF` |
| 4 | 1 | version (1) |
| 5 | 1 | kind: 0 preview, 1 detail |
| 6 | 1 | style: 0 blue, 1 rgb, 2 three-band |
| 7 | 1 | flags: bit 0 set when band levels are present |
| 8 | 4 | u32 trackId |
| 12 | 4 | u32 segment count |
| 16 | 4 | f32 segments per second (0 = unknown) |
| 20 | 4 | u32 duration in ms (0 = unknown) |
| 24 | 4 per segment | u8 height, u8 low, u8 mid, u8 high |

Every style is normalized to the same segments: an overall level and the
levels of the low, mid and high bands on their own, all 0-255 on one scale
where 255 is the loudest value in that waveform. Three-band waveforms carry
real band levels, weighted the way the players draw them. RGB waveforms carry
rekordbox's color channels (red = low, green = mid, blue = high). Blue
waveforms have levels only (bands 0, flag clear).

Beat grid file (`.fxbg`), little-endian: `FXBG`, u8 version (1), 3 reserved
bytes, u32 trackId, u32 beat count, then 8 bytes per beat: u32 time in ms,
u16 tempo in BPM x 100, u8 beat in bar (1-4), 1 reserved byte.

Song structure extras that `/phrases` already carries: the rekordbox Lighting
`bank` (style picked per track in rekordbox Lighting mode), the track `mood`,
and each phrase's fill-in (`fill`, `fillBeat`).

## Build

```
./mvnw -B package          # or mvnw.cmd on Windows
```

Produces `target/fxc-djlink-bridge-<version>.jar` (all dependencies included)
and `target/third-party-sources/` (source jars of every bundled dependency).

## Release

Push a `v*` tag. The release workflow runs the tests, builds the jar, and
attaches the jar, its SHA-256, and `third-party-sources.zip` to a GitHub release.

## License

Eclipse Public License 2.0. See `LICENSE.md`, and `NOTICE.md` for the bundled
libraries and their licenses.
