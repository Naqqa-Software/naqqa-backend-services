#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"

SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

JOPTS=(--add-modules jdk.incubator.vector -encoding UTF-8 -nowarn -Xlint:none -XDsuppressNotes -Xmaxerrs 1000)
HEAP="${AMZ_HEAP:--Xmx4g}"

mkdir -p target/amz

find src/main/java -name '*.java' > target/amz-main.txt
javac "${JOPTS[@]}" -d target/amz @target/amz-main.txt 2>&1 | grep -v 'incubating module'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "AMAZON MAIN COMPILATION FAILED"; exit 1; fi

java "$HEAP" --add-modules jdk.incubator.vector -cp target/amz com.naqqa.elasticsearch.bench.amazon.AmazonBenchmark "$@"
