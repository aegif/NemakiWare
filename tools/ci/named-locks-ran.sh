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
#     superclass outside the lock's file, or declared with an annotation of the project's own, is not seen here. One
#     written in a nested class JUnit does not run as written - no @Nested - is required under each @Nested class of
#     the file that extends it, where it runs and is reported; one in a local or an anonymous class is not required.)
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


def declaration_prefix(code, start):
    """The modifiers and annotations written before the keyword at start, back to the previous ; { or } that is not
    inside an annotation's parentheses (@SuppressWarnings({"a"}) holds braces of its own)."""
    i, depth = start - 1, 0
    while i >= 0:
        c = code[i]
        if c == ")":
            depth += 1
        elif c == "(":
            depth -= 1
        elif depth == 0 and c in ";{}":
            break
        i -= 1
    return code[i + 1:start]


def opens_an_anonymous_class(code, brace):
    """Whether the brace at this offset opens the body of an anonymous class: new X(...) { ."""
    i = brace - 1
    while i >= 0 and code[i].isspace():
        i -= 1
    if i < 0 or code[i] != ")":
        return False
    depth = 0
    while i >= 0:
        if code[i] == ")":
            depth += 1
        elif code[i] == "(":
            depth -= 1
            if depth == 0:
                break
        i -= 1
    before = code[max(0, i - 300):i]
    return re.search(r"\bnew\s+[\w$.<>?,\s]+$", before) is not None


def enclosing_classes(code):
    """For each offset, the classes whose bodies contain it, outermost first, each as (name, runs).

    A class, interface, enum or record declaration names the body its next brace opens - the next brace OUTSIDE
    parentheses: an annotation's arguments and a record's components can hold braces of their own
    (implements @A(values = {1}) Marker {), and taking the first brace made the class end there, so its methods were
    read as the outer class's (closing batch 7 review, Codex P1 / subagent P3). 'runs' says whether JUnit runs the
    tests written in that class itself: the lock's own class, and a nested class marked @Nested. A nested class without
    it (a base class others extend), a local class and an anonymous class do not run their own @Test methods, and
    requiring them refused locks whose tests all ran (closing batch 7 review, subagent P3-4).

    Returns (at, classes): at(offset) -> the chain of (name, runs); classes -> (body offset, name, runs, header) for
    every class, the header being what is written between its name and its body (extends, implements)."""
    opens = []  # (offset of the body's opening brace, offset of its closing brace, name, runs, header)
    stack = []
    pending = None
    depth = 0
    parens = 0
    for m in re.finditer(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)|[{}()]", code):
        token = m.group(0)
        if token == "(":
            parens += 1
        elif token == ")":
            parens -= 1
        elif token == "{":
            depth += 1
            if pending is not None and parens == 0:
                name, nested, header_at = pending
                stack.append((m.start(), depth, name, not stack or nested, code[header_at:m.start()]))
                pending = None
            elif pending is None and opens_an_anonymous_class(code, m.start()):
                stack.append((m.start(), depth, "<anonymous>", False, ""))
        elif token == "}":
            if stack and stack[-1][1] == depth:
                start, _, name, runs, header = stack.pop()
                opens.append((start, m.start(), name, runs, header))
            depth -= 1
        else:
            prefix = declaration_prefix(code, m.start())
            pending = (m.group(1), re.search(r"@(?:org\.junit\.jupiter\.api\.)?Nested\b", prefix) is not None,
                       m.end())

    def at(offset):
        return tuple((name, runs) for start, end, name, runs, _ in sorted(opens) if start < offset < end)
    return at, [(start, name, runs, header) for start, _, name, runs, header in sorted(opens)]


def declared_tests(path):
    """The test methods the source declares, as (nested-class suffix, method name): ("", "a") for the lock's own
    method, ("$Inner", "b") for one in its nested class Inner. Surefire reports each test case with the class that
    declares it (classname), so a method of the same name in another class of the lock cannot stand in for it
    (closing batch 6 review, both reviewers)."""
    code = without_comments_and_strings(open(path, encoding="utf-8").read())
    classes_at, classes = enclosing_classes(code)
    names = set()
    for m in TEST_ANNOTATION.finditer(code):
        # Annotations stripped BEFORE the head is cut at the first { or ; - an annotation's own arguments can hold a
        # brace (@ValueSource(strings = { ... })), and cutting there first lost the method that followed. The "@T"
        # stands for the test annotation itself, so arguments of its own (@RepeatedTest(5)) go with it.
        head = strip_annotations("@T" + code[m.end():m.end() + 4000])
        end = min([k for k in (head.find("{"), head.find(";")) if k >= 0] or [len(head)])
        name = re.search(r"([A-Za-z_$][\w$]*)\s*\(", head[:end])
        if name:
            chain = classes_at(m.start())
            if all(runs for _, runs in chain[1:]):
                names.add(("".join("$" + c for c, _ in chain[1:]), name.group(1)))
                continue
            # Declared where JUnit does not run it as written. A nested base class's test runs in each @Nested class
            # that extends it, and surefire reports it under that class: required there, by the subclass's name, so a
            # base method silenced at discovery is still seen. A local or an anonymous class's runs nowhere.
            base = chain[-1][0]
            if base == "<anonymous>" or not all(runs for _, runs in chain[1:-1]):
                continue
            for start, sub, runs, header in classes:
                if runs and re.search(r"\bextends\s+(?:[\w$]+\.)*" + re.escape(base) + r"\b", header):
                    under = classes_at(start + 1)
                    if all(r for _, r in under[1:]):
                        names.add(("".join("$" + c for c, _ in under[1:]), name.group(1)))
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
        for case in re.finditer(r'<testcase\s+name="([^"]*)"\s+classname="([^"]*)"', text):
            ran.add((case.group(2), re.split(r"[(\[]", case.group(1), 1)[0].strip()))
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
    missing = sorted((lock.rsplit(".", 1)[-1] + suffix).replace("$", ".") + "." + method
                     for suffix, method in declared if (lock + suffix, method) not in ran)
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
