# Droide distribution compliance status

**Current version:** Droide v1.00 (`versionCode 29`)  
**Third-party runtime inventory:** **CLOSED / VERIFIED FOR THE BUNDLED TREE**  
**Public binary release:** **FAIL-CLOSED UNTIL RELEASE-BUILD CLOSURE IS GENERATED**

Droide now maps every directly declared app runtime dependency and every bundled native/rootfs runtime aggregate to legal metadata. The runtime inventory is generated from the exact binary archives shipped in the tree, not from a hand-maintained approximation.

## Direct application dependencies

`tools/verify_dependency_policy.py` requires every `implementation(...)` dependency, including local JAR/AAR files, to map exactly once to `legal/open_source_licenses.json`. A new or stale mapping fails the release gate. XZ for Java 1.12 is explicitly mapped as 0BSD, and the exact Termux local AAR filenames are mapped to the reviewed Apache-2.0 terminal-module exception.

## Bundled Linux/native runtime

`legal/runtime_legal_inventory.json` is deterministically generated from the packaged runtime artifacts and records hashes plus package/source metadata for:

- PRoot/OpenMinis native runtime and loaders, with matching source preserved under `third_party/native-engines/proot/`.
- talloc 2.4.2, with matching source preserved beside the PRoot source.
- QEMU 11.0.3, with matching upstream source preserved under `third_party/native-engines/qemu/`.
- Alpine Linux 3.24.2 minirootfs: 16 installed packages with exact package-declared licenses and aports source commits.
- Alpine QEMU offline pack: 52 exact APK packages with SHA-256, repository, package-declared license and aports source commit.
- Ubuntu Base 24.04.5 arm64: 91 installed binary packages mapped to exact Ubuntu source package/version locators.

`tools/verify_runtime_licenses.py` regenerates this inventory from the shipped bytes and fails if it changes, if required license/source-offer assets disappear, or if required corresponding-source archives are missing.

## Copyleft source availability

The complete source-offer terms are packaged in `legal/COPYLEFT_SOURCE_OFFER.md`. PRoot/OpenMinis, talloc and QEMU corresponding source archives are already included in the public tree. Exact package source locators are recorded for Alpine and Ubuntu aggregate packages. BayStudio's written offer covers any distributed copyleft component whose corresponding source is not already present in the same release and remains valid for at least three years after the last distribution of that binary version.

Sora Editor 0.24.6 remains LGPL-2.1-or-later. Its exact version and license are pinned in the catalog. Droide-owned source uses the MIT License, which does not remove recipients' LGPL rights or the library's relinking requirements.

## Termux terminal scope

The Termux `terminal-emulator` and `terminal-view` 0.118.0 artifacts are scoped to the Apache-2.0 Terminal Emulator exception identified by upstream. `third_party/termux/UPSTREAM_PROVENANCE.json` pins the reviewed source/class/native scope, and `tools/verify_termux_provenance.py` verifies it offline.

## Remaining release-build gate

The source tree cannot generate the resolved Gradle transitive graph in an offline environment. Before publishing an APK/AAB, the trusted online release build must run `tools/generate_dependency_trust.sh`, commit/review dependency locks and `gradle/verification-metadata.xml`, and rerun all release gates. This is a build-environment closure step, not an unmapped bundled-runtime license issue.

## Droide-owned source license

Droide-owned source code and original project material are licensed under the MIT License. Third-party software and assets remain under their respective licenses; see `legal/DROIDE_LICENSE.md`, `legal/THIRD_PARTY_NOTICES.md`, and the repository `LICENSE_SCOPE.md`.

## Release rule

Preserve `THIRD_PARTY_NOTICES.md`, `COPYLEFT_SOURCE_OFFER.md`, `runtime_legal_inventory.json`, `open_source_licenses.json`, all packaged license texts, and all third-party provenance/source records in public distributions.
