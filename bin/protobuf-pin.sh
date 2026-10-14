#!/usr/bin/env bash
#
# Is protobuf.version high enough for the Camunda client of a line? Protobuf refuses a runtime
# older than the code it generated, and the client brings generated code. The pin in pom.xml is
# raised by hand, while the client arrives through the Camunda 8 adapter and can move without a
# commit here. So the night reads what this build really resolves, and this script says whether
# the pin is still high enough. See decision 12 in the repository's DECISIONS.md.
#
# The input is the verbose dependency tree of protobuf for that line:
#
#   mvn -Pline-<line> -pl core dependency:tree -Dverbose \
#     -Dincludes=com.google.protobuf:protobuf-java -DoutputFile=target/protobuf-tree.txt
#
# '-Dverbose' is what makes Maven print 'version managed from 4.36.2' next to every path which
# asked for another version than the pin. Without it the tree names the pin alone, and the
# version the client asked for is lost. The highest version any path asked for is the one the
# pin has to reach: a runtime may be newer than the code it serves and never older.
#
# Prints what it compared. Exit code 1 and a message on stderr when the pin is too low, exit
# code 2 when the tree names no protobuf at all, which means the tree was not read right.
#
# Usage:  bin/protobuf-pin.sh <line> <tree-file>
#
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $(basename "$0") <line> <tree-file>" >&2
  exit 2
fi

line="$1"
tree="$2"

entries=$(grep -oE 'com\.google\.protobuf:protobuf-java:jar:[^:]+:[a-z]+[^)]*' "$tree" || true)
if [ -z "$entries" ]; then
  echo "The tree of line ${line} in ${tree} names no com.google.protobuf:protobuf-java, so" >&2
  echo "nothing can be said about protobuf.version. Was it written with" >&2
  echo "-Dincludes=com.google.protobuf:protobuf-java?" >&2
  exit 2
fi

# What this build runs: the version every entry carries, which is the pin.
resolved=$(printf '%s\n' "$entries" | head -1 \
  | sed -E 's/^com\.google\.protobuf:protobuf-java:jar:([^:]+):.*/\1/')

# What the dependencies asked for, the pin itself included, so a tree in which nothing was
# managed compares the pin with itself.
needed=$(
  {
    echo "$resolved"
    printf '%s\n' "$entries" | sed -nE 's/.*version managed from ([^;) ]+).*/\1/p'
  } | sort -V | tail -1
)

echo "Line ${line} runs protobuf-java ${resolved}, and its dependencies ask for ${needed} at most."
if [ "$needed" != "$resolved" ]; then
  echo "Line ${line} runs protobuf-java ${resolved}, which is protobuf.version in pom.xml, and" >&2
  echo "a dependency of that line asks for ${needed}. As a rule it is the Camunda client the" >&2
  echo "adapter of the line brings, generated against that protobuf. Protobuf refuses a runtime" >&2
  echo "older than its generated code, so the adapter would not boot. Raise protobuf.version in" >&2
  echo "pom.xml to ${needed}. To see who asks for it:" >&2
  echo "  mvn -Pline-${line} -pl core dependency:tree -Dverbose -Dincludes=com.google.protobuf" >&2
  exit 1
fi
