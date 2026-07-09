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

# Allow the app to set its X11 WM_CLASS (so a desktop launcher's StartupWMClass
# matches the running window). Harmless on non-X11 platforms.
ADD_OPENS="--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"

if [[ -f "$JAR_PATH" ]]; then
  exec "$JAVA" $ADD_OPENS -jar "$JAR_PATH" "$@"
else
  exec "$JAVA" $ADD_OPENS -cp "$CLASSES_DIR" com.allegrohelper.App "$@"
fi
