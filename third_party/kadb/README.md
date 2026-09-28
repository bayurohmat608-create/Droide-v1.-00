# Kadb 2.1.4 integration

Droide 4.6.2 uses Kadb for direct Android Wireless Debugging communication: mDNS discovery, pairing, shell, sync, APK install, launch/logcat support and forwarding.

The official `kadb-android` and `kadb-mdns-android` AAR metadata declares `minCompileSdk=37`, while Droide intentionally remains on the API-36 baseline supported by AGP 8.13.2. Droide therefore embeds the **exact unmodified `classes.jar` bytecode payloads** under local compatibility Maven coordinates; it does not consume the incompatible AAR manifest metadata. Original source archives and upstream metadata are retained for audit/compliance.

Runtime supporting artifacts are pinned under `third_party/maven`: Okio 3.17.0, Droide SPAKE2 BoringSSL compatibility 1.0.0, AndroidX DocumentFile 1.1.0, HiddenApiBypass 6.1, Kotlin coroutines 1.11.0, and Bouncy Castle bcprov/bcpkix/bcutil 1.84 where required by the compatibility POM.  `EMBEDDED_SHA256SUMS.txt` is checked by `tools/verify_static.sh`. ADB host identity is persisted using Droide's Android-Keystore encrypted store rather than Kadb's in-memory default.

Kadb is Apache-2.0 licensed. The Droide SPAKE2 compatibility component is separately BSD-3-Clause and retains complete source, license, deterministic vectors, and provenance references. Preserve all upstream/component notices and source references when redistributing.

---
Synchronized with the Droide 4.6.2 R6 Professional Workbench documentation checkpoint on 2026-09-16.
