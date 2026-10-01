# Droide

Droide is a mobile IDE for Android built by **BayStudio** and engineered by **BayZov**. The project is designed around a professional editor/workbench, terminal, Agent tooling, language intelligence, package/toolchain management, Git workflows, build/debug integration, and an Android-first execution environment.

## Project status

- Version: **Droide v1.00** (`versionCode 29`)
- Android: `minSdk 29`, `targetSdk 36`, `compileSdk 36`
- JVM toolchain: Java 17
- Source tree: public-release source with internal process artifacts excluded
- First-party license: **MIT**
- Binary distribution: currently fail-closed pending the items documented in `RELEASE_AND_COMPLIANCE.md`

## Architecture

Droide keeps the Android application/workbench separate from installable runtimes and toolchains. The app owns the workspace model, editor, terminal integration, Agent orchestration, package/runtime contracts, security boundaries and UI. User-selected language servers, toolchains and package ecosystems are resolved through managed or terminal-driven installation paths rather than being hard-coded into the base APK.

This source includes the ARM64 PRoot engine, Ubuntu/Alpine base images and an offline QEMU engine pack. They activate on demand from packaged assets. Compilers, language servers and Android SDK/NDK toolchains remain user-installed. The QEMU engine pack has an app-private system-VM orchestration layer with qcow2 overlays, TCG x86_64 emulation, QMP lifecycle control, user networking, process leases and pinned-image admission. A fixed Alpine 3.24.2 x86_64 tiny-cloud provider can resolve the official SHA-512 sidecar/metadata and download the qcow2 on demand through the same user-visible artifact-transfer path; the image is not bundled. Runtime boot health requires both QMP `running` state and fresh serial-console identity markers, but `vm_booted=0` and `debug_certified=0` remain authoritative until physical Android evidence succeeds.

Local Linux Run, Tasks and formatting use Ubuntu. Android Gradle builds now prefer a healthy local Ubuntu toolchain discovered from the same managed receipts/workspace selections and guest environment used by the terminal; a standard project Gradle Wrapper is checked against the selected JDK using Gradle's published runtime-compatibility ranges before local readiness/build, and the wrapper is executed with explicit `JAVA_HOME`/`ANDROID_HOME` plus the AGP `android.aapt2FromMavenOverride` escape hatch only after the selected AAPT2 has executed successfully in the guest; custom wrapper distributions are not assigned an invented static version and remain subject to the real execution check. This AGP option is treated as experimental, not a stable public contract. If local host tools are unavailable, Device Workstation is an explicit fallback on supported/connected devices. Install/run/JDWP remain Device Workstation operations. Official Android Linux SDK/NDK downloads are not assumed to be ARM64-compatible: local readiness is fail-closed on actual host-tool execution. Managed package support remains intentionally fail-closed: the monolithic Android toolchain catalog is still empty, while the separate local-component catalog admits only byte-pinned SDK Platform 36 and Build Tools 36.0.0. The Build Tools provider extracts Google architecture-neutral Java/metadata files, retains the original aapt/dexdump bytes as non-executable SDK-layout compatibility files, and replaces the four normal-build native executables (`aapt2`, `aidl`, `zipalign`, `split-select`) with exact-digest linux-glibc-arm64 artifacts built from public AOSP source by Commit451/android-arm-build-tools; no Android development toolchain is bundled in the APK. Legacy AAPT-v1/dexdump execution is unsupported by this local provider. Local discovery requires both Java and a runnable `javac` from the same JDK with matching major versions, as well as successful execution of the supported ARM64 native binaries and `apksigner`, before an Android build is READY. The packaged ARM64 `droide` terminal client talks to a workspace-bound, app-UID-authenticated LocalSocket endpoint; `pkg`, `plugin`, and `runtime` management all route through the same unified package authority and receipts. Long-running build/package/VM operations keep durable ownership state keyed by a per-app-process identity rather than PID alone, so PID reuse after process death cannot make stale work look live. User-started builds, tests, lint, debug/install work and system-VM sessions require a visible `specialUse` foreground service with a Stop action when that policy is selected; if Android synchronously rejects the foreground-service start, the protected operation is not launched; later promotion failure cancels the owned operation, and multiple overlapping developer operations share one aggregate notification without losing individual cancellation callbacks. Idle terminals, LSP servers and ordinary Run File actions are not promoted automatically. Package/toolchain/VM-image downloads now use Android 14+ user-initiated data-transfer jobs with a required notification, progress reporting, cancellation, durable resumable state and exact digest verification; Android 13 and earlier use a visible `dataSync` foreground-service fallback. Installer/receipt commit still stays in the existing package authority rather than a parallel downloader authority. Android may still stop jobs or the whole app process, so persisted transfer state is recovery state, not a process-immortality claim. Terminal process-death recovery still means relaunching fresh shells, not claiming that killed processes survived. See `RELEASE_AND_COMPLIANCE.md` for Play declaration and device-QA gates.

Key source areas:

- `app/` — Android application, workbench, editor, terminal, Agent, LSP/debug and runtime integration
- `backend/` — first-party backend helpers such as the GitHub account OAuth exchange worker
- `registry/` — package/runtime catalog data used by Droide
- `third_party/` — pinned local artifacts, provenance records, licenses and notices
- `tools/` — release-source, dependency-integrity and build-readiness checks

## Runtime boundaries

Foreground-service start acceptance is distinct from successful foreground promotion. A synchronous start rejection prevents the protected operation from launching; a later promotion failure cancels its owned job. The coordinator releases its operation lock on either path. A promotion acknowledgement before beginning work remains a follow-up requirement.

## Build

Keyboard input profiles are documented in [KEYBOARD_INPUT.md](docs/KEYBOARD_INPUT.md). The editor and all terminal backends default to Compact; users can select No corrections or Normal in Settings. Actual number-row visibility is controlled by the installed keyboard.

A trusted build environment needs JDK 17 and Android SDK 36 / Build Tools 36.0.0.

```bash
python3 tools/build-readiness/prepare_build.py source
python3 tools/build-readiness/prepare_build.py environment --android-sdk-root "$ANDROID_SDK_ROOT"
./gradlew :app:assembleDebug
```

Release builds additionally require the signing properties documented in `app/build.gradle.kts`. Do not commit signing material or live credentials.

## Source verification

```bash
./tools/verify_release_source.sh
```

The release gate checks source hygiene, secret/build-output exclusions, dependency policy, vendored third-party integrity, Termux provenance, and packaged Linux/QEMU hashes against runtime admission constants. GitHub Actions runs the same release-source gate before tests, lint and release artifact assembly.

## Legal and redistribution

Droide-owned source code and original project material are licensed under the MIT License. Third-party components retain their original licenses and are not relicensed under MIT. See `LICENSE`, `LICENSE_SCOPE.md`, `app/src/main/assets/legal/`, and `third_party/`. Binary redistribution requirements are documented in `RELEASE_AND_COMPLIANCE.md`.

## Credits

**Developer / Publisher:** BayStudio  
**Engineering:** BayZov
