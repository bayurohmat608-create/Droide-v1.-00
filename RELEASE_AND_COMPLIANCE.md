# Droide release and compliance

Droide v1.00 is maintained by **BayStudio** with engineering by **BayZov**.

## Public source tree

The release tree excludes internal checkpoints, recovery archives, temporary patch scripts, generated outputs and development-only handoff material. Functional source, build configuration, runtime registries, legal assets and third-party provenance are preserved.

## Third-party licensing

The bundled third-party tree now has deterministic compliance gates:

- 23/23 directly declared application runtime dependencies must map exactly once to `open_source_licenses.json`.
- Native/runtime binaries are hash-bound in `runtime_legal_inventory.json`.
- The Alpine minirootfs inventory contains 16 exact packages.
- The QEMU offline inventory contains 52 exact Alpine packages.
- The Ubuntu Base inventory contains 91 exact installed packages mapped to source package/version locators.
- Matching PRoot/OpenMinis, talloc and QEMU source archives are preserved in `third_party/native-engines/`.
- A packaged written corresponding-source offer covers distributed copyleft components whose source is not already included in the same release.

Run `./tools/verify_release_source.sh` to verify the static release closure.

## Public binary release boundary

A public APK/AAB remains fail-closed until the trusted online release build resolves and reviews the complete Gradle transitive graph, writes dependency locks and `gradle/verification-metadata.xml`, and reruns the release gates. Run `tools/generate_dependency_trust.sh` in that environment.

This tree also does not choose a license for Droide-owned application source. BayStudio must add the intended project license before describing the public repository as open source.

The embedded application compliance copy is `app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md`.
