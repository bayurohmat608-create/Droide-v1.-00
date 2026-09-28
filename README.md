# Droide

Droide is a mobile IDE for Android built by **BayStudio** and engineered by **BayZov**. The project is designed around a professional editor/workbench, terminal, Agent tooling, language intelligence, package/toolchain management, Git workflows, build/debug integration, and an Android-first execution environment.

## Project status

- Version: **Droide v1.00** (`versionCode 29`)
- Android: `minSdk 29`, `targetSdk 36`, `compileSdk 36`
- JVM toolchain: Java 17
- Source tree: release-clean, with internal recovery/checkpoint history excluded
- First-party license: **MIT**
- Binary distribution: currently fail-closed pending the items documented in `RELEASE_AND_COMPLIANCE.md`

## Architecture

Droide keeps the Android application/workbench separate from installable runtimes and toolchains. The app owns the workspace model, editor, terminal integration, Agent orchestration, package/runtime contracts, security boundaries and UI. User-selected language servers, toolchains and package ecosystems are resolved through managed or terminal-driven installation paths rather than being hard-coded into the base APK.

Key source areas:

- `app/` — Android application, workbench, editor, terminal, Agent, LSP/debug and runtime integration
- `backend/` — first-party backend helpers such as the GitHub account OAuth exchange worker
- `registry/` — package/runtime catalog data used by Droide
- `third_party/` — pinned local artifacts, provenance records, licenses and notices
- `tools/` — release-source, dependency-integrity and build-readiness checks

## Build

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

The release gate checks source hygiene, secret/build-output exclusions, dependency policy, vendored third-party integrity and Termux provenance. GitHub Actions runs the same release-source gate before tests, lint and release artifact assembly.

## Legal and redistribution

Droide-owned source code and original project material are licensed under the MIT License. Third-party components retain their original licenses and are not relicensed under MIT. See `LICENSE`, `LICENSE_SCOPE.md`, `app/src/main/assets/legal/`, and `third_party/`. Binary redistribution requirements are documented in `RELEASE_AND_COMPLIANCE.md`.

## Credits

**Developer / Publisher:** BayStudio  
**Engineering:** BayZov
