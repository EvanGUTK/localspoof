#!/usr/bin/env bash
set -euo pipefail
localspoof_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$localspoof_root/android"
if ! command -v java >/dev/null; then
  printf '%s\n' 'Install Java 17 and set JAVA_HOME before building.' >&2
  exit 1
fi
if [ ! -f gradlew ]; then
  if ! command -v gradle >/dev/null; then
    printf '%s\n' 'Install Gradle 8.13 to generate the official wrapper.' >&2
    exit 1
  fi
  gradle wrapper --gradle-version 8.13 --distribution-type bin
fi
bash ./gradlew :session-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
