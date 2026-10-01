# Droide distribution compliance status

**Current version:** Droide v1.00 (`versionCode 29`)  
**Third-party license status:** **CLEARED BY RELEASE-SOURCE GATES**  
**Overall binary release status:** **FAIL-CLOSED UNTIL NON-LICENSE RELEASE CHECKS PASS**

The third-party license layer is complete for the source tree and bundled runtime represented by this release. This statement is an engineering compliance status, not legal advice and not a substitute for jurisdiction-specific trademark/privacy/store review.

## First-party license

Droide-owned source code and original project material are licensed under the MIT License. Third-party software and assets remain under their respective licenses. See the repository `LICENSE`, `LICENSE_SCOPE.md` and packaged `legal/DROIDE_LICENSE.md`.

## Application dependency closure

`legal/open_source_licenses.json` maps every directly declared runtime dependency exactly once. `legal/resolved_runtime_licenses.json` maps every module in the locked Gradle `releaseRuntimeClasspath`; the current closure contains 134 resolved modules and unknown coordinates fail the release gate.

The catalog packages the required license families, including Apache-2.0, MIT, BSD-3-Clause, EPL-2.0, LGPL-2.1-or-later, 0BSD and component-specific Bouncy Castle/JGit terms. XZ for Java 1.12 is mapped as 0BSD and the actual Java-only Termux emulator AAR plus terminal-view AAR are mapped under the upstream Apache-2.0 Terminal Emulator exception.

## Copyleft and aggregate runtime

`legal/runtime_legal_inventory.json` is generated from the exact bundled runtime bytes. It records PRoot/OpenMinis, talloc, QEMU, 16 Alpine minirootfs packages, 52 Alpine QEMU packages and 91 Ubuntu Base packages, including hashes and source identities/locators.

Matching PRoot/OpenMinis, talloc and QEMU source archives are present under `third_party/native-engines/`. `legal/COPYLEFT_SOURCE_OFFER.md` covers corresponding source not already mirrored in the source release and remains valid for at least three years after the last BayStudio distribution of the relevant binary version.

Sora Editor 0.24.6 remains LGPL-2.1-or-later. Droide's public source, pinned dependency declarations and replaceable build path preserve recipients' ability to rebuild against a compatible modified Sora library; the LGPL license and notice remain packaged.

Eclipse JDT annotations in the resolved graph are EPL-2.0 and the full EPL-2.0 text is packaged. Their upstream source locator is recorded in the resolved-runtime inventory.

## Provider and tool marks

Provider/tool marks are used only for identification; trademark rights remain with their owners. Pinned models.dev/LobeHub source assets retain commit/path/hash provenance. The vLLM media-kit mark is intentionally not redistributed because its media-kit currently lacks an explicit logo license; vLLM uses Droide's neutral generic provider glyph instead.

See `legal/BRAND_ASSET_TERMS.md` and `legal/THIRD_PARTY_NOTICES.md`.

## Supply-chain controls

The source pins the Gradle distribution checksum, immutable GitHub Action SHAs, dependency locks, verification metadata and vendored artifact hashes. `tools/verify_dependency_policy.py`, `tools/verify_resolved_runtime_licenses.py`, `tools/verify_runtime_licenses.py`, `tools/verify_third_party_integrity.py` and `tools/verify_termux_provenance.py` are mandatory release gates.

A dependency, lockfile, bundled runtime or pinned-logo provenance change must fail until its license/provenance inventory is intentionally updated.

## Non-license release blockers

Third-party licensing is not the remaining release blocker. Public APK/AAB publication still requires the normal technical/product release checks documented by the project, including strict build/test/lint, signing, privacy/terms/store declarations, physical-device certification and the independent SPAKE2 interoperability/cryptographic review.
