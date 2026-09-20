#!/usr/bin/env bash
#
# Says whether this branch changes what a release line IS. It prints "true" or "false"
# on stdout, and the reason on stderr. The pull-request checks read the answer and run
# the whole line matrix when it is "true".
#
# A pull request builds the current GA line alone, which is enough for a story: the other
# lines are proven by the nightly matrix. It is not enough for a property which decides
# what another line is. A build of line 8.9 never compiles the pin of line 8.8, so a
# change to it would be merged unbuilt and break in the night.
#
# The answer comes from the VALUES of the properties listed below, taken from the merge
# base and from the working tree and compared. A property, not a line of the diff: a
# comment which names a property is not a change, and a property which moves is found
# wherever in the file it stands. Reading the diff itself did both wrong, in both
# directions, until 2026-09-20.
#
# Usage:  bin/pin-change.sh <base-ref>
#
# Needs git, and a checkout deep enough to hold the merge base.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

# Every property whose value decides what a line is. Add one when a new property joins
# them, and a '*' in a name is any line.
#
#   camunda8.version.line-*          the Camunda client of a line, and with it the oldest
#                                    cluster that line accepts
#   camunda8-adapter.version.line-*  the build of the VanillaBP Camunda 8 adapter a line
#                                    sits on
#   protobuf.version                 one runtime for every line, raised together with the
#                                    newest client. Left behind it breaks the newest line
#                                    on its first deployment, and that line is the one no
#                                    pull request builds.
line_properties=(
    'camunda8\.version\.line-[^<>]+'
    'camunda8-adapter\.version\.line-[^<>]+'
    'protobuf\.version'
)

base_ref="${1:?usage: bin/pin-change.sh <base-ref>}"

values_of() {
    # reads a POM on stdin and prints one "name=value" per property of the list above
    local pattern
    pattern="$(IFS='|'; printf '%s' "${line_properties[*]}")"
    grep -oE "<(${pattern})>[^<]*</" \
        | sed -e 's|^<||' -e 's|>|=|' -e 's|</$||' \
        | sort
}

base_commit="$(git merge-base "${base_ref}" HEAD)"
before="$(git show "${base_commit}:pom.xml" | values_of || true)"
after="$(values_of < pom.xml || true)"

# A detector which finds nothing answers "false" for every pull request, and nothing says
# so. So it stops instead: either the properties were renamed and this list has to follow,
# or the script is reading the wrong file.
if [ -z "${after}" ]; then
    echo "None of the properties this script watches is in pom.xml." >&2
    echo "Were they renamed? Then this script's list has to follow them." >&2
    exit 1
fi

if [ "${before}" = "${after}" ]; then
    echo "No line property changed since ${base_ref}, so the nightly matrix keeps the other lines." >&2
    echo false
else
    echo "A line property changed since ${base_ref}, so every line is built:" >&2
    diff <(printf '%s\n' "${before}") <(printf '%s\n' "${after}") | grep -E '^[<>]' >&2 || true
    echo true
fi
