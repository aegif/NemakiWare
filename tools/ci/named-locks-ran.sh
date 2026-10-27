#!/usr/bin/env bash
# Every named lock RAN: a surefire report exists for it, it counted at least one test, and it skipped none
# (R181).
#
# Usage: named-locks-ran.sh <surefire-reports-dir> <LockClassSimpleName>...
#
# The unit job's guard used to ask, one at a time, HOW a lock could have been silenced — the -Dtest list, the
# pom's <excludes> and <excludedGroups> as strings, the class file's existence, a class-level @Disabled — and
# every new way past it was a new line to add: an @Tag("atlas-integration") on the lock, a word added to the
# surefire.excludedGroups property, a -Dsurefire.excludedGroups= on the mvn line, an <excludesFile>, a
# @Disabled with a comment after it. All of them passed every check with the lock not running (2026-10-16,
# both reviewers of batch 18's fifth round).
#
# This asks the one question all of those answer the same way: what did surefire RUN. A lock that did not run
# has no report, or a report with no tests, or a report whose tests were skipped.
#
# Reports are TEST-<fully.qualified.Name>.xml, and a nested class reports as TEST-<...Name>$<Nested>.xml; both
# count toward the lock, and nothing else does — a report for FooTestExtra is not one for FooTest.
set -u

if [ "$#" -lt 2 ]; then
  echo "::error::usage: $0 <surefire-reports-dir> <LockClassSimpleName>..." >&2
  exit 2
fi
REPORTS="$1"
shift
if [ ! -d "$REPORTS" ]; then
  echo "::error::no surefire reports at $REPORTS, so no named lock can be shown to have run"
  exit 1
fi

status=0
for lock in "$@"; do
  shopt -s nullglob
  files=("$REPORTS"/TEST-*."$lock".xml "$REPORTS"/TEST-*."$lock"\$*.xml)
  shopt -u nullglob
  if [ "${#files[@]}" -eq 0 ]; then
    echo "::error::$lock has no surefire report: it did not run (excluded by a tag, a pattern or -Dtest, or renamed)"
    status=1
    continue
  fi
  tests=0
  skipped=0
  readable=1
  for f in "${files[@]}"; do
    head=$(grep -m1 -oE '<testsuite [^>]*>' "$f" || true)
    t=$(printf '%s' "$head" | grep -oE ' tests="[0-9]+"' | grep -oE '[0-9]+' || true)
    s=$(printf '%s' "$head" | grep -oE ' skipped="[0-9]+"' | grep -oE '[0-9]+' || true)
    if [ -z "$t" ] || [ -z "$s" ]; then
      echo "::error::$lock: $f does not state how many tests ran and were skipped, so it cannot show the lock ran"
      readable=0
      break
    fi
    tests=$((tests + t))
    skipped=$((skipped + s))
  done
  if [ "$readable" -eq 0 ]; then
    status=1
  elif [ "$tests" -le 0 ]; then
    echo "::error::$lock ran no tests (${#files[@]} report(s), 0 tests)"
    status=1
  elif [ "$skipped" -ne 0 ]; then
    echo "::error::$lock skipped $skipped of its $tests tests: a skipped lock asserts nothing"
    status=1
  else
    echo "$lock ran: $tests tests, none skipped"
  fi
done
exit "$status"
