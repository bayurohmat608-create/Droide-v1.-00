# SPAKE2 BoringSSL compatibility replacement

Droide uses this source as a independently implemented compatibility layer for the public Java ABI consumed by Kadb 2.1.4.

Protocol behavior is based on Android/AOSP's Apache-2.0 Wireless Debugging pairing implementation and BoringSSL's permissively licensed legacy SPAKE2-over-Edwards25519 implementation. The Java structure was independently written for Droide while consulting the BSD-3-Clause `Jellepepe/flutter_adb` SPAKE2 implementation as a permissive cross-language reference.

No source from `com.github.Flyfish233:spake2-java:1.1.1` is copied into this replacement. The old GPL-3.0 artifact is removed from Droide's production dependency repository and dependency graph.

This replacement is intentionally release-gated pending exact physical-device Android Wireless Debugging interoperability evidence and independent security review. Its BigInteger arithmetic is correctness-oriented and is not claimed to be constant-time.

The BSD-3-Clause license notice for the independent `Jellepepe/flutter_adb` reference is preserved verbatim at `UPSTREAM_LICENSES/Jellepepe-flutter_adb-BSD-3-Clause.txt`.
