#!/usr/bin/env bash
# Launch Allegro Helper. Builds first if needed. Any arguments are passed
# through (e.g. a base directory, or: --cli <import|match|retouch|describe|all>).
set -euo pipefail

cd "$(dirname "$0")"

JAVA="${JAVA:-$(command -v java || true)}"
if [[ -z "$JAVA" && -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"; fi
if [[ -z "$JAVA" ]]; then
  echo "error: could not find 'java' on PATH or in JAVA_HOME." >&2
  exit 1
fi

JAR_PATH="build/allegro-helper.jar"
CLASSES_DIR="build/classes"

if [[ ! -f "$JAR_PATH" && ! -d "$CLASSES_DIR" ]]; then
  ./build.sh
fi

if [[ -f "$JAR_PATH" ]]; then
  exec "$JAVA" -jar "$JAR_PATH" "$@"
else
  exec "$JAVA" -cp "$CLASSES_DIR" com.allegrohelper.App "$@"
fi
