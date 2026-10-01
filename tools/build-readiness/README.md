# Build readiness

Droide separates source readiness from machine readiness.

- `python3 tools/build-readiness/prepare_build.py source` validates source prerequisites.
- `python3 tools/build-readiness/prepare_build.py environment --android-sdk-root <path>` validates the local Android build environment.

The checks are intentionally project-specific and do not constrain user projects opened inside Droide.

Dependency trust is prepared before APK packaging. `bash tools/generate_dependency_trust.sh`
resolves external dependency inputs and writes the real lock state and SHA-256 metadata.
It also compiles/tests/lints to capture inputs resolved inside task actions, then repeats
resolution, tests and lint with strict verification. It does not run assemble, bundle or signing tasks.
The source gate checks that every locked module is represented in the checksum metadata.

Gradle-generated checksums record the files fetched during bootstrap; they do not establish
publisher identity by themselves. Review checksum origins before release. Compiler/lint
inputs are included through the corresponding task actions and the final strict run.
After a reviewed dependency update, generate metadata in a trusted environment and review
the diff. Do not switch verification off to accommodate an unexpected checksum change.
