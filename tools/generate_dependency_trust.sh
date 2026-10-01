#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -x ./gradlew ]]; then
  echo "ERROR: ./gradlew is not executable" >&2
  exit 1
fi

./gradlew --write-locks :app:dependencies


./gradlew --write-verification-metadata sha256 \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleRelease \
  :app:bundleRelease

echo "Dependency trust metadata generated. Review lock state and gradle/verification-metadata.xml before release."
