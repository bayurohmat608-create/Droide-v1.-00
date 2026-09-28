# Upstream references pinned for compatibility review

- AOSP pairing layer: `platform/packages/modules/adb/pairing_auth/pairing_auth.cpp` (Apache-2.0).
- BoringSSL legacy SPAKE2: `crypto/curve25519/spake25519.{c,cc}` (BoringSSL permissive license family).
- Independent reference: `Jellepepe/flutter_adb`, commit `d8c8c3ebb0cd59d99dedd63ae0d9450067a39d0d`, `lib/spake2.dart` (BSD-3-Clause).

The ADB-specific participant identifiers include their C string terminators: `adb pair client\0` and `adb pair server\0`.
