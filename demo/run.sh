#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="${1:-method-rename-pay-local}"
out="${2:-$root/target/demo}"

native() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

cd "$root"
mvn -q install -Dmaven.test.skip=true -Dspotless.check.skip=true -Djacoco.skip=true -pl rook-eval -am
mvn -q -pl rook-eval dependency:build-classpath -Dmdep.outputFile="$(native "$root/target/demo-classpath.txt")"

sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
classpath="$(native "$root/rook-eval/target/classes")$sep$(cat "$root/target/demo-classpath.txt")"

rm -rf "$out"
java -cp "$classpath" dev.rook.eval.EvalCli run "$(native "$root/eval/fixtures")" "$(native "$out")" "$fixture"

echo
echo "before and after for $fixture"
diff -ru "$root/eval/fixtures/$fixture/repo/src/main" "$root/eval/fixtures/$fixture/gold/src/main" || true
