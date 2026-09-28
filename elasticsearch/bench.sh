#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"

SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

JOPTS=(--add-modules jdk.incubator.vector -encoding UTF-8 -nowarn -Xlint:none -XDsuppressNotes -Xmaxerrs 1000)

mkdir -p target/bench

find src/main/java -name '*.java' > target/bench-main.txt
javac "${JOPTS[@]}" -d target/bench @target/bench-main.txt 2>&1 | grep -v 'incubating module'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "BENCH MAIN COMPILATION FAILED"; exit 1; fi

java --add-modules jdk.incubator.vector -cp target/bench com.naqqa.elasticsearch.bench.BenchmarkRunner "$@"
