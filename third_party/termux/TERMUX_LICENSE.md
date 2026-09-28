# Termux terminal component license/provenance

Droide vendors only `terminal-emulator` and `terminal-view` from `termux/termux-app` v0.118.0. The upstream repository root is GPLv3-only by default, but its own `LICENSE.md` explicitly names `terminal-view` and `terminal-emulator` under the Apache License 2.0 Terminal Emulator exception. `termux-shared` is not bundled.

Release provenance is fail-closed and offline-verifiable:

- upstream tag: `v0.118.0`
- upstream commit: `6e2689f55295fa444be8ac8592c527c2c5ef3253`
- exact upstream root `LICENSE.md` is packaged as `app/src/main/assets/legal/licenses/Termux-upstream-LICENSE.md`
- `third_party/termux/UPSTREAM_PROVENANCE.json` pins every Java source blob in the two source JARs, both AAR hashes, all four `libtermux.so` ABI hashes, and the upstream JNI source blob identities
- `tools/verify_termux_provenance.py` verifies that every AAR class maps to one of the exact pinned upstream source files (except generated `BuildConfig`) and rejects `termux-shared` classes

The applicable bundled-module license text exposed in Droide's License Center is Apache License 2.0. The broader Termux repository's GPLv3 default remains documented in the exact upstream root license statement so the exception is not presented out of context.

This provenance record does not grant trademark rights and does not relicense upstream code.
