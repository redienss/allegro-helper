#!/usr/bin/env bash
# Build Allegro Helper. A thin wrapper over Gradle, kept so the documented
# `./build.sh` still works; Gradle is the real build (see build.gradle).
#
# Produces build/libs/allegro-helper.jar. Arguments are passed through to
# Gradle, e.g. `./build.sh --offline`.
#
# The app itself still has zero *runtime* dependencies - Gradle's only declared
# dependency is JUnit, and it is test-scope.
set -euo pipefail

cd "$(dirname "$0")"

# `build` runs the test suite too; that is the point of having one.
exec ./gradlew build --console=plain "$@"
