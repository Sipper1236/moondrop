# Cached server list — 2026-10-16

Moondrop 0.7.1 persists a private local server catalog in addition to speed measurements.

- Full local package check: 141 tests passed, none skipped, including native encrypted-connection tests and eight GUI/controller scenarios under Xvfb.
- Controller tests hold a feed request open and confirm that saved servers and their measurements are visible, Connect remains enabled, and the connection worker accepts work before the refresh completes.
- A failed refresh preserves the saved list, saved speeds, and connect controls; a successful refresh replaces the catalog.
- Cache tests cover profile/credential round trips, POSIX owner-only permissions, protected protocols only, corrupt/oversized files, and preserving the previous snapshot after failed writes.
- Fresh speed results retain their existing seven-day lifetime. A first refresh is required to populate the server-list cache when upgrading from versions that only saved measurements.

# Usability and Advanced mode — 2026-10-16

Moondrop 0.7.0 was checked locally on Linux with Java 17.

- Full suite: 134 tests, zero failures; 20 engine-dependent cases initially skipped.
- Re-ran all three native engine test classes with the app’s sing-box binary: 22 tests passed, none skipped, including the 20 engine-dependent cases.
- Six GUI scenarios under Xvfb cover Connect/Cancel/Disconnect, the system proxy default, Advanced mode visibility and preference preservation, first-run testing feedback, expandable details, test age, and forced retesting from an error dialog.
- Added regressions for connecting before download tests complete, excluding stale/failed cached connections, retry cancellation, and diagnostic credential exclusion.
- Inspected the simplified layout using example servers and measurements; `preview.png` is an interface fixture, not a live server benchmark.
- GitHub Actions runs native encrypted connection and bundled updater checks on Linux, Windows x64, macOS Apple Silicon, and macOS Intel. The Linux job also runs the GUI scenarios under Xvfb. These automated checks do not validate browser-specific system proxy behavior or macOS network permission prompts.

Earlier validation notes follow as historical records.

# Local validation — 2026-10-15

Validated on Linux x64, Java 17, sing-box 1.14.3. This machine already had its own external TUN proxy running; it was left unchanged.

- Maven package succeeded; all eight tests passed with `-Dengine.test.path=/usr/bin/sing-box`, none skipped.
- Tests cover SIP002 and legacy Shadowsocks URLs, IPv6, escaped credentials, invalid links, platform detection, port conflicts, a real encrypted round trip, and recovery of an owned leftover engine.
- Official engine download succeeded, SHA-256 matched the pinned release hash, binary extraction succeeded.
- Live API fetch returned 156 compatible active endpoints at the time of testing. Quick Connect tested 20 responsive candidates, found five HTTPS successes in the command-line smoke test, and verified its winner over HTTPS again.
- Clicked Quick Connect in the real Swing interface under Xvfb. It connected successfully; screenshot `quick-connect.png` captures that live connection. Clicked Disconnect, then ran and cancelled another search. Port 17890 was no longer listening and no session configs remained afterward.
- Initially built a native Linux app image with `jpackage`. This packaging has since been replaced by a portable ZIP that requires separately installed Java; current downloads do not include Java.

The original macOS/Windows workflow ran after upload; its runtime-bundling packaging is now replaced by the portable ZIP workflow. Native network-settings permissions, Windows PAC/automatic discovery interactions, signed distribution, and target-platform acceptance tests remain to be validated. These are development builds, not published releases.

## Latency and caching update — 2026-10-15

- All 25 local tests passed, including real encrypted traffic, cache expiry/credential invalidation, cancellation cleanup, and preserving completed measurements during forced retests.
- Live background checks populated actual per-server HTTPS latencies and sorted working servers by latency.
- Reopened the app: all 36 baseline successful measurements were reused with unchanged timestamps. Remaining stale/unmeasured servers continued in the background.
- Clicked Quick Connect with saved results: the activity log confirmed cached ranking was used and the selected connection was verified again.
- Updated preview shows live results; values are latency in milliseconds, not bandwidth estimates.
