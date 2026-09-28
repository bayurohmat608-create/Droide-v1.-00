# Build readiness

Droide separates source readiness from machine readiness.

- `python3 tools/build-readiness/prepare_build.py source` validates source prerequisites.
- `python3 tools/build-readiness/prepare_build.py environment --android-sdk-root <path>` validates the local Android build environment.

The checks are intentionally project-specific and do not constrain user projects opened inside Droide.
