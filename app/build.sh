#!/usr/bin/env bash
# Build Allegro Helper using only the JDK (no Maven/Gradle, no external
# dependencies). Produces build/classes and, if the `jar` tool is available, a
# runnable build/allegro-helper.jar.
#
# Compiler/runtime resolution order:
#   $JAVAC / $JAVA env vars  ->  PATH  ->  $JAVA_HOME/bin  ->  common JVM dirs.
set -euo pipefail

cd "$(dirname "$0")"

find_tool() {
  # $1 = tool name (javac/jar), $2 = env override value
  local name="$1" override="${2:-}"
  if [[ -n "$override" && -x "$override" ]]; then echo "$override"; return 0; fi
  if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return 0; fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$name" ]]; then echo "$JAVA_HOME/bin/$name"; return 0; fi
  local c
  for c in /usr/lib/jvm/*/bin/"$name" /opt/*/bin/"$name" /snap/*/*/jbr/bin/"$name"; do
    [[ -x "$c" ]] && { echo "$c"; return 0; }
  done
  return 1
}

JAVAC="$(find_tool javac "${JAVAC:-}")" || {
  echo "error: could not find 'javac'. Install a JDK or set JAVAC=/path/to/javac." >&2
  exit 1
}
echo "Using javac: $JAVAC ($("$JAVAC" -version 2>&1))"

SRC_DIR="src/main/java"
BUILD_DIR="build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_PATH="$BUILD_DIR/allegro-helper.jar"
MAIN_CLASS="com.allegrohelper.App"

rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR"

echo "Compiling sources…"
find "$SRC_DIR" -name '*.java' > "$BUILD_DIR/sources.txt"
"$JAVAC" -encoding UTF-8 -d "$CLASSES_DIR" @"$BUILD_DIR/sources.txt"

if JAR="$(find_tool jar "${JAR:-}")"; then
  echo "Packaging jar with: $JAR"
  "$JAR" --create --file "$JAR_PATH" --main-class "$MAIN_CLASS" -C "$CLASSES_DIR" .
  echo "Built $JAR_PATH"
else
  echo "note: 'jar' tool not found; skipping jar. The app still runs from build/classes."
fi

echo "Run the desktop app with:  ./run.sh"
echo "Or headless:               ./run.sh --cli all"
