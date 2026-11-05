# FXCanvas DJ Link Bridge

A small headless Java program that joins a Pro DJ Link network and reports
what the players are doing over OSC on the local machine. FXCanvas starts it
as a child process for its Pioneer DJ tempo source.

It is built on Deep Symmetry's [beat-link](https://github.com/Deep-Symmetry/beat-link)
and speaks the same OSC messages as Deep Symmetry's
[open-beat-control](https://github.com/Deep-Symmetry/open-beat-control) for the
features FXCanvas uses, plus three additions:

- `/version`: bridge name, version and protocol number.
- `/phrases`: rekordbox phrase analysis (song structure) for each loaded track.
- `/positions`: the beat-grid beat each player is on, every beat and after every jump.

> **Unofficial.** This project is not affiliated with, endorsed by, or supported
> by Pioneer DJ, AlphaTheta, or rekordbox. Use it at your own risk; see the
> compatibility notes in beat-link's README.

## Run

Java 11 or later.

```
java -jar fxc-djlink-bridge-1.0.0.jar [-o 17002] [-r] [-d NUM] [-L file.log]
```

The OSC server listens on `127.0.0.1` only. Send `/respond <port>` first to
choose where responses go (default: the sender's port plus one).

| Option | Default | Meaning |
|---|---|---|
| `-o, --osc-port PORT` | 17002 | OSC server port |
| `-r, --real-player` | off | Try to use player number 1-4 |
| `-d, --device-number NUM` | auto | Fixed device number (overrides `-r`) |
| `-L, --log-file PATH` | stderr | Log to a rotating file |
| `-l, --latency MS` | 20 | Accepted for compatibility |
| `-B -a -b -c PORT` | | Accepted and ignored (FXCanvas bridges Ableton Link itself) |

## OSC protocol (version 1)

Subscriptions follow open-beat-control: send `/<topic> stream [port]`,
`/<topic> list [port]` or `/<topic> stop [port]`. `stream` also sends the
current state. Topics: `/devices /tempos /playing /on-air /metadata /master
/beats /phrases /positions`.

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

`/position/{device} i trackId, i beatNumber, i playing, i definitive, i reason`:
`reason` is 0 for a beat and 1 for a movement (cue, jump, loop, stop).

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
