#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"

SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

MODE="${1:-all}"
FILTER="${2:-}"
JOPTS=(--add-modules jdk.incubator.vector -encoding UTF-8 -nowarn -Xlint:none -XDsuppressNotes -Xmaxerrs 1000)

rm -rf target/classes target/test-classes
mkdir -p target/classes target/test-classes

find src/main/java -name '*.java' > target/main-sources.txt
javac "${JOPTS[@]}" -d target/classes @target/main-sources.txt 2>&1 | grep -v 'incubating module'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "MAIN COMPILATION FAILED"; exit 1; fi

if [ "$MODE" = "compile" ]; then echo "MAIN COMPILATION OK"; exit 0; fi

find src/test/java -name '*.java' > target/test-sources.txt
javac "${JOPTS[@]}" -cp target/classes -d target/test-classes @target/test-sources.txt 2>&1 | grep -v 'incubating module'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "TEST COMPILATION FAILED"; exit 1; fi

java --add-modules jdk.incubator.vector -cp "target/classes${SEP}target/test-classes" \
  com.naqqa.elasticsearch.test.TestRunner target/test-classes "$FILTER" 2>&1 | grep -v 'incubating module'
exit "${PIPESTATUS[0]}"
