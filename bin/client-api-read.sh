#!/usr/bin/env bash
#
# Writes what client-api-changes.yaml puts into its pull request: the record raised to the
# clients the adapter pins now, and a body which says what those clients added and where this
# repository uses the classes concerned.
#
# Camunda adds enum literals and abstract interface methods inside a line and does not count
# either as breaking. The client here comes from the Camunda 8 adapter (decision 32), so such a
# client arrives without a commit in this repository. This script is where the question "does
# our code need a change?" gets asked anyway. Decision 33 says why.
#
# What a client added is read by bin/client-api-changes.sh of the adapter repository. It is
# called and not copied, so the adapter and this repository cannot disagree about it.
#
# Usage:  bin/client-api-read.sh <record> <adapter pom.xml> <client-api-changes.sh> <out-dir>
#
# It writes <out-dir>/record.properties and <out-dir>/body.md, and prints 'lines=<count>' and
# 'found=yes|no' on stdout. With 'lines=0' every line is read and nothing else is written. It
# needs what client-api-changes.sh needs: Maven Central, Maven, a JDK and unzip.

set -euo pipefail

if [ $# -ne 4 ]; then
  echo "Usage: $(basename "$0") <record> <adapter pom.xml> <client-api-changes.sh> <out-dir>" >&2
  exit 2
fi

record="$1"
adapter_pom="$2"
api_changes="$3"
out="$4"
here="$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)"

mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$here/bin/client-lines-to-read.sh" "$record" "$adapter_pom" > "$work/lines"
count="$(wc -l < "$work/lines" | tr -d ' ')"
echo "lines=${count}"
if [ "$count" -eq 0 ]; then
  echo "found=no"
  exit 0
fi

# The new record is the old one with the versions replaced, so its comment stays as it is.
cp "$record" "$out/record.properties"
found=no
: > "$work/reports"
while read -r line old_version new_version; do
  echo "== line ${line}: ${old_version} -> ${new_version}" >&2
  sed -i "s|^\([[:space:]]*line-${line//./\\.}[[:space:]]*=[[:space:]]*\).*|\1${new_version}|" \
    "$out/record.properties"
  status=0
  "$api_changes" io.camunda:camunda-client-java "$old_version" "$new_version" \
    >> "$work/reports" || status=$?
  # 0 is nothing found and 1 is something found. Anything else is the script failing, and a
  # failed read must not raise the record.
  [ $status -le 1 ] || exit $status
  [ $status -eq 0 ] || found=yes
  echo >> "$work/reports"
done < "$work/lines"
echo "found=${found}"

# The classes are taken from the code blocks of the report: 'Class.LITERAL' for a literal and
# 'Class: method' for a method. A nested class is imported by its outer class, so the part
# after '$' goes.
awk '/^```/ { inside = !inside; next } inside { print }' "$work/reports" \
  | sed -e 's|: .*||' -e 't' -e 's|\.[^.]*$||' \
  | sed 's|\$.*||' \
  | sort -u > "$work/classes"

# Where this repository uses one of them: a file which names the class, or which imports its
# whole package. Generated sources are not ours, so target/ stays out.
: > "$work/uses"
while read -r class; do
  [ -n "$class" ] || continue
  package="${class%.*}"
  { grep -rlF --include='*.java' --exclude-dir=target "$class" "$here" || true
    grep -rlF --include='*.java' --exclude-dir=target "import ${package}.*;" "$here" || true
  } | sort -u | while read -r file; do
    source_package="$(sed -n 's|^package \([^;]*\);.*|\1|p' "$file" | head -n 1)"
    echo "${source_package} ${class##*.}"
  done >> "$work/uses"
done < "$work/classes"

{
  echo "<!-- client-api-read -->"
  if [ "$found" = "yes" ]; then
    echo "**Camunda added enum literals or interface methods in a client this repository has not"
    echo "read yet. Check the code of this repository against them before you merge this.**"
    echo
    if [ -s "$work/uses" ]; then
      echo "These packages of this repository use a class named below:"
      echo
      sort -u "$work/uses" | awk '
        $1 != package { if (package != "") print "- `" package "`: " names; package = $1; names = "" }
        { names = names (names == "" ? "" : ", ") "`" $2 "`" }
        END { if (package != "") print "- `" package "`: " names }'
    else
      echo "No source file here names a class from the list below. Check anyway that nothing"
      echo "reaches one of them through a type of the adapter."
    fi
  else
    echo "**Nothing to check.** The clients below added no enum literal and no abstract interface"
    echo "method. Merge this to record that they were read."
  fi
  echo
  echo "| Line | Read | Adapter pins now |"
  echo "|------|------|------------------|"
  while read -r line old_version new_version; do
    echo "| ${line} | ${old_version} | ${new_version} |"
  done < "$work/lines"
  echo
  echo "The builds here already use the new clients: they come from the Camunda 8 adapter, see"
  echo "decision 32. This pull request only raises \`camunda8-client-read.properties\`. Merging"
  echo "it says somebody read the report below with this repository's code in mind, so it never"
  echo "merges itself. Decision 33 says why."
  echo
  cat "$work/reports"
} > "$out/body.md"
