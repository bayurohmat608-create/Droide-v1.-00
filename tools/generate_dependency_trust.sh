#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -x ./gradlew ]]; then
  echo "ERROR: ./gradlew is not executable" >&2
  exit 1
fi

./gradlew --write-locks --write-verification-metadata sha256 resolveDependencyInputs
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest :app:lintDebug
python3 tools/verify_dependency_trust.py
./gradlew --dependency-verification strict resolveDependencyInputs :app:testDebugUnitTest :app:lintDebug

echo "Dependency checksums and lock state generated without APK/AAB packaging. Review their origin before release."
