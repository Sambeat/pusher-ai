#!/usr/bin/env bash
# Pusher-AI build & test. Requires JDK 21+.
# Uses java/javac from PATH, or auto-detects a JDK (macOS /usr/libexec/java_home,
# Homebrew, sdkman, /usr/lib/jvm) and sets JAVA_HOME if none is on PATH.
#
#   ./build.sh test    download deps (if missing), compile src/ -> out/,
#                      run every test class, print the JUnit summary.
#
# Exits non-zero on a missing toolchain, a compile error, or a test failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

LIB="lib"
OUT="out"

# Pinned test dependencies (exact versions and URLs — do not drift).
JUNIT_VERSION="4.13.2"
HAMCREST_VERSION="1.3"
JUNIT_JAR="$LIB/junit-$JUNIT_VERSION.jar"
HAMCREST_JAR="$LIB/hamcrest-core-$HAMCREST_VERSION.jar"
JUNIT_URL="https://repo1.maven.org/maven2/junit/junit/$JUNIT_VERSION/junit-$JUNIT_VERSION.jar"
HAMCREST_URL="https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/$HAMCREST_VERSION/hamcrest-core-$HAMCREST_VERSION.jar"

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
  if [[ ! -f "$JUNIT_JAR" ]]; then
    echo "Downloading $JUNIT_JAR ..."
    curl -fsSL -o "$JUNIT_JAR" "$JUNIT_URL"
  fi
  if [[ ! -f "$HAMCREST_JAR" ]]; then
    echo "Downloading $HAMCREST_JAR ..."
    curl -fsSL -o "$HAMCREST_JAR" "$HAMCREST_URL"
  fi
}

compile() {
  rm -rf "$OUT"
  mkdir -p "$OUT"
  local sources
  sources="$(find src -name '*.java')"
  echo "Compiling $(printf '%s\n' "$sources" | wc -l | tr -d ' ') source files -> $OUT/ ..."
  # shellcheck disable=SC2086
  javac --release 21 -Xlint:all -cp "$JUNIT_JAR:$HAMCREST_JAR" -d "$OUT" $sources
}

run_tests() {
  # Discover test classes: compiled *.class under out/tests whose simple name
  # ends in "Test". Excludes inner classes (name contains '$') and any
  # non-test helper (e.g. a future MockPusherServer).
  local class_files
  class_files="$(find "$OUT/tests" -name '*.class' ! -name '*$*' 2>/dev/null || true)"
  if [[ -z "$class_files" ]]; then
    echo "ERROR: no compiled test classes found under $OUT/tests." >&2
    return 1
  fi

  local fqcn="" f rel base
  while IFS= read -r f; do
    rel="${f#"$OUT"/}"      # tests/GameBoardTest.class
    rel="${rel%.class}"      # tests/GameBoardTest
    rel="${rel//\//.}"       # tests.GameBoardTest
    base="${rel##*.}"
    if [[ "$base" == *Test ]]; then
      fqcn="$fqcn $rel"
    fi
  done <<< "$class_files"
  fqcn="$(printf '%s' "$fqcn" | xargs)"  # trim surrounding whitespace
  if [[ -z "$fqcn" ]]; then
    echo "ERROR: no test classes (name ending in 'Test') found under $OUT/tests." >&2
    return 1
  fi

  echo "Running test classes: $fqcn"
  # Word-splitting on $fqcn is intentional: fully-qualified class names have no spaces.
  # shellcheck disable=SC2086
  java -cp "$OUT:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore $fqcn
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
