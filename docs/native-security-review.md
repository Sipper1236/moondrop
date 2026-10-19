# Native dependency review — 2026-10-19

This records the native networking dependency investigation, including unresolved
findings. It is not a claim that a module inventory alone proves exploitability.

## Starting artifacts and upstream availability

The published Android engine AAR reports Go 1.25.5 in `go version -m`. The
previously downloaded official desktop sing-box 1.14.3 reports Go 1.26.8.
The Android inventory originally omitted the Go standard library and toolchain.

The latest official stable sing-box release checked was still
[1.14.3, commit 7054cac5124465ffdb145870d7c01885566711b1](https://github.com/SagerNet/sing-box/releases/tag/v1.14.3).
The newer [1.15.0-alpha.12](https://github.com/SagerNet/sing-box/releases/tag/v1.15.0-alpha.12)
and current testing branch did not provide an upstream release containing all
required dependency fixes. Switching to that alpha is therefore not a solution.

## Inventory findings and minimum fixes

The original Android module scan returned 24 advisory identifiers: four GitHub
advisories explicitly rated HIGH/CRITICAL and twenty unscored Go records.
Four Go records alias the four GitHub records, so those 24 IDs do not represent
24 distinct high-severity vulnerabilities.

| Component | Original | Security patch target | Primary advisory evidence |
| --- | --- | --- | --- |
| Go runtime/toolchain | Android 1.25.5; desktop 1.26.8 | 1.26.9 | [Official downloads](https://go.dev/dl/?mode=json), [HTTP/2 runtime fixes](https://pkg.go.dev/vuln/GO-2026-6603) |
| google.golang.org/grpc | v1.79.1 | v1.83.2 | [xDS panic](https://github.com/grpc/grpc-go/security/advisories/GHSA-2v4p-qf9q-27wj), [HTTP/2 memory exhaustion](https://github.com/grpc/grpc-go/security/advisories/GHSA-vp52-pcj8-j9qc), [authorization bypass](https://github.com/grpc/grpc-go/security/advisories/GHSA-p77j-4mvh-x3m3) |
| github.com/go-chi/chi/v5 | v5.2.5 | v5.3.0 | [RealIP spoofing](https://pkg.go.dev/vuln/GO-2026-5774) |
| golang.org/x/crypto | v0.54.0 | v0.57.0 | [SSH channel DoS](https://pkg.go.dev/vuln/GO-2026-6354); v0.57.0 also satisfies x/net v0.60.0's required minimum |
| golang.org/x/mod | v0.37.0 | v0.41.0 | [Checksum transparency verification](https://pkg.go.dev/vuln/GO-2026-6179) |
| golang.org/x/net | v0.57.0 | v0.60.0 | [HTTP/2 fixes](https://pkg.go.dev/vuln/GO-2026-6610) |
| golang.org/x/text | v0.40.0 | v0.42.0 | [PRECIS panic](https://pkg.go.dev/vuln/GO-2026-6629) |

OSV queries on this date returned no records for stdlib/toolchain 1.26.9 or
1.27.2, grpc v1.83.2, chi v5.3.0, x/mod v0.40.0, x/net v0.60.0, and x/text
v0.41.0. That describes the queried database state, not a guarantee against
unknown vulnerabilities. The consistent dependency solution raises x/crypto to v0.57.0, x/mod to v0.41.0, and x/text to v0.42.0 because x/net v0.60.0 requires those versions. Required transitive updates are enumerated in `android/native/security-patch.json`; they must also be scanned.

Querying the omitted `stdlib@1.25.5` returned 46 Go advisory IDs; querying
`toolchain@1.25.5` returned 13. Several concern TLS, HTTP clients, compiler memory
safety, or checksum verification. Rebuilding with a maintained patched compiler
is necessary in addition to updating module versions.

## The unfixed OpenPGP record

[GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932) concerns the unmaintained
`golang.org/x/crypto/openpgp` package and its subpackages. Its affected range has
no fixed version. The x/crypto module is also required for supported modern
cryptography, so rejecting every version of that entire module cannot be
resolved by an ordinary dependency upgrade.

An exception must be supported by package/call reachability evidence, or the
package must be removed/replaced. Merely asserting that Moondrop does not use
OpenPGP is insufficient. The patched engine's source scan reports no vulnerable
symbols reachable from `moondropnative`; its full tagged `go list -deps` inventory
contains 833 module packages and no OpenPGP import path. This supports a
source-reachability exception for this build, not a claim that the module itself
is free of vulnerabilities.

## Scanner limitations

The shipped AAR is stripped using `-s -w`. Running official govulncheck v1.9.0
on that library yielded module-level fallback results. Its generated wildcard
“symbol” findings are not evidence that every reported symbol was linked.
The [official scanner implementation](https://github.com/golang/vuln/blob/v1.9.0/internal/vulncheck/binary.go)
explicitly falls back to go.mod precision when symbols cannot be extracted.
Do not use this scan to justify an OpenPGP reachability exception.

## Validation status

A minimal dependency security backport on the pinned 1.14.3 source now builds
the Android AAR with Go 1.26.9. It changes dependency locks, not sing-box
protocol implementation. The checked-in patch script and lock metadata identify
the source and module changes. Bridge tests, all three Android ABI builds, and
the tagged govulncheck source scan pass. The scan finds no vulnerable symbols
reachable from the native entry package and one vulnerable required module
whose vulnerable symbols are not called. The exact package inventory is
`android/app/libs/moondrop-engine.packages.txt`; the shipped SBOM includes the
Go toolchain, standard library, and reachable native packages.

The Android app now checks GitHub Releases at startup and on request, verifies
the asset digest and APK signing certificate, and hands installation to Android
for explicit user approval. The currently published Android APK uses publisher
certificate SHA-256 `8b9adde046750ac8df0a5f95850459f2cfe29b0c42305ce354ab98a872f5e3cb`;
the local debug key differs and this repository has no signing secret. The CI
release build remains unsigned. Do not expect in-place updates until the
matching signing key is supplied to a release build; do not replace it with a
new key without requiring users to reinstall.

The desktop installer and Linux strict-mode broker still use the upstream
official sing-box 1.14.3 binary built with Go 1.26.8. This Android patch does not
remediate those desktop binaries. Android instrumentation validation and the
final SBOM/advisory check remain in progress; do not publish a patched release
until they pass and the desktop engine scope is addressed or disclosed.
