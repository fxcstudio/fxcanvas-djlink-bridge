# Notices

FXCanvas DJ Link Bridge
Copyright (c) 2026 FXCanvas Studio.
Distributed under the Eclipse Public License 2.0 (see `LICENSE.md`).

The released jar (`fxc-djlink-bridge-<version>.jar`) bundles the following
libraries **unmodified**. Their source code is attached to every GitHub
release of this project as `third-party-sources.zip`, and is also available
from the locations below.

| Library | Version | License | Source |
|---|---|---|---|
| beat-link | 8.1.0 preview (snapshot build 8.1.0-20261027.135635-6) | EPL-2.0 | https://github.com/Deep-Symmetry/beat-link |
| crate-digger | 0.2.2 preview (snapshot build 0.2.2-20261026.025444-2) | EPL-2.0 (secondary: MPL-2.0, LGPL-3.0) | https://github.com/Deep-Symmetry/crate-digger |
| electro | 0.1.4 | EPL-2.0 | https://github.com/Deep-Symmetry/electro |
| Remote Tea (remotetea-oncrpc) | 1.1.4 | LGPL-2.0 | https://remotetea.sourceforge.net/ |
| Kaitai Struct Java runtime | 0.10 | MIT | https://github.com/kaitai-io/kaitai_struct_java_runtime |
| sqlite-jdbc (io.github.willena) | 3.50.1.0 | Apache-2.0 | https://github.com/Willena/sqlite-jdbc-crypt |
| Apache Commons Math | 3.6.1 | Apache-2.0 | https://commons.apache.org/proper/commons-math/ |
| SLF4J API, JDK14 binding, ext | 1.7.36 / 2.0.13 | MIT | https://www.slf4j.org/ |
| API Guardian | 1.1.2 | Apache-2.0 | https://github.com/apiguardian-team/apiguardian |

sqlite-jdbc is included without its native SQLite libraries
(`org/sqlite/native/**`), so the jar contains no native code. beat-link uses
SQLite only to read the encrypted export database of CDJ-3000X, XDJ-AZ and
Opus Quad players when a database key is configured. The bridge does not
support that path: it requests track data from those players directly.

Remote Tea is licensed under the GNU Library General Public License 2.0. It is
included as unmodified classes inside the jar. You may replace those classes
with a modified version of Remote Tea by rebuilding the jar from this
repository's source with a different dependency.

This project is not affiliated with, endorsed by, or supported by Pioneer DJ,
AlphaTheta, or rekordbox. Those names are trademarks of their owners and are
used here only to describe compatibility.
