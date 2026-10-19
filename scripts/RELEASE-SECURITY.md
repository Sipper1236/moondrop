# Publisher signatures and release checks

Desktop updates now require an independent Ed25519 signature, not just the checksum published beside a JAR. The immutable trusted X.509 public key is `ReleaseSignatures.PUBLIC_KEY`. There is no download-host key discovery or checksum-only fallback. The detached signature covers the **exact bytes** of `release-manifest.txt`:

```
moondrop-release-v1
version 0.7.3
<64 lowercase SHA256 hex>  moondrop.jar
<64 lowercase SHA256 hex>  Moondrop.zip
```

Each artifact has one basename-only entry; the file ends in LF. The version has no `v` prefix. A signature is the raw 64-byte Ed25519 signature in `release-manifest.sig`.

After collecting the final artifacts, SBOMs and provenance (including the final Android-signed APK, if applicable) in an otherwise empty directory:

```
python3 scripts/sign-release.py --version VERSION \
  --key /private/path/releases-ed25519.pem --directory RELEASE_DIR
```

The signer regenerates `SHA256SUMS` for older clients, but those checksums are not publisher authentication. Upload every output, including both manifest files. Keep the private key offline, backed up, and outside the repository. It is deliberately not available to ordinary CI builds. Changing the embedded key requires a trusted application update; protect and retain the original key.

New clients verify signatures before staging, before reusing downloads, and again inside the standalone installer before replacing an installation. The signed sidecars travel with the staged JAR. Older clients do not understand publisher signatures and retain their existing checksum-only trust until this release is installed; a direct installation verified against an independently obtained public key is the safest migration.

## Build provenance and dependency inventory

Workflow actions are pinned to immutable commit SHAs. Dependabot proposes updates; `check-action-pins.py` rejects floating references. PR dependency review rejects newly introduced high-severity dependencies. Builds generate CycloneDX inventories and query OSV, failing on high/critical findings or findings whose severity cannot be established. Network/API errors fail the check rather than passing an unscanned build.

On trusted push builds, GitHub artifact attestations bind the output digest to the repository, workflow and commit. Verify downloaded CI artifacts with:

```
gh attestation verify ARTIFACT --repo Sipper1236/moondrop
```

The final locally signed APK has a different digest from its unsigned CI input; the publisher release manifest authenticates that final APK. Do not describe the unsigned APK's attestation as covering the final signed APK.

Rebuild a release from its published tag using JDK 17+ and the exact Maven/Gradle, SDK, NDK and pinned engine versions documented in the project. Desktop: `mvn -B package`; Android: `bash android/native/build.sh`, then `cd android && ./gradlew --no-daemon assembleRelease`. Compare extracted JAR classes/resources and embedded native libraries as well as the dependency SBOM. Current packaging includes timestamps, platform Java runtimes and installer metadata, so byte-for-byte reproducibility is **not yet guaranteed**. A matching source tag and provenance are not a claim of reproducibility.

Windows Authenticode and Apple Developer ID signing/notarization still require the publisher's paid signing identity, credentials and provisioning. These files do not create those identities or claim that existing EXE/DMG installers are signed/notarized.
