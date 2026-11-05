# Changelog

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
