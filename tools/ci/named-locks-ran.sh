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
#     @TestFactory or @TestTemplate has to be among the report's test cases. The check JUDGES one shape: test
#     methods written directly in the lock's class or in a chain of @Nested member classes of it - each a class, not
#     static, private or abstract - none of which extends or implements anything, and no two test methods of one name
#     in a class; each is required under its own class's name. The source is read as javac reads it: Unicode escapes
#     translated (JLS 3.3), a carriage return alone ending a line (JLS 3.4), identifiers in any script. Any other shape
#     is REFUSED by name rather than guessed at: a test method in a class this check does not judge (a member class
#     without @Nested, an interface, an enum, a record, a local class, a static, private or abstract @Nested class,
#     another top-level class), one inside a method body (an anonymous class, an enum constant's body), a running class
#     that extends or implements a type - of the file or from outside it, whose tests this check cannot see - two
#     same-named test methods of one class (a report names a case without its parameters), a test annotation followed
#     by nothing read as a method name, and a Unicode escape of a backslash or a line terminator. (A test method
#     declared with an annotation of the project's own, meta-annotated with @Test, is not seen here.)
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
import unicodedata

reports, sources, locks = sys.argv[1], sys.argv[2], sys.argv[3:]
# A refusal names a method as the source spells it, in any script; a runner whose locale is ASCII must not turn that
# into a traceback.
sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

# Whitespace - and so a comment, blanked to whitespace below - may sit after @ and around a qualified name's dots:
# `@ /* note */ Test` is a test JUnit runs, and a pattern that wanted the name right after @ did not see it, so a method
# tagged out at discovery went unrequired (closing batch 11 review, Codex P1).
QUALIFIER = r"(?:(?:org\s*\.\s*junit\s*\.\s*jupiter\s*\.\s*(?:api|params)\s*\.\s*))?"
TEST_ANNOTATION = re.compile(r"@\s*" + QUALIFIER + r"(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
NESTED_ANNOTATION = re.compile(r"@\s*" + QUALIFIER + r"Nested\b")


JAVA_KEYWORDS = frozenset(
    "abstract assert boolean break byte case catch char class const continue default do double else enum extends "
    "final finally float for goto if implements import instanceof int interface long native new package private "
    "protected public return short static strictfp super switch synchronized this throw throws transient try void "
    "volatile while true false null _".split())


def identifier_char(c, first=False):
    """Character.isJavaIdentifierStart / isJavaIdentifierPart, by Unicode category: a letter, a letter number, a currency
    symbol or a connector starts an identifier, and digits, combining marks and format characters may follow. A method
    or class named in letters other than ASCII is one JUnit runs, and `[A-Za-z_$]` did not read its name (closing batch
    12 review, subagent P2-1)."""
    category = unicodedata.category(c)
    if category[0] == "L" or category in ("Nl", "Sc", "Pc"):
        return True
    return not first and category in ("Nd", "Mn", "Mc", "Cf")


def java_identifier(token):
    return bool(token) and token not in JAVA_KEYWORDS and identifier_char(token[0], first=True) \
        and all(identifier_char(c) for c in token[1:])


UNICODE_ESCAPE = re.compile(r"\\u+([0-9A-Fa-f]{4})")


def translate_unicode_escapes(text):
    """The source as javac reads it, before anything else (JLS 3.3): (text, refused). A backslash with an even number of
    backslashes right before it, then one or more u and four hex digits, is the character it names - in code, comments
    and strings alike, so `@\\u0054est` is @Test and `g\\u0075ard` is guard; reading the raw text missed the one and
    misnamed the other (closing batch 12 review, Codex P1). An escape of a backslash or of a line terminator is not
    modelled but returned as (offset, escape) to be refused: the one changes which backslash can begin the next escape,
    the other ends a // comment and moves every line after it."""
    out, refused = [], []
    i, n, backslashes = 0, len(text), 0
    while i < n:
        if text[i] == "\\" and backslashes % 2 == 0:
            m = UNICODE_ESCAPE.match(text, i)
            if m:
                c = chr(int(m.group(1), 16))
                if c in "\\\n\r":
                    refused.append((i, m.group(0)))
                out.append(c)
                i, backslashes = m.end(), 0
                continue
        backslashes = backslashes + 1 if text[i] == "\\" else 0
        out.append(text[i])
        i += 1
    return "".join(out), refused


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
            end = n if j < 0 else j + 2
            # The newlines inside kept, so a line counted in the blanked text is the file's line (closing batch 11
            # review, both reviewers: the 16-line licence header put every refusal's line 15 lines early).
            out.append(" " + "\n" * text.count("\n", i, end))
            i = end
        elif text.startswith('"""', i):
            # A text block ends at the first """ that is not escaped: \""" inside it is text (closing batch 8 review).
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == "\\" else 1
            end = n if j >= n else j + 3
            out.append('""' + "\n" * text.count("\n", i, end))
            i = end
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


def past_annotation(text, i):
    """The index past the annotation whose @ is at i: its name, qualified or not, with blanks after the @ and around the
    dots, then its (possibly nested) argument list. A name is read by Java's identifier characters, in any script."""
    n = len(text)
    i += 1
    while True:
        while i < n and text[i].isspace():
            i += 1
        while i < n and identifier_char(text[i]):
            i += 1
        j = i
        while j < n and text[j].isspace():
            j += 1
        if j < n and text[j] == ".":
            i = j + 1
            continue
        i = j
        break
    if i < n and text[i] == "(":
        depth = 0
        while i < n:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
    return i


def strip_annotations(text):
    """Drop annotations, with their (possibly nested) argument lists."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text[i] == "@":
            i = past_annotation(text, i)
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def method_head(code, start):
    """What follows the test annotation whose @ is at start, up to the method's body or its ;, every annotation dropped
    whole however long its arguments: a 4,000-character window ran out inside a long @CsvSource, so the head came back
    empty and the method was skipped without a word (closing batch 12 review, subagent P2-1)."""
    out = []
    i, n = past_annotation(code, start), len(code)
    while i < n and code[i] not in "{;":
        if code[i] == "@":
            i = past_annotation(code, i)
        else:
            out.append(code[i])
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


def drop_balanced(text, opening, closing):
    """The text without its (possibly nested) opening...closing groups."""
    out, depth = [], 0
    for c in text:
        if c == opening:
            depth += 1
        elif c == closing and depth > 0:
            depth -= 1
        elif depth == 0:
            out.append(c)
    return "".join(out)


def supertypes(header):
    """The simple names a class's header extends or implements. Annotations and every <...> go first - the class's
    own type parameters included: class Impl<T extends Base> implements Marker names Marker only, and extends
    Holder.Base or Outer<T>.Base names Base (closing batch 8 review)."""
    h = drop_balanced(strip_annotations(header), "<", ">")
    names = set()
    for keyword in ("extends", "implements"):
        m = re.search(r"\b" + keyword + r"\b(.*?)(?=\bimplements\b|\bpermits\b|$)", h, re.S)
        if m:
            for part in m.group(1).split(","):
                names_in_part = re.findall(r"[^\s.]+", part)
                if names_in_part and java_identifier(names_in_part[-1]):
                    names.add(names_in_part[-1])
    return names


def class_bodies(code):
    """Every class, interface, enum and record declaration of the source, as dicts (start, end, depth, name, kind,
    supers): start and end are the offsets of its body's braces, depth the brace depth directly inside the body.
    'kind' is "top" for a top-level class, "nested" for a member class marked @Nested, "member" for a member class
    without it and "local" for a class declared in a method body.

    A declaration names the body its next brace opens - the next brace OUTSIDE parentheses: an annotation's arguments
    and a record's components can hold braces of their own (implements @A(values = {1}) Marker {), and taking the
    first brace made the class end there (closing batch 7 review, Codex P1 / subagent P3). Any other brace - a method
    body, an anonymous class, an enum constant's body, an initializer - is depth only."""
    opens = []
    stack = []
    pending = None
    depth = 0
    parens = 0
    for m in re.finditer(r"\b(class|interface|enum|record)\s+([^\s<>{}()\[\],;=.@&?:]+)|[{}()]", code):
        token = m.group(0)
        if token == "(":
            parens += 1
        elif token == ")":
            parens -= 1
        elif token == "{":
            depth += 1
            if pending is not None and parens == 0:
                name, nested, header_at, keyword, modifiers, declared_at = pending
                if not stack:
                    kind = "top"
                elif depth == stack[-1]["depth"] + 1:
                    kind = "nested" if nested else "member"
                else:
                    kind = "local"
                stack.append(dict(start=m.start(), depth=depth, name=name, kind=kind, keyword=keyword,
                                  modifiers=modifiers, declared_at=declared_at,
                                  supers=supertypes(code[header_at:m.start()])))
                pending = None
        elif token == "}":
            if stack and stack[-1]["depth"] == depth:
                entry = stack.pop()
                entry["end"] = m.start()
                opens.append(entry)
            depth -= 1
        elif java_identifier(m.group(2)):
            prefix = declaration_prefix(code, m.start())
            modifiers = set(re.findall(r"\b(static|private|abstract)\b", strip_annotations(prefix)))
            pending = (m.group(2), NESTED_ANNOTATION.search(prefix) is not None, m.end(), m.group(1), modifiers,
                       m.start())
    opens.sort(key=lambda e: e["start"])
    return opens


def declared_tests(path):
    """(names, refusals) for the lock's source. names: the test methods it declares, as (nested-class suffix, method
    name) - ("", "a") for the lock's own method, ("$Inner", "b") for one in its @Nested class Inner. Surefire reports
    each test case with the class that declares it (classname), so a method of the same name in another class of the
    lock cannot stand in for it (closing batch 6 review, both reviewers).

    This check JUDGES one shape: test methods written directly in the lock's class or in a chain of @Nested member
    classes of it - each a class, not static, private or abstract - none of which extends or implements anything, with
    no two test methods of one name in a class. Any other shape is a REFUSAL naming what it could not judge, not a
    guess: a test method in a class this check does not judge (a member class without @Nested, an interface, an enum, a
    record, a local class, a static, private or abstract @Nested class, another top-level class), one inside a method
    body (an anonymous class, an enum constant's body), a running class that extends or implements a type, a second
    test method of the same name in a class, a test annotation followed by nothing read as a method name, and a Unicode
    escape of a backslash or a line terminator. Four review rounds each found
    a new shape that a model of JUnit's inheritance and discovery read wrong in one direction or the other (closing
    batches 7 to 10), so the check stops at what it can read exactly and says so at the rest."""
    lock_name = os.path.basename(path)[:-len(".java")]
    # Read in text mode, a carriage return alone or before a newline is one \n (Python's universal newlines), so a line
    # ends where javac ends it (JLS 3.4) and a // comment stops there.
    raw = open(path, encoding="utf-8").read()
    text, refused = translate_unicode_escapes(raw)
    if refused:
        return set(), ["a Unicode escape of a backslash or a line terminator (%s) at line %d, which this check does not "
                       "model" % (escape, raw.count("\n", 0, offset) + 1) for offset, escape in refused]
    code = without_comments_and_strings(text)
    classes = class_bodies(code)

    def chain_at(offset):
        return [e for e in classes if e["start"] < offset < e["end"]]

    def chain_of(entry):
        return [e for e in classes if e["start"] <= entry["start"] < e["end"]]

    def runnable(e):
        # JUnit runs a class: not an interface, an enum or a record, and not a static, private or abstract @Nested
        # class (closing batch 11 review: Codex P2, subagent P3-2).
        return e["keyword"] == "class" and not (e["kind"] == "nested" and e["modifiers"])

    def runs(chain):
        return bool(chain) and chain[0]["kind"] == "top" and chain[0]["name"] == lock_name \
            and all(e["kind"] == "nested" for e in chain[1:]) and all(runnable(e) for e in chain)

    def where(chain):
        return ".".join(e["name"] for e in chain)

    def line_of(offset):
        return code.count("\n", 0, offset) + 1

    def depth_at(offset):
        return code.count("{", 0, offset) - code.count("}", 0, offset)

    refusals = []
    for e in classes:
        chain = chain_of(e)
        # A supertype outside the file too: its tests run under this class's name, and one of the same name stood in
        # for a test declared here that a tag took out (closing batch 12 review, subagent P3-1).
        if runs(chain) and e["supers"]:
            refusals.append("%s (line %d) extends or implements %s, so a test it inherits - from this file or from "
                            "outside it, where this check cannot see - runs under its name and can stand in for one "
                            "declared here" % (where(chain), line_of(e["declared_at"]), ", ".join(sorted(e["supers"]))))
    names = set()
    for m in TEST_ANNOTATION.finditer(code):
        # Annotations dropped BEFORE the head is cut at the first { or ; - an annotation's own arguments can hold a
        # brace (@ValueSource(strings = { ... })), and cutting there first lost the method that followed.
        line = line_of(m.start())
        before, paren, _ = method_head(code, m.start()).partition("(")
        method = re.search(r"([^\s<>\[\],.?&]*)\s*$", before).group(1) if paren else ""
        if not java_identifier(method):
            # Read as "no test here" this was the one silent exit of the check (closing batch 12 review, subagent P2-1).
            refusals.append("the test annotation at line %d is followed by nothing this check reads as a method name, "
                            "so the method it marks cannot be required" % line)
            continue
        chain = chain_at(m.start())
        if not chain:
            refusals.append("%s() at line %d is outside every class" % (method, line))
            continue
        if depth_at(m.start()) != chain[-1]["depth"]:
            refusals.append("%s() at line %d is inside a method body or an anonymous class (an enum constant's body "
                            "included) of %s, which JUnit does not run as the lock's" % (method, line, where(chain)))
            continue
        if chain[0]["kind"] != "top" or chain[0]["name"] != lock_name:
            refusals.append("%s() at line %d is in %s, another top-level class of the file" % (method, line,
                                                                                               where(chain)))
            continue
        if not runs(chain):
            refusals.append("%s() at line %d is in %s, which this check does not judge: it judges the lock's class and "
                            "@Nested classes in it, each a class that is not static, private or abstract (a member "
                            "class without @Nested, an interface, an enum, a record and a local class are outside "
                            "that)" % (method, line, where(chain)))
            continue
        key = ("".join("$" + e["name"] for e in chain[1:]), method)
        if key in names:
            # The report names a test case without its parameters, so one of two same-named methods of a class can be
            # tagged out and the other's run stands in for it (closing batch 11 review, subagent P2-2).
            refusals.append("%s() at line %d is the second test method of that name in %s, and a report names a case "
                            "without its parameters, so one could stand in for the other" % (method, line, where(chain)))
            continue
        names.add(key)
    return names, refusals


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
    declared, refusals = declared_tests(source)
    if refusals:
        print("::error::%s: this check cannot judge %d shape(s) in %s: %s" % (lock, len(refusals), source,
                                                                             "; ".join(refusals)))
        status = 1
        continue
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
