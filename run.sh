#!/usr/bin/env bash
# Launch Allegro Helper. Builds first if needed. Any arguments are passed
# through (e.g. a base directory, or: --cli <import|match|retouch|describe|all>).
#
# Deliberately execs java directly rather than `gradlew run`: it keeps startup
# instant once built (no Gradle daemon in the launch path) and passes arguments
# through verbatim, which `--args="..."` does not do for paths with spaces.
set -euo pipefail

cd "$(dirname "$0")"

JAVA="${JAVA:-$(command -v java || true)}"
if [[ -z "$JAVA" && -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"; fi
if [[ -z "$JAVA" ]]; then
  echo "error: could not find 'java' on PATH or in JAVA_HOME." >&2
  exit 1
fi

JAR_PATH="build/libs/allegro-helper.jar"

# Skip the tests on this path: launching the app should not wait on them.
if [[ ! -f "$JAR_PATH" ]]; then
  ./gradlew jar --console=plain
fi

# Allow the app to set its X11 WM_CLASS (so a desktop launcher's StartupWMClass
# matches the running window). Harmless on non-X11 platforms.
ADD_OPENS="--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"

exec "$JAVA" $ADD_OPENS -jar "$JAR_PATH" "$@"
