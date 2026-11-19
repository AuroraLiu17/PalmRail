#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${SINGLE_RAIL_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$JAVA_HOME" ]]; then
  for jdk in "$HOME"/.pico/primer-cli/jdk/jdk-21*/Contents/Home; do
    if [[ -x "$jdk/bin/java" ]]; then export JAVA_HOME="$jdk"; break; fi
  done
fi
export GRADLE_USER_HOME="${SINGLE_RAIL_GRADLE_HOME:-${TMPDIR:-/tmp}/palmrail-single-rail-gradle}"
exec bash "$root/gradlew" -p "$root" --no-daemon "$@"
