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
- An emergency loop (the player lost its media and loops what it has buffered)
  now counts as looping on `/deck`. The play state is `emergency_loop`.
- The jar now includes the SQLite native libraries for Windows x64 and macOS
  arm64 and x64. The jar for 1.4.0 is about 5.6 MB.
