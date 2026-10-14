#!/usr/bin/env bash
#
# Says which lines have a Camunda client nobody here has read yet. It prints one row per such
# line, "<line> <client read> <client the adapter pins now>", and nothing when every line is
# read.
#
# The client read is the entry of the line in camunda8-client-read.properties. The client the
# adapter pins is the 'camunda8.version.line-<line>' property in the POM of
# camunda-community-hub/vanillabp-camunda8-adapter. That POM is read as text: Maven would stop
# at the snapshot BOM it imports from GitHub Packages long before it reached a property, and a
# property is all this needs.
#
# Only the lines in the record count. A line the adapter carries and this repository does not
# build yet is none of our business. A line in the record which the adapter has dropped is an
# error, because the record then names a line nobody can raise any more: take the line out of
# the record when it leaves this repository.
#
# Usage:  bin/client-lines-to-read.sh <camunda8-client-read.properties> <adapter pom.xml>

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $(basename "$0") <camunda8-client-read.properties> <adapter pom.xml>" >&2
  exit 2
fi

record="$1"
adapter_pom="$2"

pins="$(sed -n 's|.*<camunda8\.version\.line-\([^>]*\)>\([^<]*\)</.*|\1 \2|p' "$adapter_pom")"

# Comments and blank lines are skipped, and so is anything that is not 'line-<id>=<version>'.
sed -n 's|^[[:space:]]*line-\([^=[:space:]]*\)[[:space:]]*=[[:space:]]*\([^[:space:]]*\).*|\1 \2|p' \
  "$record" \
  | while read -r line read_version; do
      pinned="$(awk -v l="$line" '$1 == l { print $2 }' <<< "$pins")"
      if [ -z "$pinned" ]; then
        echo "The adapter POM has no 'camunda8.version.line-${line}', but ${record} still" >&2
        echo "names line ${line}. Take the line out of the record if it left this repository." >&2
        exit 2
      fi
      if [ "$pinned" != "$read_version" ]; then
        echo "${line} ${read_version} ${pinned}"
      fi
    done
