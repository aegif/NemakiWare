/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.util.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lock that cannot look at its subject says so in a way the control runner can tell apart.
 *
 * <h2>The defect this closes</h2>
 *
 * <p>The negative-control runner decides a control FIRED by looking for an assertion in the
 * failed test's stanza. {@link JavaSource#methodBody} and the reflection helpers that report a
 * renamed method threw {@code AssertionError} — so the one outcome the judgement exists to
 * exclude, <em>the sabotage broke the harness</em>, was indistinguishable from the guard
 * tripping. Rename a method that a source lock reads and the control reports FIRED while
 * measuring nothing at all. It was recorded as a known runner defect for two rounds.
 *
 * <p>Both halves are pinned here: the helpers raise {@link HarnessBroken}, and
 * {@code HarnessBroken} is not an {@code AssertionError} — the second half is what the runner
 * relies on, and a well-meant "make it an AssertionError so JUnit reports it nicely" would
 * silently restore the defect.
 *
 * <h2>The rule a new lock has to follow</h2>
 *
 * <p><strong>Breakage says {@link HarnessBroken}; a failure asserts.</strong> If your lock could
 * not look at its subject — the method was renamed, the file is not there, the source does not
 * parse — throw {@code HarnessBroken}. If the production broke the promise your lock measures,
 * use the assertion library ({@code assertX}, or {@code Assertions.fail(msg, cause)}).
 *
 * <p><strong>Never produce one of those types yourself.</strong> The control runner decides a
 * control fired by looking for the type's NAME in the failure stanza, so the sweep below forbids,
 * in any test source of any module:
 *
 * <ul>
 *   <li>{@code new AssertionError(…)} and the other nine type names the runner scores — however
 *       qualified, spaced or wrapped ({@code orElseThrow(() -> new AssertionError(…))} counts),
 *       including the constructor as a method reference ({@code AssertionError::new});</li>
 *   <li>a variable or field OF one of those types (that is the route to {@code throw it;}) —
 *       {@code AssertionError thrown = assertThrows(…)} is fine, qualified or not, because it
 *       asserts rather than builds;</li>
 *   <li>the Java {@code assert} STATEMENT. Surefire enables assertions, so the JVM builds a
 *       {@code java.lang.AssertionError} and the runner scores it — with no {@code new} anywhere
 *       in the source. Write {@code assertTrue} / {@code assertNotNull} instead.</li>
 * </ul>
 *
 * <p>A reflective call needs both arms — {@code InvocationTargetException} is the SUBJECT throwing
 * (assert), anything else is the lock being unable to look (breakage).
 *
 * <p>Rethrowing an assertion the SUBJECT threw is correct and deliberately not swept: this tree
 * writes it as {@code if (t instanceof AssertionError e) throw e;}, and a {@code catch} of one is
 * how a meta-test checks that a lock fires. A {@code catch} form was swept for one round and only
 * ever refused those legitimate shapes (round-17 review: both reviewers).
 *
 * <p>Adding an arm to {@code thePomReadersTakeTheUnion}: an arm that expects a LIST of
 * candidates goes through {@code candidatesOrRefusal} (the reader's refusal is one of the answers
 * it compares, and a thrown {@code HarnessBroken} would be excluded by the runner before it looked
 * for an assertion); an arm that measures the REFUSAL itself uses {@code assertThrows}. The rule
 * lived only in the plan for a round (round-24 review, subagent P3-5).
 *
 * <p>The limits a lock's author has to know. The modules outside {@code core} have no
 * {@code HarnessBroken} on their test path, so a lock there can only avoid producing an
 * assertion type — its breakage reads to the runner as "did not fire" rather than being
 * excluded by name. And saying breakage through an ASSERTION METHOD
 * ({@code assertTrue(found > 0, "X is gone")}) cannot be swept at all: it is
 * indistinguishable from an ordinary assertion, two predicates measured it as 50 and 404
 * occurrences, and neither is the set. That half is residual R191.
 *
 * <p>The FULL list of limits lives in one place — the R189 row of
 * {@code docs/design/fail-closed-reads.md} — and is deliberately not repeated here with a
 * count: two consecutive rounds found this javadoc stating a number the canon's list had
 * already outgrown (round-19 review, subagent P2-4).
 *
 * <p>And one cost of reading the runner's list: it is a DETECTION list being used as a
 * PROHIBITION list. A CamelCase marker added over there instantly forbids {@code new X(…)} in all
 * of this tree's test sources — which is right for assertion types and would be wrong for, say,
 * {@code NullPointerException} (round-18 review, subagent P3-5).
 *
 * <p>Previously this rule lived in a {@code //} comment inside the sweep, where a new lock's
 * author would not read it (round-16 review, subagent P3); the {@code assert}-statement clause
 * went back into a method comment for one round and is here now (round-18 review, subagent P2).
 */
class HarnessBreakageIsNotAFiringTest {

    @Test
    @DisplayName("HarnessBroken is deliberately not an AssertionError")
    void harnessBreakageIsNotAnAssertion() {
        assertFalse(AssertionError.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken became an AssertionError — the control runner reads that as "
                        + "'the lock fired', so every source lock whose method was renamed "
                        + "would report a firing while measuring nothing");
        assertTrue(RuntimeException.class.isAssignableFrom(HarnessBroken.class),
                "HarnessBroken must still fail the test loudly");
    }

    @Test
    @DisplayName("a method the lock cannot find raises HarnessBroken, not an assertion")
    void aMissingMethodRaisesHarnessBroken() {
        String source = "class A {\n    void present() {\n        int x = 1;\n    }\n}\n";

        assertThrows(HarnessBroken.class,
                () -> JavaSource.methodBody(source, "void absent()"),
                "a lock reading a method that is no longer there reported an assertion — "
                        + "which the runner counts as the protection working");

        // The control: a method that IS there is read normally, so the refusal above is not
        // simply "this helper throws now".
        String body = JavaSource.methodBody(source, "void present()");
        assertTrue(body.contains("int x = 1;"), "the ordinary read broke: " + body);
    }

    @Test
    @DisplayName("unbalanced braces raise HarnessBroken, not an assertion")
    void unbalancedBracesRaiseHarnessBroken() {
        assertThrows(HarnessBroken.class,
                () -> JavaSource.methodBody("class A {\n    void half() {\n", "void half()"));
    }

    @Test
    @DisplayName("no test BUILDS a type whose NAME the control runner scores as a firing")
    void noTestThrowsAnAssertionError() throws Exception {
        // BY THE FORM, not by the wording (owner's decision 2026-10-16, residual R189). Three
        // vocabularies were tried and each missed the next phrasing, and `new AssertionError(e)`
        // cannot be swept by words at all. The rule that needs no vocabulary: a lock never BUILDS
        // one of the types the runner scores. Either it is asserting, and the assertion library
        // says so, or it could not look at its subject, and that is HarnessBroken.
        //
        // The type names come FROM THE RUNNER (scoredTypeNames), so a new marker over there is
        // covered here without an edit. The first two cuts of this sweep were narrower than the
        // runner twice over — one exact string, then only names containing "Assertion…Error"
        // while the runner also scores WantedButNotInvoked, TooFewActualInvocations and the rest
        // (round-17 review: Codex P1).
        List<Path> roots = testRoots();
        List<Pattern> forms = scoredForms();
        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            int inThisRoot = 0;
            for (Path file : javaSourcesOrBreak(root)) {
                String code = readOrBreak(file);
                inThisRoot++;
                for (Pattern form : forms) {
                    Matcher hit = form.matcher(code);
                    while (hit.find()) {
                        offenders.add(file + " 「" + hit.group().replace('\n', ' ').trim() + "」");
                    }
                }
            }
            // PER ROOT, not a total: 742 of this tree's 761 test sources are in core, so a total
            // floor passes with every other module's tests dropped (round-17 review, subagent P3).
            if (inThisRoot == 0) {
                throw new HarnessBroken("the sweep read no test source under " + root
                        + ", so whatever it cleared there it did not look at");
            }
        }

        assertTrue(offenders.isEmpty(),
                "these locks PRODUCE a type the control runner scores as a firing — by building "
                        + "one, by holding one in a variable, or with a Java `assert` statement, "
                        + "which makes the JVM build one — so a sabotage that only broke the "
                        + "harness is recorded as the guard tripping. Say it with HarnessBroken "
                        + "when the lock could not look at its subject, assert it with the "
                        + "assertion library when the production broke its promise, and write "
                        + "`assertTrue`/`assertNotNull` instead of `assert`. The rule is in this "
                        + "class's javadoc and the limits in the canon's R189 row: "
                        + offenders);
    }

    @Test
    @DisplayName("a lock that reflects tells a thrown subject apart from a method it could not find")
    void reflectionDoesNotCallAProductThrowAHarnessBreakage() throws Exception {
        // The other half of the same classification. `Method.invoke` wraps whatever the SUBJECT
        // threw in InvocationTargetException, so a `catch (Exception e) { throw new
        // HarnessBroken(e); }` around a reflective call reports a production regression as "this
        // lock could not look at its subject" (round-16 review, Codex P2).
        //
        // The rule, by file so that no brace matching is needed: a test that reflects AND converts
        // to HarnessBroken must have a REAL InvocationTargetException clause — a catch of it, or an
        // assertThrows of it. Naming the type was enough at first, which an unused import
        // satisfies (round-17 review, subagent P3-3). What this still does not prove: that the
        // clause is placed around the right call.
        List<String> conflating = new ArrayList<>();
        for (Path root : testRoots()) {
            int inThisRoot = 0;
            for (Path file : javaSourcesOrBreak(root)) {
                String code = readOrBreak(file);
                inThisRoot++;
                if (!code.contains(".invoke(") || !code.contains("new HarnessBroken(")) {
                    continue;
                }
                boolean caught = Pattern.compile("catch\\s*\\([^)]*InvocationTargetException")
                        .matcher(code).find();
                boolean asserted = Pattern.compile(
                        "assertThrows\\s*\\(\\s*(?:[\\w.]*\\.)?InvocationTargetException\\s*\\.class")
                        .matcher(code).find();
                if (!caught && !asserted) {
                    conflating.add(file.toString());
                }
            }
            // PER ROOT, like its sibling: a total floor passes with every root but core dropped,
            // and this reader had the weaker form for a round (round-18 review, subagent P3-3).
            if (inThisRoot == 0) {
                throw new HarnessBroken("this reader read no test source under " + root
                        + ", so whatever it cleared there it did not look at");
            }
        }

        assertTrue(conflating.isEmpty(),
                "these locks reflect and convert to HarnessBroken with no InvocationTargetException "
                        + "clause of any kind, so whatever the subject threw is reported as the lock "
                        + "being unable to look at it: " + conflating);
    }

    @Test
    @DisplayName("the refusal type's name satisfies the runner's own breakage predicate")
    void theRefusalTypeIsStillExcludedByTheRunner() throws Exception {
        // LOCKSTEP with the runner, not with a comment. The runner decides "the harness broke"
        // with head.endsWith("HarnessBroken") on each line of the failure stanza, so the subtype's
        // NAME is part of the rule: calling the next one RefusalNotABreakage would quietly undo
        // round-25's fix and a bare call wrapped in assertDoesNotThrow would be scored as a firing
        // (round-26 review, subagent P3-2).
        String runner = readTextOrBreak(Path.of("../tools/negative-controls/run_negative_controls.py"));
        Matcher predicate = Pattern.compile("head\\.endswith\\(\"([A-Za-z]+)\"\\)").matcher(runner);
        // EVERY place the runner writes this predicate, and they have to agree. Taking the FIRST
        // match read whatever came earliest in 25,000 lines: a control whose `what=` quotes the
        // predicate with outer single quotes would put prose ahead of the code, and this lock
        // would then test the refusal type against a suffix the runner does not use
        // (round-27 review, subagent P3-7 — one match today, at the runner's own decision).
        java.util.Set<String> suffixes = new java.util.LinkedHashSet<>();
        while (predicate.find()) {
            suffixes.add(predicate.group(1));
        }
        if (suffixes.isEmpty()) {
            throw new HarnessBroken("the runner no longer decides breakage by a type-name suffix "
                    + "(head.endswith(\"…\")), so this lock cannot tell whether the refusal type is "
                    + "still excluded");
        }
        assertEquals(1, suffixes.size(),
                "the runner names more than one breakage suffix " + suffixes + ", so which one "
                        + "decides is read from whichever line comes first — write the predicate "
                        + "once, or this lock measures the wrong one");
        String suffix = suffixes.iterator().next();
        assertTrue(RefusalHarnessBroken.class.getSimpleName().endsWith(suffix),
                "the refusal type is called " + RefusalHarnessBroken.class.getSimpleName()
                        + " and the runner excludes a breakage by the suffix 「" + suffix + "」, so "
                        + "this type's throws would be scored as the guard firing");
        assertTrue(HarnessBroken.class.getSimpleName().endsWith(suffix),
                "even HarnessBroken itself no longer matches the runner's suffix 「" + suffix
                        + "」, so nothing in this tree is excluded as breakage any more");

        // EVERY subtype, not only the one this class happens to declare. The first cut named
        // RefusalHarnessBroken in a class literal, which locks that ONE name and leaves the next
        // refusal type — written by whoever reads the comment above instead of the runner —
        // unmeasured: a sibling called RefusalNotABreakage could be added beside it with this
        // lock still green (that is what IN5 sabotages, and the narrow form could not be
        // measured at all: renaming the class in one edit does not compile).
        Pattern subtype = Pattern.compile(
                "class\\s+([A-Za-z_$][\\w$]*)(?:<[^>]*>)?\\s+extends\\s+([\\w.$]*HarnessBroken)\\b");
        // The other way a breakage type gets a name the runner does not recognise: an ANONYMOUS
        // subclass. `new HarnessBroken("...") {}` is reported by Surefire as `Outer$1`, which does
        // not end with the suffix, so a lock that could not look at its subject would be scored as
        // the guard firing — the exact harm this class exists to prevent (round-27 review: Codex
        // P1, subagent P3-5; measured 0 live today out of 77 `new …HarnessBroken(` sites).
        Pattern anonymous = Pattern.compile(
                "new\\s+(?:[\\w.$]*\\.)?[\\w$]*" + suffix + "\\s*\\([^;{]*\\)\\s*\\{");
        // Discriminating, before the sweep runs: the form has to match the anonymous shape and
        // leave the ordinary throw alone, or a dead regex sweeps clean (this tree's own rule).
        assertTrue(anonymous.matcher("throw new HarnessBroken(\"x\") {};").find(),
                "this form no longer matches an anonymous subclass of the breakage type, so the "
                        + "sweep below would clear the tree without looking for it");
        assertFalse(anonymous.matcher("throw new HarnessBroken(\"x\");").find(),
                "this form matches an ordinary throw, so it would refuse every honest breakage");
        List<String> misnamed = new ArrayList<>();
        int declarations = 0;
        for (Path root : testRoots()) {
            int inThisRoot = 0;
            for (Path file : javaSourcesOrBreak(root)) {
                // readOrBreak ALREADY strips literal text as well as comments
                // (JavaSource.withoutLiteralText), so a declaration written INSIDE a string —
                // a parser fixture, say — never reaches this sweep. Round 26 also called
                // withoutComments on the result, which reads as if strings were kept and was
                // the premise of a round-27 finding (Codex P2, premise measured false).
                String code = readOrBreak(file);
                inThisRoot++;
                Matcher declared = subtype.matcher(code);
                while (declared.find()) {
                    declarations++;
                    if (!declared.group(1).endsWith(suffix)) {
                        misnamed.add(file + " 「" + declared.group().trim() + "」");
                    }
                }
                Matcher inline = anonymous.matcher(code);
                while (inline.find()) {
                    misnamed.add(file + " 「" + inline.group().replace('\n', ' ').trim() + "」");
                }
            }
            // PER ROOT, like this class's other sweeps: a total floor passes with every module
            // but core dropped.
            if (inThisRoot == 0) {
                throw new HarnessBroken("this reader read no test source under " + root
                        + ", so whatever it cleared there it did not look at");
            }
        }
        if (declarations == 0) {
            throw new HarnessBroken("no subtype of HarnessBroken was found in this tree's test "
                    + "sources, and this class declares one, so this sweep is not reading the "
                    + "sources it is meant to read");
        }

        assertTrue(misnamed.isEmpty(),
                "a breakage type whose runtime name does NOT end with 「" + suffix + "」 — either a "
                        + "subtype declared under another name, or an ANONYMOUS subclass, which "
                        + "Surefire reports as Outer$1. The control runner decides «the harness "
                        + "broke» by that suffix, so throwing one of these is scored as a lock "
                        + "firing rather than as the lock being unable to look at its subject: "
                        + misnamed);
    }

    @Test
    @DisplayName("the sweep's forms match every shape the runner scores, and its roots cover every module's tests")
    void theSweepMatchesWhatTheRunnerScores() throws Exception {
        // What earlier cuts let through, each one a shape the runner scores as a firing (round-16
        // and round-17 reviews). Without this the sweep passes by matching nothing.
        List<Pattern> forms = scoredForms();
        for (String slips : List.of(
                "throw new AssertionError(\"x\");",
                "throw new AssertionError (\"x\");",
                "throw new\n        AssertionError(\"x\");",
                "throw new java.lang.AssertionError(e);",
                "throw new AssertionFailedError(\"x\");",
                "throw new org.opentest4j.AssertionFailedError(\"x\");",
                "throw new MockitoAssertionError(\"x\");",
                "throw new NeverWantedButInvoked(\"the fixture is gone\");",
                "throw new org.mockito.exceptions.verification.WantedButNotInvoked(\"x\");",
                "throw new TooFewActualInvocations(\"x\");",
                "        .orElseThrow(() -> new AssertionError(\"the entry vanished\"));",
                "        AssertionError failure = somethingThatBuildsOne();",
                "        AssertionError _failure = somethingThatBuildsOne();",
                "        AssertionError Failure = somethingThatBuildsOne();",
                "        } catch (Exception e) {AssertionError failure = f();throw failure;}",
                "        int x = 0;AssertionError pending = f();",
                "        }AssertionError pending = f();",
                "        assert found > 0 : \"the view answered nothing\";",
                "        if (x) assert found > 0 : \"the view answered nothing\";",
                "        assert m.equals(Map.of(\"a\", new int[]{1})) : \"the shape moved\";",
                "        .orElseThrow(AssertionError::new);",
                "        .orElseThrow(MySpecialAssertionError::new);",
                "        assert(found > 0) : \"the view answered nothing\";",
                "        assert!flag : \"the view answered nothing\";",
                "        throw new @Nullable AssertionError(\"could not read\");",
                "        throw new @Size(min = 1) AssertionError(\"could not read\");",
                "        .orElseThrow(AssertionErrorFactory::new);",
                "            case 1:assert found > 0 : \"the view answered nothing\";")) {
            assertTrue(forms.stream().anyMatch(f -> f.matcher(slips).find()),
                    "this sweep does not see 「" + slips + "」, and the runner would score the "
                            + "harness breaking as a control firing");
        }

        // And it must not call the ordinary ways of ASSERTING an AssertionError an offender, or
        // the rule cannot be kept at all. Rethrowing one the SUBJECT threw is correct and stays
        // out: this tree writes it as `instanceof AssertionError e` and a catch of it is how a
        // meta-test checks a lock fires (round-17 review: Codex P2, subagent P2-2).
        for (String fine : List.of(
                "assertThrows(AssertionError.class, () -> lock.run());",
                "AssertionError thrown = assertThrows(AssertionError.class, () -> lock.run());",
                "throw new HarnessBroken(\"could not read \" + file, e);",
                "assertFalse(HarnessBroken.class.isAssignableFrom(AssertionError.class));",
                "Class<?> type = AssertionFailedError.class;",
                "if (thrown instanceof AssertionError e) {\n    throw e;\n}",
                "} catch (AssertionError expected) {\n    assertEquals(\"x\", expected.getMessage());\n}",
                "assertTrue(found > 0, \"the view answered nothing\");",
                "AssertionError thrown = Assertions.assertThrows(AssertionError.class, lock::run);",
                "AssertionError thrown = org.junit.jupiter.api.Assertions.assertThrows(T.class, r);",
                "assertThat(found).isGreaterThan(0);",
                "AssertionError t = Assertions.<AssertionError>assertThrows(E.class, lock::run);",
                "assertTrue(found > 0);")) {
            assertTrue(forms.stream().noneMatch(f -> f.matcher(fine).find()),
                    "this sweep calls 「" + fine + "」 an offender, so the rule it enforces cannot "
                            + "be followed");
        }

        // Every type-like marker the runner scores is covered by a form. This is what keeps the
        // two in lockstep rather than in sync by hand.
        for (String type : scoredTypeNames()) {
            String built = "throw new " + type + "(\"x\");";
            assertTrue(forms.stream().anyMatch(f -> f.matcher(built).find()),
                    "the runner scores 「" + type + "」 as a firing and this sweep does not see a "
                            + "lock building one");
        }

        List<String> roots = testRoots().stream().map(Path::toString).toList();
        for (String module : List.of("evidence-verifier-core", "evidence-verifier-cli", "common")) {
            assertTrue(roots.stream().anyMatch(r -> r.contains("/" + module + "/")),
                    "the sweep does not read " + module + "'s tests: " + roots);
        }
        assertTrue(roots.stream().anyMatch(r -> r.equals("src/test/java")
                        || r.endsWith("/core/src/test/java")),
                "the sweep does not read this module's own tests. 「contains(core/src/test/java)」 "
                        + "was the first form of this check and evidence-verifier-core satisfies "
                        + "it (round-17 review, subagent P3-1): " + roots);
    }

    @Test
    @DisplayName("a path's four answers are told apart on a real filesystem")
    void theReaderTellsAbsentFromUnreadable() throws Exception {
        // DRIVEN on a real filesystem: three of these four answers could not be reached from this
        // reactor at all, and the round-23 cut classified two of them wrongly while claiming it had
        // separated them (round-24 review, subagent P2-4).
        Path scratch = Files.createTempDirectory("nw-presence");
        try {
            Path directory = Files.createDirectory(scratch.resolve("adir"));
            Path file = Files.writeString(scratch.resolve("afile"), "x");
            Path absent = scratch.resolve("nothing-here");

            assertEquals(Presence.DIRECTORY, classify(directory));
            assertEquals(Presence.NOT_A_DIRECTORY, classify(file),
                    "a path that exists and is a file was not told apart — the round-23 cut "
                            + "refused it as 「could not tell」, which is a false reason");
            assertEquals(Presence.ABSENT, classify(absent));
            // CANNOT_TELL: a directory whose parent denies traversal. Without this the fourth arm
            // was unmeasured and IK5 (which turns it into ABSENT) did not fire — the arm that
            // carries this tree's whole rule (round-25, measured). It stays in THIS method with
            // the other three answers, and the arms that cannot be measured on some machines
            // (the symlink, the permission bits) say so with HarnessBroken — which is why the
            // fixtures that do not depend on the environment live in their own test below
            // (round-26 review, subagent P3-3: an unmeasurable arm was taking the measurable
            // ones down with it).
            Path locked = Files.createDirectory(scratch.resolve("locked"));
            Path inside = Files.createDirectory(locked.resolve("inside"));
            Presence blocked;
            try {
                Files.setPosixFilePermissions(locked, java.util.Set.of());
                blocked = classify(inside);
            } catch (UnsupportedOperationException noPosix) {
                throw new HarnessBroken("this filesystem has no POSIX permissions, so the "
                        + "could-not-tell arm could not be measured", noPosix);
            } finally {
                try {
                    Files.setPosixFilePermissions(locked,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                } catch (Exception ignored) {
                    // the cleanup below will report what it can
                }
            }
            if (blocked == Presence.DIRECTORY) {
                throw new HarnessBroken("this process reads through a directory with no "
                        + "permissions (running as root?), so the could-not-tell arm could not "
                        + "be measured");
            }
            assertEquals(Presence.CANNOT_TELL, blocked,
                    "a path whose parent denies traversal was not reported as 「could not tell」 — "
                            + "it is the one answer this tree's rule is about");

            // LAST, and created here rather than at the top: a filesystem without symlinks is
            // this lock being unable to look at its subject, not a finding (round-25 review,
            // subagent P3-4) — but creating it first took the three arms above down with it on
            // such a machine, which is the same defect round 26 fixed for the pom fixtures and
            // left here (round-27 review, subagent P3-3).
            Path dangling = scratch.resolve("dangling");
            try {
                Files.createSymbolicLink(dangling, scratch.resolve("also-nothing"));
            } catch (Exception cannotLink) {
                throw new HarnessBroken("could not create a symlink under " + scratch
                        + ", so the broken-symlink arm could not be measured", cannotLink);
            }
            assertEquals(Presence.ABSENT, classify(dangling),
                    "a broken symlink: nothing is reachable through it, so absent is the honest "
                            + "answer — the comment that claimed notExists tells it apart from "
                            + "absent was wrong");
        } finally {
            // Cleanup must never REPLACE a failure: Files.walk itself throws, and an exception
            // from a finally block discards the assertion that was on its way out — the runner
            // would then read an IOException instead of the firing (round-25 review, subagent
            // P3-4).
            try (var walk = Files.walk(scratch)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // a scratch directory that will not go is not this lock's subject
                    }
                });
            } catch (Exception ignored) {
                // same
            }
        }

    }

    @Test
    @DisplayName("the pom readers take the union, and a comment that mentions a tag does not eat one")
    void thePomReadersTakeTheUnion() {
        // DRIVEN with synthetic poms. Three arms of this reader were added over two rounds and
        // none of them could be reached by this tree's own poms (no module declares a
        // testSourceDirectory, none nests a reactor), so they were asserted about rather than
        // measured — the shape this tree calls an unreachable arm (round-19 review, subagent P3-3).
        String commentMentionsModules = """
                <project>
                  <modules>
                    <module>a</module>
                    <!-- the optional ones live in <modules> inside <profiles> below -->
                    <module>b</module>
                  </modules>
                  <profiles><profile><modules><module>optional</module></modules></profile></profiles>
                </project>
                """;
        String commentedOutModule = """
                <project>
                  <modules>
                    <module>real</module>
                    <!-- <module>ghost</module> -->
                  </modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(commentedOutModule),
                "a module commented OUT inside <modules> was taken, and this sweep would then "
                        + "refuse the whole build for a directory nobody declared. The "
                        + "comment-mentions-the-tag fixture below stopped discriminating this once "
                        + "the reader was scoped to <modules> (round-21 review, subagent P3)");

        assertEquals(List.of("a", "b", "optional"), modulesIn(commentMentionsModules),
                "a comment that merely mentions a tag changed which modules this sweep reads. The "
                        + "round-18 form deleted everything from the comment to the real "
                        + "</profiles>, which dropped real <module> lines in silence");

        String profileOnly = """
                <project>
                  <profiles><profile><modules><module>extra</module></modules></profile></profiles>
                </project>
                """;
        assertEquals(List.of("extra"), modulesIn(profileOnly),
                "a module declared only inside a profile is not read. For a SWEEP, reading one the "
                        + "active build would skip costs nothing and missing one is the defect");

        String property = """
                <project>
                  <properties><tests.root>${project.basedir}/src/locks/java</tests.root></properties>
                  <build><testSourceDirectory>${tests.root}</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(property),
                "a property reference the pom itself declares is not resolved, and the round-18 "
                        + "form refused the whole build for it");

        String unresolvable = """
                <project>
                  <build><testSourceDirectory>${from.the.parent}</testSourceDirectory></build>
                </project>
                """;
        HarnessBroken refused = assertThrows(HarnessBroken.class,
                () -> testRootCandidates(unresolvable, "synthetic"),
                "a property this pom does not declare was passed over in favour of the default, so "
                        + "the sweep would go on to say it read a module whose tests may sit in a "
                        + "directory it cannot name — an over-refusal traded for a fail-open");
        assertTrue(refused.getMessage().contains("${from.the.parent}"),
                "the refusal does not name the property nobody can resolve: "
                        + refused.getMessage());

        String twoReferences = """
                <project>
                  <properties>
                    <src.root>${project.basedir}/src</src.root>
                    <flavour>locks</flavour>
                  </properties>
                  <build><testSourceDirectory>${src.root}/${flavour}/java</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("src/locks/java", "src/test/java"),
                // The REFUSAL is one of the answers this arm compares, not a harness breakage:
                // an unresolved reference makes testRootCandidates throw, and both
                // assertDoesNotThrow and a bare call leave `Caused by: HarnessBroken` in the
                // stanza, which the runner excludes before it looks for an assertion — the control
                // was scored FIRED FOR THE WRONG REASON twice that way.
                candidatesOrRefusal(twoReferences),
                "a value with TWO property references the pom itself declares was not resolved. "
                        + "Round 20 resolved the first reference only and then refused the rest as "
                        + "unresolvable, so this very pom was refused — and nothing measured it, so "
                        + "reverting the fix left every test green (round-22 review, subagent P2-3)");

        String twoProfiles = """
                <project>
                  <profiles>
                    <profile><properties><tests.root>src/a</tests.root></properties></profile>
                    <profile><properties><tests.root>src/b</tests.root></properties></profile>
                  </profiles>
                  <build><testSourceDirectory>${tests.root}</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("src/a", "src/b", "src/test/java"),
                candidatesOrRefusal(twoProfiles),
                "two profiles give the same property different directories and only the first was "
                        + "taken, so the other profile's .java files were never read");

        String pluginTestSourceDirectory = """
                <project>
                  <build>
                    <testSourceDirectory>src/locks/java</testSourceDirectory>
                    <plugins><plugin><configuration>
                      <testSourceDirectory>fixtures/java</testSourceDirectory>
                    </configuration></plugin></plugins>
                  </build>
                </project>
                """;
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(pluginTestSourceDirectory),
                "a <testSourceDirectory> inside a plugin's own configuration was taken for the "
                        + "build's, so this sweep would read a directory Maven never compiles — an "
                        + "over-refusal of a valid pom (round-23 review, Codex P2)");

        // THE FOUR CASES, as fixtures. They were measured once and written into a comment, so
        // reverting the scanner to either earlier form left every assertion green — which is why
        // this same defect changed hands three rounds running (round-26 review, subagent P1-1,
        // who reverted both forms and measured zero failures).
        String promotion = """
                <project>
                  <description><![CDATA[<modules><module>ghost</module></modules>]]></description>
                  <modules><module>real</module></modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(promotion),
                "a <module> written inside CDATA — text to Maven — was read as structure, and the "
                        + "reactor is then refused for a directory nobody declared");
        assertEquals(List.of("real"), requiredModulesIn(promotion),
                "the same promotion through the required-modules reader");

        // A REAL <profiles> after the mention, or the world where the span is eaten cannot
        // happen: without a closing </profiles> the non-greedy strip has nothing to match and
        // every implementation answers the same, right or wrong (round-27 review, subagent P2-2,
        // who measured this fixture green on all three implementations).
        String cdataNamesProfiles = """
                <project>
                  <description><![CDATA[turn on <profiles> for the extras]]></description>
                  <modules><module>core</module></modules>
                  <profiles><profile><modules><module>extra</module></modules></profile></profiles>
                </project>
                """;
        assertEquals(List.of("core"), requiredModulesIn(cdataNamesProfiles),
                "a CDATA section that merely MENTIONS <profiles> swallowed the real <modules> up to "
                        + "the first real </profiles>, so a module that must exist was treated as "
                        + "optional");

        // A REAL <plugins> too, and AFTER the build's own declaration, so the eaten span covers
        // something that matters (same finding).
        String cdataNamesPlugins = """
                <project>
                  <description><![CDATA[configure <plugins> as usual]]></description>
                  <build>
                    <testSourceDirectory>src/locks/java</testSourceDirectory>
                    <plugins><plugin><configuration>
                      <testSourceDirectory>fixtures/java</testSourceDirectory>
                    </configuration></plugin></plugins>
                  </build>
                </project>
                """;
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(cdataNamesPlugins),
                "a CDATA section that mentions <plugins> swallowed the build's own declaration, so "
                        + "the directory this module keeps its tests in was never read");

        String cdataNamesComment = """
                <project>
                  <description><![CDATA[write <!-- to open a comment]]></description>
                  <modules><module>real</module></modules>
                  <build><testSourceDirectory>src/locks/java</testSourceDirectory></build>
                  <!-- a normal trailing comment -->
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(cdataNamesComment),
                "a CDATA section containing `<!--` let the comment stripper eat the real markup "
                        + "that followed it — the fail-open a single left-to-right pass closes");
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(cdataNamesComment),
                "the same, through the test-root reader");

        String processingInstruction = """
                <project>
                  <?nemaki <modules><module>ghost</module></modules> ?>
                  <modules><module>real</module></modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(processingInstruction),
                "a processing instruction is not structure, and its text was read as a module");

        // The DOCTYPE's internal subset is the other place legal XML carries raw `<`: an entity
        // declaration's value. Maven's reader never sees it as an element (round-26 review,
        // subagent P3-4, which measured [ghost, real] here).
        String doctypeInternalSubset = """
                <!DOCTYPE project [<!ENTITY g "<modules><module>ghost</module></modules>">]>
                <project>
                  <modules><module>real</module></modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(doctypeInternalSubset),
                "an entity declared in the DOCTYPE's internal subset was read as structure, so a "
                        + "valid pom would have been refused for a module nobody declared");
        assertEquals(List.of("real"), requiredModulesIn(doctypeInternalSubset),
                "the same, through the required-modules reader");

        // The subset ends at the `]` that CLOSES it, not at the first one: a `]` inside an
        // entity's value ended the declaration early and everything after it — including another
        // entity's <modules> — came out as structure. Both reviewers measured [ghost, real] on
        // the round-26 scanner, and the fixture above did not discriminate it because its first
        // `]` happened to be the real end (round-27 review: Codex P1, subagent P2-1).
        String doctypeQuotedBracket = """
                <!DOCTYPE project [<!ENTITY bracket "]"><!ENTITY g "<modules><module>ghost</module></modules>">]>
                <project>
                  <modules><module>real</module></modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(doctypeQuotedBracket),
                "a `]` inside an entity's value ended the internal subset, so the entity after it "
                        + "was read as structure and a valid pom is refused for a module nobody "
                        + "declared");
        assertEquals(List.of("real"), requiredModulesIn(doctypeQuotedBracket),
                "the same, through the required-modules reader");

        // And a `>` inside a quoted system literal, which is legal XML as well.
        String doctypeQuotedAngle = """
                <!DOCTYPE project SYSTEM "urn:<modules><module>ghost</module></modules>">
                <project>
                  <modules><module>real</module></modules>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(doctypeQuotedAngle),
                "the DOCTYPE's quoted system literal was cut at the first `>` inside it, so the "
                        + "rest of the literal was read as structure");

        // A property declared WITH an attribute. Round 26 widened the blocks and the element
        // reader and left the property-declaration pattern strict, so this valid pom was refused
        // as unresolvable (round-27 review, subagent P3-2, measured).
        String propertyWithAttribute = """
                <project>
                  <properties><tests.root xml:space="preserve">src/locks</tests.root></properties>
                  <build><testSourceDirectory>${tests.root}/java</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(propertyWithAttribute),
                "an attribute on a property's own declaration made it read as undeclared, and the "
                        + "module's test sources were refused rather than read");

        String attributesOnTags = """
                <project>
                  <modules combine.children="append"><module>real</module></modules>
                  <build><testSourceDirectory xml:space="preserve">src/locks/java</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(attributesOnTags),
                "an attribute on <modules> — combine.children is a Maven feature — made every "
                        + "module in it disappear");
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(attributesOnTags),
                "an attribute on <testSourceDirectory> dropped the declaration");

        // DEDUPE: sixty-five profiles giving the same property the same value is a valid pom, and
        // without the dedupe the candidate list crosses 64 and the reader refuses it (round-25
        // review, subagent P3-3 — the guard was there, nothing measured it).
        // ONE <profiles> container with sixty-five <profile> children — the shape Maven accepts.
        // Sixty-five containers is not a valid pom, so a fixture built that way measured the
        // reader's own looseness instead of a valid build (round-26 review, Codex P2).
        StringBuilder sameValue = new StringBuilder("<project>\n  <profiles>\n");
        for (int i = 0; i < 65; i++) {
            sameValue.append("    <profile><id>p").append(i).append("</id><properties>")
                    .append("<tests.root>src/locks/java</tests.root>")
                    .append("</properties></profile>\n");
        }
        sameValue.append("  </profiles>\n")
                .append("  <build><testSourceDirectory>${tests.root}</testSourceDirectory></build>\n")
                .append("</project>\n");
        assertEquals(List.of("src/locks/java", "src/test/java"),
                candidatesOrRefusal(sameValue.toString()),
                "sixty-five profiles declaring the SAME value were refused as an explosion; the "
                        + "candidates have to be deduped before the cap is read");

        // And the combinatorial guard, which had no fixture either: five declarations of one
        // property, each referring to the property again, multiplies past 64 on the third pass.
        String explodes = """
                <project>
                  <properties>
                    <tests.root>${tests.root}/a</tests.root>
                    <tests.root>${tests.root}/b</tests.root>
                    <tests.root>${tests.root}/c</tests.root>
                    <tests.root>${tests.root}/d</tests.root>
                    <tests.root>${tests.root}/e</tests.root>
                  </properties>
                  <build><testSourceDirectory>${tests.root}</testSourceDirectory></build>
                </project>
                """;
        List<String> answer = candidatesOrRefusal(explodes);
        assertEquals(1, answer.size(), "the explosion was not refused: " + answer);
        assertTrue(answer.get(0).contains("more than 64"),
                "the refusal does not say what it refused: " + answer);

        String unbalancedCdata = """
                <project>
                  <description><![CDATA[<modules><module>ghost</module></modules></description>
                  <modules><module>real</module></modules>
                </project>
                """;
        HarnessBroken unbalanced = assertThrows(HarnessBroken.class,
                () -> modulesIn(unbalancedCdata),
                "a CDATA section with no closer was answered from rather than refused: its text "
                        + "reads as structure, so a module that exists only inside the comment-like "
                        + "run was reported (measured round 26)");
        assertTrue(unbalanced.getMessage().contains("unbalanced CDATA"),
                "the refusal does not say what it could not read: " + unbalanced.getMessage());

        String cdata = """
                <project>
                  <modules><module><![CDATA[guard-module]]></module></modules>
                </project>
                """;
        assertEquals(List.of("guard-module"), modulesIn(cdata),
                "a module written as CDATA — ordinary character content to Maven — was dropped in "
                        + "silence, which is the fail-open shape this reader exists to avoid");

        String pluginConfiguration = """
                <project>
                  <modules><module>real</module></modules>
                  <build><plugins><plugin><configuration>
                    <module>jpms.name.not.a.maven.module</module>
                  </configuration></plugin></plugins></build>
                </project>
                """;
        assertEquals(List.of("real"), modulesIn(pluginConfiguration),
                "a <module> in a plugin's own configuration was taken for a reactor module, and "
                        + "this reader then demanded a directory Maven never looks for");

        assertEquals(List.of("src/test/java"),
                candidatesOrRefusal("<project/>"),
                "the default is not a candidate, which is where every module of this build keeps "
                        + "its tests");
    }

    /**
     * What the pom reader answers for this pom — a list of candidates, or the refusal as a value.
     *
     * <p>The refusal is part of the contract these arms measure (an unresolvable property must not
     * be passed over), so it is compared rather than thrown: a thrown {@code HarnessBroken} lands
     * in the failure stanza as a cause, and the control runner excludes anything whose stanza names
     * that type before it looks for an assertion.
     */
    private static List<String> candidatesOrRefusal(String pom) {
        try {
            return testRootCandidates(pom, "synthetic");
        } catch (HarnessBroken broken) {
            // ONLY the refusal this arm measures. Converting every HarnessBroken would make a
            // genuine breakage — a pom that cannot be read, once this reader starts reading parent
            // poms — come out as an assertion failure, which the runner scores as a firing: the
            // very confusion this class exists to prevent (round-23 review, subagent P2-1).
            // The type is the test, not the wording: matching the message's words meant that
            // rewording the refusal would have rethrown it (round-24).
            if (!(broken instanceof RefusalHarnessBroken)) {
                throw broken;
            }
            return List.of("refused: " + broken.getMessage());
        }
    }

    /**
     * The reader refusing to name a test-source directory — its own answer, not a breakage of the
     * harness.
     *
     * <p>A type rather than a phrase: {@code candidatesOrRefusal} matched the refusal by the words
     * in its message, so rewording the refusal would have made the helper rethrow and the lock die
     * of breakage instead of asserting (round-24, found while reading my own change).
     */
    static final class RefusalHarnessBroken extends HarnessBroken {
        // The NAME matters: the runner decides "the harness broke" with
        // head.endsWith("HarnessBroken") on each line of the failure stanza, so a subtype called
        // anything else walks straight past that exclusion — and a bare call wrapped in
        // assertDoesNotThrow would then be scored FIRED (round-25 review, subagent P2-4).
        private static final long serialVersionUID = 1L;

        RefusalHarnessBroken(String message) {
            super(message);
        }
    }

    /** Read it, or say the sweep could not look — not that it looked and found nothing. */
    private static String readOrBreak(Path file) {
        try {
            return JavaSource.withoutLiteralText(Files.readString(file));
        } catch (Exception e) {
            throw new HarnessBroken("could not read " + file + " while sweeping for locks that "
                    + "build a type the runner scores", e);
        }
    }

    /**
     * The type names the control runner scores as a firing, read from the runner itself.
     *
     * <p>Its judgement is a substring match on the failure stanza, so what matters is the simple
     * name. The markers that are prose ("Wanted but not invoked") are not types and are not swept:
     * a lock cannot build those by name, only print them.
     */
    private static List<String> scoredTypeNames() throws Exception {
        Path runner = Path.of("../tools/negative-controls/run_negative_controls.py");
        // ONE look, read twice: asking Files.exists and then Files.notExists can straddle a
        // change on disk and print the opposite reason to the one that applies. Both arms break
        // the harness, so only the WORDING could be wrong — and a wrong reason is what this tree
        // refuses to print (round-27 review, subagent P3-8).
        Presence runnerIs = classify(runner);
        if (runnerIs != Presence.NOT_A_DIRECTORY && runnerIs != Presence.DIRECTORY) {
            throw new HarnessBroken((runnerIs == Presence.ABSENT ? "the control runner is not at "
                    : "could not tell whether the control runner is at ") + runner.toAbsolutePath()
                    + ", so this sweep cannot know which type names it scores");
        }
        String code = readTextOrBreak(runner);
        int at = code.indexOf("return any(marker in failure_text for marker in (");
        if (at < 0) {
            throw new HarnessBroken("the runner no longer has the marker tuple this sweep reads "
                    + "(failure_is_assertion), so the two cannot be kept in lockstep");
        }
        int closes = code.indexOf("))", at);
        if (closes < 0) {
            throw new HarnessBroken("the runner's marker tuple does not close, so this sweep "
                    + "cannot read which type names it scores");
        }
        String tuple = code.substring(at, closes);
        List<String> names = new ArrayList<>();
        // BOTH quote styles and a qualified name reduced to its simple name. The runner's own
        // readers use ["'], and counting control ids with one of the two is how THIS tree's
        // documents got two control counts wrong (that was a person counting, not this function —
        // round-19 review, subagent P3-6). Here it is defence against a marker added the other
        // way (round-18 review, Codex P1).
        Matcher marker = Pattern.compile("[\"']((?:[A-Za-z_][\\w]*\\.)*)([A-Z][A-Za-z0-9]+)[\"']")
                .matcher(tuple);
        while (marker.find()) {
            names.add(marker.group(2));
        }
        if (names.size() < 5 || !names.contains("AssertionError")
                || !names.contains("AssertionFailedError")) {
            // BOTH. The two names are the tuple's FIRST line, so every cut below line one leaves
            // them in place and a name check alone passes a truncated list — weaker than the size
            // floor it replaced (round-20 review, subagent P2-6, measured).
            // Those two are what every assertion in this tree lands as, so a tuple read without
            // them was not read; and the size is what catches a span cut after them (the tuple's
            // first line is exactly those two). Round 19 replaced the size with the names, which
            // was weaker; round 20 asked for both (round-19 subagent P3-7, round-20 subagent P2-6).
            List<String> missing = new ArrayList<>();
            if (!names.contains("AssertionError")) {
                missing.add("AssertionError");
            }
            if (!names.contains("AssertionFailedError")) {
                missing.add("AssertionFailedError");
            }
            throw new HarnessBroken("the runner's marker tuple yielded " + names + " ("
                    + names.size() + " names)" + (missing.isEmpty()
                            ? ", which is fewer than this tuple has — the span was cut"
                            : ", which does not include " + missing)
                    + ", so it was not read");
        }
        return names;
    }

    /** The forms that put one of those names into a failure stanza by the test's own hand. */
    private static List<Pattern> scoredForms() throws Exception {
        String names = String.join("|", scoredTypeNames());
        String qualifier = "(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*";
        return List.of(
                // BUILT, wherever it is built: `orElseThrow(() -> new AssertionError(…))` throws
                // one without the word `throw` next to it, and five locks were written that way.
                Pattern.compile("new\\s+(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s+)*" + qualifier
                        + "(?:" + names + ")\\s*\\("),
                // the constructor as a METHOD REFERENCE — `orElseThrow(AssertionError::new)` also
                // builds one (round-18 review, subagent P3-2).
                // No left boundary: the runner matches the NAME as a substring, so a type
                // whose own name ends with one of these (MySpecialAssertionError) scores
                // just the same. A boundary was added for one round on the reading that
                // such a name is an over-refusal — IH5 fired nothing and taught the
                // opposite (round-19 review, subagent P3-1, taken too far by me).
                Pattern.compile(qualifier + "(?:" + names + ")[\\w$]*\\s*::\\s*new"),
                // held in a variable or field, which is the route to `throw it;` — but NOT the
                // meta-test's `AssertionError thrown = assertThrows(…)`, which asserts rather than
                // builds. The exclusion takes a QUALIFIED call too: this tree writes
                // `org.junit.jupiter.api.Assertions.assertThrows(…)` in places, and the first cut
                // of the exclusion only knew the bare name (round-18 review: Codex P1, subagent
                // P3-1).
                Pattern.compile("(?:^|[\\s(,{};])" + qualifier + "(?:" + names
                        + ")\\s+[A-Za-z_$][\\w$]*\\s*(?:;|=(?!\\s*" + qualifier
                        + "(?:<[^>]*>\\s*)?assertThrows))"),
                // the `assert` STATEMENT: the JVM builds a java.lang.AssertionError and surefire
                // enables assertions by default, so this is a firing with no `new` in sight
                // (round-17 review, subagent P2-2; eight of them were in ConnectionTestGroup).
                // Not anchored to the line start, and braces inside the expression are allowed —
                // `if (x) assert y;` and `assert m.equals(Map.of("a", new int[]{1}));` are both
                // assert statements (round-18 review: Codex P1, subagent P3-2).
                Pattern.compile("(?:^|[\\s)}{;:])assert(?![\\w$])[^;]*;"));
    }

    /**
     * The test roots of every module this build has, read from the build itself.
     *
     * <p>Not a hand-written list: the first cut of this sweep hard-coded one directory and so never
     * read the two verifier modules, where 224 controls run their tests.
     *
     * <p>It takes the UNION of everything a pom names — modules declared in a profile included,
     * and both a declared {@code <testSourceDirectory>} and the default {@code src/test/java} — and
     * adds every candidate that exists. For a sweep, reading a directory the active build would
     * skip costs nothing, while missing one is the whole defect; the round-18 cut filtered profiles
     * out and so could lose a module's tests in silence, and the regex that did the filtering ate
     * real {@code <module>} lines when a COMMENT mentioned profiles (round-19 review: Codex P1,
     * subagent P2-7).
     */
    private static List<Path> testRoots() throws Exception {
        List<Path> roots = new ArrayList<>();
        // The visited set travels with the walk. It was static and cleared on entry, which two
        // tests running in parallel would have shared — the loser walking fewer modules and the
        // sweep clearing them in silence (round-21 review, subagent P3).
        java.util.Set<Path> walked = new java.util.HashSet<>();
        walked.add(realPathOrBreak(Path.of("..")));
        collectTestRoots(Path.of(".."), "the reactor", roots, walked);
        if (roots.size() < 2) {
            throw new HarnessBroken("the reactor pom yielded " + roots.size() + " test roots "
                    + roots + "; this build has more than that, so the pom was not read");
        }
        return roots;
    }

    private static void collectTestRoots(Path module, String named, List<Path> into,
            java.util.Set<Path> walked) throws Exception {
        Path pom = module.resolve("pom.xml");
        // ONE look here too (round-27 review, subagent P3-8).
        Presence pomIs = classify(pom);
        if (pomIs != Presence.NOT_A_DIRECTORY && pomIs != Presence.DIRECTORY) {
            throw new HarnessBroken((pomIs == Presence.ABSENT ? "there is no pom at "
                    : "could not tell whether there is a pom at ") + pom.toAbsolutePath()
                    + ", so this sweep cannot know which modules exist");
        }
        String text = readTextOrBreak(pom);

        // This module's own tests, whichever directory it keeps them in.
        boolean took = false;
        for (String candidate : testRootCandidates(text, named)) {
            Path tests = module.resolve(candidate);
            switch (classify(tests)) {
                case DIRECTORY -> {
                    into.add(tests);
                    took = true;
                }
                case ABSENT, NOT_A_DIRECTORY -> {
                    // Both are answers: nothing is there, or something that is not a directory is.
                }
                case CANNOT_TELL -> throw new HarnessBroken("could not tell what " + tests
                        + " is, so this sweep will not record it as absent");
            }
        }
        if (!took && containsJavaSources(module.resolve("src/test"))) {
            throw new HarnessBroken("module " + named + " keeps .java files under "
                    + module.resolve("src/test") + " and none of the directories this sweep "
                    + "knows about (" + testRootCandidates(text, named) + ") exist — its tests "
                    + "would be silently skipped");
        }

        for (String child : modulesIn(text)) {
            Path childPath = module.resolve(child);
            Presence childIs = classify(childPath);
            if (childIs != Presence.DIRECTORY) {
                if (childIs == Presence.CANNOT_TELL) {
                    // BEFORE the skip: a profile-only module whose directory cannot be read was
                    // skipped in silence, so none of its locks were ever swept (round-24 review,
                    // subagent P2-4c).
                    throw new HarnessBroken("could not tell what the module directory " + childPath
                            + " is, so this sweep will not skip it as absent");
                }
                if (!requiredModulesIn(text).contains(child)) {
                    // Named only by a profile. A profile that is off in this checkout may well
                    // have no directory, and refusing the whole reactor for it is an
                    // over-refusal of a valid build (round-23 review, Codex P2).
                    continue;
                }
                throw new HarnessBroken("the pom of " + named + " names module " + child
                        + " in its own <modules> and there is no such directory, so this sweep "
                        + "cannot say it read it");
            }
            // `<module>.</module>` is legal text and would recurse for ever, and the same module
            // named both in <modules> and in a profile would walk the tree twice (round-20
            // review, subagent P3). Canonical paths, visited once.
            if (!walked.add(realPathOrBreak(childPath))) {
                continue;
            }
            collectTestRoots(childPath, child, into, walked);
        }
    }

    /**
     * The modules a pom names in its own {@code <modules>} — the ones that must exist.
     *
     * <p>Profiles are left out here on purpose: a profile that is off in this checkout may name a
     * module with no directory, and refusing the whole reactor for that is an over-refusal
     * (round-23 review, Codex P2). {@link #modulesIn} is the other half and takes everything.
     */
    static List<String> requiredModulesIn(String pom) {
        String text = structureOnly(pom).replaceAll("(?s)<profiles(?:\\s[^>]*)?>.*?</profiles>", "");
        List<String> modules = new ArrayList<>();
        Matcher block = Pattern.compile("(?s)<modules(?:\\s[^>]*)?>(.*?)</modules>").matcher(text);
        while (block.find()) {
            Matcher m = Pattern.compile(elementPattern("module")).matcher(block.group(1));
            while (m.find()) {
                modules.add(m.group(1).trim());
            }
        }
        return modules;
    }

    /**
     * Every {@code <module>} a pom names, profiles included.
     *
     * <p>Comments go first: {@code <!-- the <modules> below … -->} would otherwise contribute a
     * module that does not exist and this sweep would refuse the whole build. This javadoc sat
     * above {@link #requiredModulesIn} for one round — the method was inserted before this
     * declaration, which is the fifth time this tree has made that edit (round-24 review,
     * subagent P2-1). The detector that catches it reads the verifier modules' test sources but
     * not {@code core}'s, which is why it stayed green; {@code core/src/test/java} holds nine
     * orphans of its own in seven files (round-25 review, subagent P3-2, measured twice).
     */
    static List<String> modulesIn(String pom) {
        // INSIDE <modules> only. A plugin's own free-form configuration may carry a <module>
        // element of its own (a JPMS name, for one), and taking those made this reader demand a
        // directory Maven never looks for — an over-refusal of a valid pom, and the synthetic
        // fixture shared the misreading (round-20 review, Codex P2).
        List<String> modules = new ArrayList<>();
        Matcher block = Pattern.compile("(?s)<modules(?:\\s[^>]*)?>(.*?)</modules>").matcher(structureOnly(pom));
        while (block.find()) {
            Matcher m = Pattern.compile(elementPattern("module")).matcher(block.group(1));
            while (m.find()) {
                modules.add(m.group(1).trim());
            }
        }
        return modules;
    }

    /**
     * The directories a pom's tests could be in: every {@code <testSourceDirectory>} it declares
     * (profiles included) plus Maven's default.
     *
     * <p>A property reference is resolved against the pom's own {@code <properties>}, every
     * reference and every value each is given. One that STILL does not resolve — a parent pom's
     * property, most likely — makes this method REFUSE: the sweep cannot name the directory, so it
     * must not go on to say it read that module. Round 19 let it fall through to the default, which
     * traded an over-refusal for a fail-open; round 20 restored the refusal and left this paragraph
     * describing the fall-through (round-21 review, subagent P2-1).
     */
    static List<String> testRootCandidates(String pom, String named) {
        String text = structureOnly(pom);
        List<String> candidates = new ArrayList<>();
        // Only where Maven looks for it: inside <build>, and not inside a plugin's own
        // <configuration> (a plugin may take an element of the same name as its own input, and
        // reading those made this sweep walk a directory the build never compiles — round-23
        // review, Codex P2).
        // Plugins out FIRST, then the build blocks. Taking <build> first and stripping plugins
        // inside it loses the real directory when a plugin's own configuration carries a POM
        // fragment of its own: the non-greedy <build>…</build> ends at the INNER closing tag
        // (round-24 review, subagent P3-3, measured).
        String withoutPlugins = text.replaceAll("(?s)<plugins(?:\\s[^>]*)?>.*?</plugins>", "");
        StringBuilder build = new StringBuilder();
        Matcher builds = Pattern.compile("(?s)<build(?:\\s[^>]*)?>(.*?)</build>").matcher(withoutPlugins);
        while (builds.find()) {
            build.append(builds.group(1));
        }
        Matcher declared = Pattern.compile(elementPattern("testSourceDirectory"))
                .matcher(build);
        while (declared.find()) {
            // EVERY value a property is given, not the first: two profiles can set the same
            // property to different directories, and taking the first silently dropped the other
            // one's tests (round-20 review, Codex P2).
            for (String resolved : resolveProperties(declared.group(1).trim(), text)) {
                if (resolved.contains("${")) {
                    // A property this pom does not declare — a parent's, most likely. Refusing is
                    // the point: the sweep must not go on to say it read this module when a
                    // directory it cannot name may hold the tests (round-20 review, subagent P2-4;
                    // dropping it in favour of the default was trading an over-refusal for a
                    // fail-open).
                    throw new RefusalHarnessBroken("module " + named + " declares its test sources as 「"
                            + declared.group(1).trim() + "」 and this sweep could not resolve 「"
                            + resolved + "」 from this pom's own properties, so it cannot say which "
                            + "directory it read");
                }
                candidates.add(resolved);
            }
        }
        candidates.add("src/test/java");
        return candidates;
    }

    /**
     * {@code ${project.basedir}} and friends away, and one pass of the pom's own properties —
     * every value each property is given, since profiles can give it several.
     *
     * <p>The property is looked up inside {@code <properties>} blocks only. Searching the whole
     * pom resolved {@code ${name}} from the project's {@code <name>} element (round-20 review,
     * subagent P3).
     */
    private static List<String> resolveProperties(String raw, String pom) {
        StringBuilder properties = new StringBuilder();
        Matcher block = Pattern.compile("(?s)<properties(?:\\s[^>]*)?>(.*?)</properties>").matcher(pom);
        while (block.find()) {
            properties.append(block.group(1));
        }

        // EVERY reference, each of them possibly declared more than once (profiles). Round 20
        // resolved the FIRST reference only and then refused anything still holding a `${`, so
        // `${src.root}/${flavour}/java` — fully declared in the same pom — was refused with a
        // message saying the pom does not declare the property (round-21 review, subagent P2-2,
        // measured against the round-19 behaviour).
        List<String> values = new ArrayList<>(List.of(stripBasedir(raw)));
        for (int pass = 0; pass < 10; pass++) {
            List<String> next = new ArrayList<>();
            boolean moved = false;
            for (String value : values) {
                Matcher property = Pattern.compile("\\$\\{([\\w.\\-]+)\\}").matcher(value);
                if (!property.find()) {
                    next.add(value);
                    continue;
                }
                // Attributes tolerated HERE too. Round 26 widened the blocks and the element
                // reader and left this one declaration pattern strict, so a property declared as
                // `<tests.root xml:space="preserve">` read as undeclared and the module's test
                // sources were refused — an over-refusal of a valid pom (round-27 review,
                // subagent P3-2, measured).
                Matcher declared = Pattern.compile("<" + Pattern.quote(property.group(1))
                        + "(?:\\s[^>]*)?>([^<]*)</" + Pattern.quote(property.group(1)) + ">")
                        .matcher(properties);
                boolean any = false;
                while (declared.find()) {
                    next.add(stripBasedir(value.replace(property.group(), declared.group(1).trim())));
                    any = true;
                    moved = true;
                }
                if (!any) {
                    next.add(value);        // nobody declares it; the caller decides what that means
                }
            }
            next = new ArrayList<>(new java.util.LinkedHashSet<>(next));
            if (next.size() > 64) {
                // Each pass multiplies by the number of declarations, so five profiles declaring
                // one property with a self-reference reaches nine million values before the pass
                // limit — it would hang or run out of memory rather than refuse (round-23 review,
                // subagent P3-4, measured). A sweep that cannot enumerate the candidates says so.
                throw new RefusalHarnessBroken("resolving 「" + raw + "」 against this pom's properties "
                        + "produced more than 64 candidate directories, so this sweep cannot say "
                        + "which one it read");
            }
            values = next;
            if (!moved) {
                break;
            }
        }
        return values;
    }

    private static String stripBasedir(String value) {
        return value.replace("${project.basedir}/", "").replace("${basedir}/", "")
                .replace("${pom.basedir}/", "").replace("${project.basedir}", "")
                .replace("${basedir}", "").replace("${pom.basedir}", "");
    }

    /** What a path is, with "I could not tell" kept apart from "it is not there". */
    enum Presence { DIRECTORY, NOT_A_DIRECTORY, ABSENT, CANNOT_TELL }

    /**
     * Which of the four a path is.
     *
     * <p>{@code isDirectory} answers false for three different things. The round-23 cut split them
     * with {@code notExists}, which refused a path that exists and is a FILE as "could not tell"
     * though it is perfectly knowable (round-24 review, subagent P2-4b, measured against the
     * syscalls). Reading the attributes answers all four.
     *
     * <p>A BROKEN SYMLINK is {@code ABSENT} here, deliberately: nothing is reachable through it,
     * so there is nothing this sweep could have read and nothing it is failing to look at. What
     * was wrong in round 23 was the COMMENT that claimed {@code notExists} tells that case apart
     * from absence — it does not (round-25 review, Codex P3: the two sentences contradicted
     * each other).
     */
    static Presence classify(Path path) {
        try {
            if (Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class)
                    .isDirectory()) {
                return Presence.DIRECTORY;
            }
            return Presence.NOT_A_DIRECTORY;
        } catch (java.nio.file.NoSuchFileException absent) {
            // A broken symlink lands here too; see the javadoc — one contract, not two.
            return Presence.ABSENT;
        } catch (java.io.IOException cannotTell) {
            return Presence.CANNOT_TELL;
        }
    }

    /** The file's text, or "this sweep could not look" — a pom is not Java, so it does not go
     * through the literal-stripping reader. */
    private static String readTextOrBreak(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new HarnessBroken("could not read " + file + " while walking the reactor", e);
        }
    }

    /** The walk, or "this sweep could not look" — not an IOException the runner reads as a
     * non-firing (round-26 review, subagent P3-7). */
    private static List<Path> javaSourcesOrBreak(Path root) {
        // The LIST, not the stream: wrapping only `Files.walk(root)` covered opening the root and
        // left the walk itself uncovered — a directory that becomes unreadable while the tree is
        // being walked throws UncheckedIOException out of the terminal operation, which is not
        // this sweep saying "I could not look" (round-27 review, Codex P3). The terminal operation
        // runs inside the try now, so both failures speak with the same voice.
        try (var walk = Files.walk(root)) {
            return walk.filter(f -> f.toString().endsWith(".java")).toList();
        } catch (Exception e) {
            throw new HarnessBroken("could not walk " + root, e);
        }
    }

    /** The real path, or "this sweep could not look" — not an IOException the runner reads as a
     * non-firing (round-21 review, subagent P3). */
    private static Path realPathOrBreak(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            throw new HarnessBroken("could not resolve " + path + " to a real path while walking "
                    + "the reactor", e);
        }
    }

    private static boolean containsJavaSources(Path directory) throws Exception {
        Presence is = classify(directory);
        if (is != Presence.DIRECTORY) {
            if (is == Presence.CANNOT_TELL) {
                // The same two meanings as the candidate check above: this one answered "no .java
                // here" for "I could not look", and the refusal that depends on it then never
                // fired (round-24 review, Codex P1 — the claim "the two were separated" was
                // true of one call site only).
                throw new HarnessBroken("could not tell what " + directory + " is, so this "
                        + "sweep will not report it as holding no sources");
            }
            return false;
        }
        return !javaSourcesOrBreak(directory).isEmpty();
    }

    /**
     * The pom with everything that is NOT structure taken out, in ONE left-to-right pass.
     *
     * <p>Comments, processing instructions and the DOCTYPE declaration go; a CDATA section becomes
     * its own text with {@code <} and {@code >} escaped, so it can be read as a value and never as
     * markup. Something that opens and never closes is refused.
     *
     * <p>One pass because both orders were measured wrong. Stripping comments first let a
     * {@code <!--} INSIDE a CDATA section swallow the real markup that followed it, up to the next
     * real {@code -->} (round-26 review, subagent P1-2); stripping CDATA first lets a {@code ]]>}
     * inside a comment do the same. A left-to-right scan takes whichever opens first, which is
     * what an XML reader does.
     *
     * <p>Known limit, recorded rather than papered over: the CDATA text is ESCAPED, so an element
     * whose value genuinely contains {@code <} reads as {@code &lt;} here, and entities in ordinary
     * text are not decoded either. Structure safety is what this sweep needs; a module directory
     * named with an angle bracket is not a case this tree has (round-26 review, Codex P1).
     */
    private static String structureOnly(String pom) {
        StringBuilder out = new StringBuilder(pom.length());
        int i = 0;
        while (i < pom.length()) {
            if (pom.startsWith("<!--", i)) {
                int close = pom.indexOf("-->", i + 4);
                if (close < 0) {
                    throw new HarnessBroken("this pom has a comment that never closes, so this "
                            + "sweep cannot tell its text from its structure");
                }
                i = close + 3;
            } else if (pom.startsWith("<![CDATA[", i)) {
                int close = pom.indexOf("]]>", i + 9);
                if (close < 0) {
                    throw new HarnessBroken("this pom has an unbalanced CDATA section, so this "
                            + "sweep cannot tell its text from its structure");
                }
                out.append(pom, i + 9, close);
                int from = out.length() - (close - (i + 9));
                String text = out.substring(from).replace("<", "&lt;").replace(">", "&gt;");
                out.setLength(from);
                out.append(text);
                i = close + 3;
            } else if (pom.startsWith("<?", i)) {
                int close = pom.indexOf("?>", i + 2);
                if (close < 0) {
                    throw new HarnessBroken("this pom has a processing instruction that never "
                            + "closes, so this sweep cannot tell its text from its structure");
                }
                i = close + 2;
            } else if (pom.startsWith("<!DOCTYPE", i)) {
                // QUOTE-AWARE, and the internal subset tracked rather than ended at the first `]`.
                // Taking `indexOf(']')` ended the subset inside an entity's VALUE —
                // `<!ENTITY bracket "]">` — and everything after it, including another entity's
                // `<modules>`, came out as structure: measured [ghost, real] by both reviewers in
                // round 27 (Codex P1, subagent P2-1). `>` and `]` inside a quoted literal are
                // legal XML, so neither can be read without following the quotes.
                int at = i + "<!DOCTYPE".length();
                char quote = 0;
                boolean inSubset = false;
                while (at < pom.length()) {
                    char c = pom.charAt(at);
                    if (quote != 0) {
                        if (c == quote) {
                            quote = 0;
                        }
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '[') {
                        inSubset = true;
                    } else if (c == ']') {
                        inSubset = false;
                    } else if (c == '>' && !inSubset) {
                        break;
                    }
                    at++;
                }
                if (at >= pom.length()) {
                    throw new HarnessBroken("this pom has a DOCTYPE declaration that never closes, "
                            + "so this sweep cannot tell its text from its structure");
                }
                i = at + 1;
            } else {
                out.append(pom.charAt(i++));
            }
        }
        return out.toString();
    }

    /**
     * The element's text, whether it is written plainly or wrapped in CDATA.
     *
     * <p>CDATA is ordinary character content to Maven, and `[^<]+` stops at the `<` of the opener,
     * so `<module><![CDATA[guard]]></module>` read as an absent element (round-23 review, Codex
     * P1). Round 23 answered that by stripping the CDATA markers from the whole pom, which
     * PROMOTED the content to structure — `<description><![CDATA[<modules><module>ghost</module>
     * </modules>]]></description>` then read as a real module and a valid pom was refused
     * (round-24 review, Codex P1). Round 24 put the tolerance in these element patterns, one
     * element at a time; round 25 took it back out, because the promotion was the BLOCK patterns
     * reading CDATA text as structure and an element-by-element tolerance left that untouched.
     * {@link #structureOnly} now neutralises CDATA for every reader, so this pattern reads plain
     * text and nothing here knows about CDATA. The sentence that used to end this javadoc still
     * described round 24's design, which is this tree's "the document is stronger than the code"
     * defect in a javadoc (round-26 review, subagent P3-1).
     */
    private static String elementPattern(String name) {
        // Plain text: structureOnly has already unwrapped any CDATA into escaped text, so a
        // value containing `]` reads fine — the round-24 pattern forbade `]` and dropped
        // `<module><![CDATA[child]name]]></module>` in silence (round-25 review, Codex P1).
        return "<" + name + "(?:\\s[^>]*)?>\\s*([^<]+?)\\s*</" + name + ">";
    }
}
