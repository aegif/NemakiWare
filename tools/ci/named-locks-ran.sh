#!/usr/bin/env bash
# Every named lock RAN, whole: a surefire report exists for it, it counted at least one test, it skipped none,
# and every test method its source declares is among the tests the report names (R181).
#
# Usage: named-locks-ran.sh <surefire-reports-dir> <test-source-root> <fully.qualified.LockClass>...
#
# The unit job's guard used to ask, one at a time, HOW a lock could have been silenced — the -Dtest list, the
# pom's <excludes> and <excludedGroups> as strings, the class file's existence, a class-level @Disabled — and
# every new way past it was a new line to add: an @Tag("atlas-integration") on the lock, a word added to the
# surefire.excludedGroups property, a -Dsurefire.excludedGroups= on the mvn line, an <excludesFile>, a
# @Disabled with a comment after it. All of them passed every check with the lock not running (2026-10-16,
# both reviewers of batch 18's fifth round).
#
# This asks what surefire RAN. A lock that did not run has no report, or a report with no tests, or a report
# whose tests were skipped. Two shapes pass those three questions with the lock silenced, and are asked here too
# (closing batch 5 review, both reviewers):
#   - A report is chosen by the lock's FULLY QUALIFIED name: TEST-<pkg.Name>.xml and, for a nested class,
#     TEST-<pkg.Name>$<Nested>.xml. Chosen by the simple name, a class of the same name in another package stood
#     in for the lock while the lock itself did not run. A simple name is refused.
#   - JUnit's tag filter takes a tagged METHOD out at discovery, so the report counts fewer tests and none
#     skipped. Every method the lock's source declares with @Test, @ParameterizedTest, @RepeatedTest,
#     @TestFactory or @TestTemplate has to be among the report's test cases. (A test method inherited from a
#     superclass, or declared with an annotation of the project's own, is not seen here.)
set -u

if [ "$#" -lt 3 ]; then
  echo "::error::usage: $0 <surefire-reports-dir> <test-source-root> <fully.qualified.LockClass>..." >&2
  exit 2
fi
REPORTS="$1"
SOURCES="$2"
shift 2
if [ ! -d "$REPORTS" ]; then
  echo "::error::no surefire reports at $REPORTS, so no named lock can be shown to have run"
  exit 1
fi
if [ ! -d "$SOURCES" ]; then
  echo "::error::no test sources at $SOURCES, so what each named lock declares cannot be read"
  exit 1
fi

exec python3 - "$REPORTS" "$SOURCES" "$@" <<'PY'
import glob
import os
import re
import sys

reports, sources, locks = sys.argv[1], sys.argv[2], sys.argv[3:]

TEST_ANNOTATION = re.compile(r"@(?:org\.junit\.jupiter\.api\.|org\.junit\.jupiter\.params\.)?"
                             r"(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def without_comments_and_strings(text):
    """The source with comments, string literals, text blocks and char literals blanked out."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
        elif text[i] in "\"'":
            quote = text[i]
            j = i + 1
            while j < n and text[j] != quote:
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            out.append(quote * 2)
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def strip_annotations(text):
    """Drop annotations, with their (possibly nested) argument lists."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text[i] == "@":
            m = re.match(r"@[\w.]+\s*", text[i:])
            i += m.end() if m else 1
            if i < n and text[i] == "(":
                depth = 0
                while i < n:
                    if text[i] == "(":
                        depth += 1
                    elif text[i] == ")":
                        depth -= 1
                        if depth == 0:
                            i += 1
                            break
                    i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def declared_tests(path):
    code = without_comments_and_strings(open(path, encoding="utf-8").read())
    names = set()
    for m in TEST_ANNOTATION.finditer(code):
        # Annotations stripped BEFORE the head is cut at the first { or ; — an annotation's own arguments can hold a
        # brace (@ValueSource(strings = { ... })), and cutting there first lost the method that followed. The "@T"
        # stands for the test annotation itself, so arguments of its own (@RepeatedTest(5)) go with it.
        head = strip_annotations("@T" + code[m.end():m.end() + 4000])
        end = min([k for k in (head.find("{"), head.find(";")) if k >= 0] or [len(head)])
        name = re.search(r"([A-Za-z_$][\w$]*)\s*\(", head[:end])
        if name:
            names.add(name.group(1))
    return names


status = 0
for lock in locks:
    if "." not in lock:
        print("::error::%s is not a fully qualified class name: a report chosen by a simple name can be another "
              "package's class of that name" % lock)
        status = 1
        continue
    source = os.path.join(sources, *lock.split(".")) + ".java"
    if not os.path.isfile(source):
        print("::error::%s has no source at %s, so what it declares cannot be read" % (lock, source))
        status = 1
        continue
    files = sorted(set(glob.glob(os.path.join(glob.escape(reports), "TEST-" + glob.escape(lock) + ".xml"))
                       + glob.glob(os.path.join(glob.escape(reports), "TEST-" + glob.escape(lock) + "$*.xml"))))
    if not files:
        print("::error::%s has no surefire report: it did not run (excluded by a tag, a pattern or -Dtest, "
              "or renamed)" % lock)
        status = 1
        continue
    tests = skipped = 0
    ran = set()
    unreadable = None
    for f in files:
        text = open(f, encoding="utf-8", errors="replace").read()
        head = re.search(r"<testsuite [^>]*>", text)
        t = re.search(r' tests="(\d+)"', head.group(0)) if head else None
        s = re.search(r' skipped="(\d+)"', head.group(0)) if head else None
        if not t or not s:
            unreadable = f
            break
        tests += int(t.group(1))
        skipped += int(s.group(1))
        for case in re.finditer(r'<testcase\s+name="([^"]*)"', text):
            ran.add(re.split(r"[(\[]", case.group(1), 1)[0].strip())
    if unreadable:
        print("::error::%s: %s does not state how many tests ran and were skipped, so it cannot show the lock "
              "ran" % (lock, unreadable))
        status = 1
        continue
    if tests <= 0:
        print("::error::%s ran no tests (%d report(s), 0 tests)" % (lock, len(files)))
        status = 1
        continue
    if skipped != 0:
        print("::error::%s skipped %d of its %d tests: a skipped lock asserts nothing" % (lock, skipped, tests))
        status = 1
        continue
    declared = declared_tests(source)
    missing = sorted(declared - ran)
    if not declared:
        print("::error::%s: no test method could be read from %s, so the report cannot be checked against it"
              % (lock, source))
        status = 1
        continue
    if missing:
        print("::error::%s: %d of the %d test methods it declares did not run: %s (a method-level @Tag, or any "
              "filter that takes a method out at discovery, leaves no skipped count)"
              % (lock, len(missing), len(declared), ", ".join(missing)))
        status = 1
        continue
    print("%s ran: %d tests, none skipped, all %d declared test methods among them" % (lock, tests, len(declared)))
sys.exit(status)
PY
