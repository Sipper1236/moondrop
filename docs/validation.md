# Local validation — 2026-10-15

Validated on Linux x64, Java 17, sing-box 1.14.3. This machine already had its own external TUN proxy running; it was left unchanged.

- Maven package succeeded; all eight tests passed with `-Dengine.test.path=/usr/bin/sing-box`, none skipped.
- Tests cover SIP002 and legacy Shadowsocks URLs, IPv6, escaped credentials, invalid links, platform detection, port conflicts, a real encrypted round trip, and recovery of an owned leftover engine.
- Official engine download succeeded, SHA-256 matched the pinned release hash, binary extraction succeeded.
- Live API fetch returned 156 compatible active endpoints at the time of testing. Quick Connect tested 20 responsive candidates, found five HTTPS successes in the command-line smoke test, and verified its winner over HTTPS again.
- Clicked Quick Connect in the real Swing interface under Xvfb. It connected successfully; screenshot `quick-connect.png` captures that live connection. Clicked Disconnect, then ran and cancelled another search. Port 17890 was no longer listening and no session configs remained afterward.
- Built a native Linux app image with its own Java runtime using `jpackage`.

macOS/Windows packaging workflow is included but has not run from this local workspace. Native network-settings permissions, Windows PAC/automatic discovery interactions, signed distribution, and target-platform acceptance tests remain to be validated. These are development builds, not published releases.
