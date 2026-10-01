# Droide third-party notices

Droide v1.00 includes software and assets owned by their respective upstream authors. This notice preserves attribution and points to the exact packaged license/source records; it does not replace upstream license terms or grant trademark rights.

## Application/runtime libraries

The authoritative machine-readable catalog is `legal/open_source_licenses.json`. Every directly declared app runtime dependency is required to map exactly once to that catalog by `tools/verify_dependency_policy.py`.

Reviewed components include AndroidX/Compose, Kotlinx, OkHttp/Okio, Kadb compatibility artifacts, HiddenApiBypass, Bouncy Castle, Eclipse JGit, jsoup, Coil, Sora Editor, Termux terminal modules and XZ for Java. Their exact versions, SPDX identifiers, license-text assets and source/provenance references are recorded in the catalog.

Termux `terminal-emulator` and `terminal-view` 0.118.0 use the upstream Apache-2.0 Terminal Emulator exception. Droide preserves the reviewed upstream statement and verifies the pinned Java source, AAR classes, AAR hashes and native ABI hashes offline. Sora Editor 0.24.6 is LGPL-2.1-or-later. XZ for Java 1.12 is 0BSD.

## Native Linux and virtual-machine runtime

Droide bundles PRoot/OpenMinis, talloc, Linux rootfs archives and an Alpine QEMU package set. The exact binary/runtime closure is `legal/runtime_legal_inventory.json` and is generated from the shipped bytes.

- PRoot/OpenMinis runtime and loaders: GPL v2 scope as preserved by the bundled source/COPYING; matching source is under `third_party/native-engines/proot/`.
- talloc 2.4.2: LGPL-3.0-or-later; matching source is bundled beside the PRoot source.
- QEMU 11.0.3: GPL v2; matching QEMU source is under `third_party/native-engines/qemu/`.
- Alpine minirootfs: 16 exact installed packages. Their package-declared license expressions and aports source commits are recorded individually.
- Alpine QEMU offline pack: 52 exact APK packages. Their SHA-256 values, declared licenses, repositories and source-recipe commits are recorded individually.
- Ubuntu Base 24.04.5 arm64: 91 installed binary packages mapped to their exact Ubuntu source package/version pages. Ubuntu and Canonical trademarks remain subject to Canonical's separate trademark/IP policy.

The aggregate archives do not relicense their component packages. See `legal/licenses/Runtime-Aggregates-NOTICE.md` and `legal/COPYLEFT_SOURCE_OFFER.md`.

## Corresponding source offer

BayStudio offers the complete corresponding source for copyleft binaries it distributes with Droide. PRoot/OpenMinis, talloc and QEMU source archives are already included in the public source tree. For aggregate Alpine/Ubuntu components not already present as source archives, the runtime inventory records exact source locators and BayStudio's written source offer remains valid for at least three years after the last distribution of the relevant binary version. Requests may be filed through the public Droide repository issue tracker using the title `Corresponding Source Request`.

## Provider/model and development-tool identity marks

Droide uses compact local marks only to identify the corresponding services, projects or tools. Copyright-license attribution includes LobeHub lobe-icons (MIT), Simple Icons (CC0-1.0), Font Awesome Free brand icons (CC BY 4.0), OpenCode project assets (MIT), Ollama project assets (MIT) and Codex project assets under the applicable project license. Other bundled provider/tool marks remain subject to their respective trademark and brand-use rules. Inclusion does not imply sponsorship, endorsement or affiliation.

Full brand-asset terms remain available from **Settings → About & Legal → Brand Asset Terms**. Full open-source license texts remain available from **Settings → About & Legal → Open Source Licenses**.

## Integrity and release checks

`third_party/ARTIFACTS_SHA256.txt` hash-binds every file under `third_party/`. `tools/verify_third_party_integrity.py`, `tools/verify_termux_provenance.py`, `tools/verify_dependency_policy.py` and `tools/verify_runtime_licenses.py` are mandatory release-source gates. The resolved Gradle transitive graph must additionally be locked and verified by the trusted online release build before public APK/AAB publication.
