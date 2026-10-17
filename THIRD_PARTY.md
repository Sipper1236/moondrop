# Third-party software

- **Eclipse Temurin OpenJDK 21** — bundled only in the Windows and macOS native installers; GPL-2.0 with the Classpath Exception and additional component notices. The portable ZIP/JAR contains no Java runtime. Runtime license and notice files are retained in `runtime/legal` (inside the app bundle on macOS). The runtime's `release` file identifies the exact included version. Corresponding source: https://github.com/adoptium/jdk21u; distribution and build information: https://adoptium.net/temurin/. No local source modifications.

- **sing-box**, SagerNet — GPL-3.0-or-later, https://github.com/SagerNet/sing-box. Downloaded separately at runtime from the official v1.14.3 release. Binary archives are verified against pinned SHA-256 values from that release. Corresponding source: https://github.com/SagerNet/sing-box/tree/v1.14.3. The desktop core has no local modifications. Android bundles this core, built from commit `7054cac5124465ffdb145870d7c01885566711b1`, with Moondrop’s GPL bridge; build commands and feature tags are in `android/native/build.sh`. Its Go dependency versions and licenses are available in the pinned upstream source.
- **AndroidX Test** (Android tests only) and **Android desugar_jdk_libs** — Apache-2.0, https://github.com/android/android-test and https://github.com/google/desugar_jdk_libs.
- **FlatLaf** — Apache-2.0, https://github.com/JFormDesigner/FlatLaf.
- **Gson** — Apache-2.0, https://github.com/google/gson.
- **Apache Commons Compress**, **Commons IO**, **Commons Lang** — Apache-2.0, https://commons.apache.org/.
- **JUnit** (tests only) — EPL-2.0, https://junit.org/.
- **Shadowmere** — source/API reference, GPL-3.0, https://github.com/shadowmere-xyz/shadowmere. No upstream code is copied into the application.

The shaded JAR includes dependency license/notice files under META-INF. Moondrop is an independent client and is not endorsed by the above projects.

- **flag-icons**, lipis and contributors — MIT, https://github.com/lipis/flag-icons, revision `086f7e97d657358203916dbe84f61c2bccaa81eb`. Bundled 4:3 SVG flags; license in `src/main/resources/flags/LICENSE.txt`.

- **GetFreeProxy / gfpcom free-proxy-list** — MIT, https://github.com/gfpcom/free-proxy-list. Public Shadowsocks, Hysteria2, and TUIC subscriptions fetched at runtime; no upstream code or server list is bundled.
- **V2rayCollector**, MhdiTaheri — MIT, https://github.com/MhdiTaheri/V2rayCollector. Public Shadowsocks and mixed-protocol subscriptions fetched at runtime; no upstream code or server list is bundled.
- **morpheusadam/v2ray-config** — public Shadowsocks, VLESS, Trojan, Hysteria2, and TUIC bundles fetched at runtime from https://github.com/morpheusadam/v2ray-config. No upstream code or server list is bundled.

- **Google DSPL country coordinates** — BSD-3-Clause, https://github.com/google/dspl/blob/master/samples/google/canonical/countries.csv. Bundled country-center data for approximate proximity ordering; license in `src/main/resources/geography/LICENSE.txt`.
- **IPWHOIS** — https://ipwhois.io/documentation. Runtime HTTPS lookup of approximate country; only the country code and lookup time are cached, without an IP address.

- **Cloudflare Speed Test** — https://speed.cloudflare.com/. Its HTTPS download endpoint (`/__down?bytes=1048576`) is requested at runtime for bounded connection-speed estimates. No Cloudflare SDK or source code is copied or bundled.

The question-mark fallback flag (`src/main/resources/flags/xx.svg`) is an original Moondrop asset.

- **V2RayAggregator**, mahdibland — public subscriptions fetched at runtime from https://github.com/mahdibland/V2RayAggregator. No upstream code or server lists bundled.
- **Epodonios/v2ray-configs** — public subscriptions fetched at runtime from https://github.com/Epodonios/v2ray-configs. No upstream code or server lists bundled.
