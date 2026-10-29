#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

OUT_MAIN="$ROOT_DIR/out/main"
OUT_TEST="$ROOT_DIR/out/test"

compile_main() {
  mkdir -p "$OUT_MAIN"
  javac --release 17 -encoding UTF-8 -sourcepath src/main/java -d "$OUT_MAIN" src/main/java/com/solari/similarity/Main.java
  if [ -d "src/main/resources" ]; then
    cp -R src/main/resources/* "$OUT_MAIN"/
  fi
}

compile_test() {
  compile_main
  mkdir -p "$OUT_TEST"
  javac --release 17 -encoding UTF-8 -cp "$OUT_MAIN" -sourcepath src/test/java -d "$OUT_TEST" src/test/java/com/solari/similarity/SimilarityAssistantTest.java
}

MODE="${1:-cli}"

case "$MODE" in
  test)
    compile_test
    shift
    exec java -cp "$OUT_MAIN:$OUT_TEST" com.solari.similarity.SimilarityAssistantTest "$@"
    ;;
  server)
    compile_main
    shift
    exec java -cp "$OUT_MAIN" com.solari.similarity.Main --serve "$@"
    ;;
  cli)
    compile_main
    shift
    exec java -cp "$OUT_MAIN" com.solari.similarity.Main "$@"
    ;;
  *)
    compile_main
    exec java -cp "$OUT_MAIN" com.solari.similarity.Main "$@"
    ;;
esac
