#!/usr/bin/env bash
# Pusher-AI build & test. Requires JDK 21+.
# Uses java/javac from PATH, or auto-detects a JDK (macOS /usr/libexec/java_home,
# Homebrew, sdkman, /usr/lib/jvm) and sets JAVA_HOME if none is on PATH.
#
#   ./build.sh test    download deps (if missing), compile src/ -> out/,
#                      run every test class (JUnit 5), print the summary.
#
# Exits non-zero on a missing toolchain, a compile error, or a test failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

LIB="lib"
OUT="out"

# Pinned test dependency (exact version and URL — do not drift).
# The JUnit Platform console launcher (standalone edition) bundles the JUnit 5
# (Jupiter) API + engine + assertions in one jar; it replaces the old
# junit-4.13.2 + hamcrest-core-1.3 pair (nothing imports Hamcrest).
JUNIT_PLATFORM_VERSION="1.14.4"    # = JUnit Jupiter 5.14.4
JUPITER_JAR="$LIB/junit-platform-console-standalone-$JUNIT_PLATFORM_VERSION.jar"
JUPITER_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_PLATFORM_VERSION/junit-platform-console-standalone-$JUNIT_PLATFORM_VERSION.jar"

usage() {
  echo "Usage: ./build.sh [test]" >&2
  echo "  test   download deps, compile src/ -> out/, run all tests (default)" >&2
}

resolve_jdk() {
  # A working JDK already on PATH? Done.
  if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    return 0
  fi

  # Otherwise probe common install locations for a usable JDK.
  local c candidates=""
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidates="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
  candidates="$candidates /opt/homebrew/opt/openjdk /usr/local/opt/openjdk $HOME/.sdkman/candidates/java/current"
  for c in /usr/lib/jvm/java-*; do
    [[ -d "$c" ]] && candidates="$candidates $c"
  done

  # Word-splitting on $candidates is intentional: the known JDK paths have no spaces.
  # shellcheck disable=SC2086
  for c in $candidates; do
    if [[ -x "$c/bin/java" ]] && "$c/bin/java" -version >/dev/null 2>&1; then
      export JAVA_HOME="$c"
      export PATH="$c/bin:$PATH"
      echo "Using JDK at: $c"
      return 0
    fi
  done

  echo "ERROR: JDK 21+ required. Install a JDK, or set JAVA_HOME / put java on PATH." >&2
  exit 1
}

download_deps() {
  mkdir -p "$LIB"
  if [[ ! -f "$JUPITER_JAR" ]]; then
    echo "Downloading $JUPITER_JAR ..."
    curl -fsSL -o "$JUPITER_JAR" "$JUPITER_URL"
  fi
}

compile() {
  rm -rf "$OUT"
  mkdir -p "$OUT"
  local sources
  sources="$(find src -name '*.java')"
  echo "Compiling $(printf '%s\n' "$sources" | wc -l | tr -d ' ') source files -> $OUT/ ..."
  # shellcheck disable=SC2086
  javac --release 21 -Xlint:all -cp "$JUPITER_JAR" -d "$OUT" $sources
}

run_tests() {
  if [[ ! -d "$OUT/tests" ]]; then
    echo "ERROR: no compiled test classes found under $OUT/tests." >&2
    return 1
  fi

  # JUnit 5 console launcher (standalone jar): the default class-name filter
  # (names starting with "Test" or ending in "Test"/"Tests") selects the test
  # classes under out/tests and skips helpers like MockPusherServer.
  # --fail-if-no-tests keeps the old "no test classes -> error" failure mode
  # (launcher exit status 2).
  echo "Running tests (JUnit 5 console launcher, package 'tests') ..."
  java -jar "$JUPITER_JAR" execute --class-path "$OUT" --select-package tests \
    --fail-if-no-tests --disable-ansi-colors
}

main() {
  local cmd="${1:-test}"
  case "$cmd" in
    test)
      resolve_jdk
      download_deps
      compile
      run_tests
      ;;
    *)
      usage
      exit 2
      ;;
  esac
}

main "$@"
