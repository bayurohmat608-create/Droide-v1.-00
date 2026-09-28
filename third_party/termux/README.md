# Termux terminal components

Droide v1.00 vendors pinned `terminal-emulator` and `terminal-view` v0.118.0 for the native Android PTY/TUI surface.

- AARs live under `app/libs/` so runtime terminal behavior does not depend on JitPack availability.
- matching source archives and licensing/provenance evidence are retained here.
- `SHA256SUMS` pins the four primary local artifacts.
- `UPSTREAM_PROVENANCE.json` binds the source JARs to exact Git blobs from upstream tag `v0.118.0` / commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`, binds AAR/class scope, and pins the four JNI ABI binaries.
- `tools/verify_termux_provenance.py` verifies the provenance offline and rejects unexpected Termux packages/classes.
- release R8 rules preserve the JNI entry points used by the terminal emulator.

Upstream `termux-app` is GPLv3-only by default, with an explicit Apache-2.0 Terminal Emulator exception directing readers to `terminal-view` and `terminal-emulator`. Droide bundles only those two modules, not `termux-shared`. Preserve the packaged upstream license statement, Apache-2.0 text, source archives and provenance evidence on redistribution.
