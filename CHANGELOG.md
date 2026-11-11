# Changelog

## 1.5.0 (2026-11-10)

- New simulator option `--sim-no-mixer`: no mixer (device 33) on the simulated
  network. Every player reports off air the whole time, as real players do
  when no DJM is on the DJ Link network (measured on two CDJ-3000s). Use it to
  test how a client behaves without on-air data. The `onair` command answers
  with an error in this mode.
- The simulator now sends what real players send, in the same order and at
  the same pace (measured on two CDJ-3000s):
  - `load` runs in stages over half a second. The deck reports `loading` and
    clears its metadata, phrases, beat grid, art and waveforms, then
    `no_track`, then the new track. Metadata and phrases follow, then the
    beat grid and art, then the waveforms. A `play` during the load starts
    the deck as soon as the track is there, before its title arrives.
  - A paused deck reports its position every 30 ms for half a second while
    the platter slows down.
  - When the tempo master stops, ends or loads a track, the lowest playing
    deck becomes master.
  - Metadata has the album, colour (`No Color`), genre and key.
- New simulator command `mixer <0|1>`: the mixer leaves or joins the running
  network, without a restart. When it leaves, every player goes off air. When
  it joins, all faders are down until an `onair` command.

## 1.4.0 (2026-11-05)

- Support for the CDJ-3000X. The bridge now uses a preview of beat-link 8.1.0,
  the first version that knows this player. The preview also brings beat-link's
  work on XDJ-AZ four-deck mode, Opus Quad device numbers, and new play states.
- Track data for CDJ-3000, CDJ-3000X and XDJ-AZ players comes directly from the
  players when every player on the network accepts these requests. Before, the
  bridge read the exported files from the media whenever it used a device
  number above 4. The CDJ-3000X exports an encrypted database that the bridge
  cannot read that way. If an older player joins the network, the bridge goes
  back to reading the exported files.
- New command `/waveforms style NAME` (`3band`, `rgb` or `blue`) picks the
  waveform style, so FXCanvas can show the same waveform as the players.
  The bridge fetches the waveforms of the loaded tracks again in the new
  style. A track without that style gets its blue waveform, and the
  `/waveform` message names the style actually sent. Three-band stays the
  default, so clients that do not send the command see no change. Waveform
  file names now contain the style. The simulator serves all three styles.
- An emergency loop (the player lost its media and loops what it has buffered)
  now counts as looping on `/deck`. The play state is `emergency_loop`.
- The bridge does not read the encrypted SQLite export database of the
  CDJ-3000X, XDJ-AZ or Opus Quad (beat-link's database-key path). The jar still
  contains no SQLite native libraries. If a database key is set in Beat Link
  Trigger's shared settings, beat-link tries that path, logs an error for each
  mounted media, and the bridge continues with direct requests.
