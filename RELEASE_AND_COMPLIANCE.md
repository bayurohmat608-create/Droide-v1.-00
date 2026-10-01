# Droide release and compliance

Droide v1.00 is maintained by **BayStudio** with engineering by **BayZov**. Droide-owned source code and original project material use the MIT License; third-party components retain their respective licenses.

## Public source release

The public source tree is license-ready for publication when `tools/verify_release_source.sh` passes. Internal process artifacts, build caches and development-only notes are excluded. Functional source, build configuration, third-party provenance, corresponding-source material and legal notices remain preserved.

## Third-party license closure

The release source uses three complementary inventories:

1. `app/src/main/assets/legal/open_source_licenses.json` for direct dependencies and the in-app legal browser.
2. `app/src/main/assets/legal/resolved_runtime_licenses.json` for every locked Gradle `releaseRuntimeClasspath` module.
3. `app/src/main/assets/legal/runtime_legal_inventory.json` for bundled native/rootfs/QEMU runtime bytes and package-level source identities.

The current Gradle runtime closure contains 134 mapped modules. The bundled runtime inventory contains 16 Alpine minirootfs packages, 52 Alpine QEMU packages and 91 Ubuntu Base packages. Unknown or stale entries fail release verification.

Copyleft source/notice obligations are preserved through bundled matching source where practical plus `app/src/main/assets/legal/COPYLEFT_SOURCE_OFFER.md`. Sora remains LGPL-2.1-or-later; PRoot/QEMU and aggregate package licenses remain their upstream licenses. The MIT license applies only to Droide-owned code/material.

## Brand assets

Provider/tool marks are nominative identification only and do not imply affiliation. models.dev/LobeHub logo sources are pinned with provenance. Marks without a sufficiently clear redistribution basis are not bundled; specifically, vLLM uses a neutral Droide glyph rather than the unlicensed media-kit logo.

## Binary release boundary

License closure is no longer the blocking item for the represented source/runtime tree. Public APK/AAB distribution remains fail-closed until strict compilation/tests/lint, signing, privacy/terms/store declarations, physical-device certification and the independent SPAKE2 interoperability/cryptographic review are complete.

The embedded compliance copy is `app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md`.
