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
 * <p>Two limits a lock's author has to know. (1) The modules outside {@code core} have no
 * {@code HarnessBroken} on their test path, so a lock there can only avoid producing an
 * assertion type — its breakage reads to the runner as "did not fire" rather than being
 * excluded by name. (2) Saying breakage through an ASSERTION METHOD
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
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = readOrBreak(file);
                    inThisRoot++;
                    for (Pattern form : forms) {
                        Matcher hit = form.matcher(code);
                        while (hit.find()) {
                            offenders.add(file + " 「" + hit.group().replace('\n', ' ').trim() + "」");
                        }
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
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
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
                "        assert found > 0 : \"the view answered nothing\";",
                "        if (x) assert found > 0 : \"the view answered nothing\";",
                "        assert m.equals(Map.of(\"a\", new int[]{1})) : \"the shape moved\";",
                "        .orElseThrow(AssertionError::new);",
                "        .orElseThrow(MySpecialAssertionError::new);",
                "        assert(found > 0) : \"the view answered nothing\";",
                "        assert!flag : \"the view answered nothing\";",
                "        throw new @Nullable AssertionError(\"could not read\");")) {
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
                testRootCandidates(property, "synthetic"),
                "a property reference the pom itself declares is not resolved, and the round-18 "
                        + "form refused the whole build for it");

        String unresolvable = """
                <project>
                  <build><testSourceDirectory>${from.the.parent}</testSourceDirectory></build>
                </project>
                """;
        assertEquals(List.of("src/test/java"), testRootCandidates(unresolvable, "synthetic"),
                "an unresolvable property drops the default too, so a module keeping its tests in "
                        + "the usual place would not be read");

        assertEquals(List.of("src/test/java"), testRootCandidates("<project/>", "synthetic"),
                "the default is not a candidate, which is where every module of this build keeps "
                        + "its tests");
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
        if (!Files.exists(runner)) {
            throw new HarnessBroken("the control runner is not at " + runner.toAbsolutePath()
                    + ", so this sweep cannot know which type names it scores");
        }
        String code = Files.readString(runner);
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
        if (!names.contains("AssertionError") || !names.contains("AssertionFailedError")) {
            // Not a size: those two are what every assertion in this tree lands as, so a tuple
            // read without them was not read. A size floor passed a truncated list (a prose
            // marker containing "))" cuts the span, and the self-test below reads the same cut
            // list, so the two agree with each other and nobody notices — round-19 review,
            // subagent P3-7).
            throw new HarnessBroken("the runner's marker tuple yielded " + names
                    + ", which does not include AssertionError and AssertionFailedError, so it "
                    + "was not read");
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
                Pattern.compile("new\\s+(?:@[\\w.]+\\s+)*" + qualifier + "(?:" + names
                        + ")\\s*\\("),
                // the constructor as a METHOD REFERENCE — `orElseThrow(AssertionError::new)` also
                // builds one (round-18 review, subagent P3-2).
                // No left boundary: the runner matches the NAME as a substring, so a type
                // whose own name ends with one of these (MySpecialAssertionError) scores
                // just the same. A boundary was added for one round on the reading that
                // such a name is an over-refusal — IH5 fired nothing and taught the
                // opposite (round-19 review, subagent P3-1, taken too far by me).
                Pattern.compile(qualifier + "(?:" + names + ")\\s*::\\s*new"),
                // held in a variable or field, which is the route to `throw it;` — but NOT the
                // meta-test's `AssertionError thrown = assertThrows(…)`, which asserts rather than
                // builds. The exclusion takes a QUALIFIED call too: this tree writes
                // `org.junit.jupiter.api.Assertions.assertThrows(…)` in places, and the first cut
                // of the exclusion only knew the bare name (round-18 review: Codex P1, subagent
                // P3-1).
                Pattern.compile("(?:^|[\\s(,])" + qualifier + "(?:" + names
                        + ")\\s+[A-Za-z_$][\\w$]*\\s*(?:;|=(?!\\s*" + qualifier
                        + "(?:<[^>]*>\\s*)?assertThrows))"),
                // the `assert` STATEMENT: the JVM builds a java.lang.AssertionError and surefire
                // enables assertions by default, so this is a firing with no `new` in sight
                // (round-17 review, subagent P2-2; eight of them were in ConnectionTestGroup).
                // Not anchored to the line start, and braces inside the expression are allowed —
                // `if (x) assert y;` and `assert m.equals(Map.of("a", new int[]{1}));` are both
                // assert statements (round-18 review: Codex P1, subagent P3-2).
                Pattern.compile("(?:^|[\\s)}{;])assert(?![\\w$])[^;]*;"));
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
        collectTestRoots(Path.of(".."), "the reactor", roots);
        if (roots.size() < 2) {
            throw new HarnessBroken("the reactor pom yielded " + roots.size() + " test roots "
                    + roots + "; this build has more than that, so the pom was not read");
        }
        return roots;
    }

    private static void collectTestRoots(Path module, String named, List<Path> into)
            throws Exception {
        Path pom = module.resolve("pom.xml");
        if (!Files.exists(pom)) {
            throw new HarnessBroken("there is no pom at " + pom.toAbsolutePath()
                    + ", so this sweep cannot know which modules exist");
        }
        String text = Files.readString(pom);

        // This module's own tests, whichever directory it keeps them in.
        boolean took = false;
        for (String candidate : testRootCandidates(text, named)) {
            Path tests = module.resolve(candidate);
            if (Files.isDirectory(tests)) {
                into.add(tests);
                took = true;
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
            if (!Files.isDirectory(childPath)) {
                throw new HarnessBroken("the pom of " + named + " names module " + child
                        + " and there is no such directory, so this sweep cannot say it read it");
            }
            collectTestRoots(childPath, child, into);
        }
    }

    /**
     * Every {@code <module>} a pom names, profiles included.
     *
     * <p>Comments go first: {@code <!-- the <modules> below … -->} would otherwise contribute a
     * module that does not exist and this sweep would refuse the whole build.
     */
    static List<String> modulesIn(String pom) {
        List<String> modules = new ArrayList<>();
        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(withoutXmlComments(pom));
        while (m.find()) {
            modules.add(m.group(1).trim());
        }
        return modules;
    }

    /**
     * The directories a pom's tests could be in: every {@code <testSourceDirectory>} it declares
     * (profiles included) plus Maven's default.
     *
     * <p>A property reference is resolved against the pom's own {@code <properties>} — one pass,
     * which is what a build file of this shape uses. One that still does not resolve is NOT a
     * reason to refuse the build: the default is tried as well, and the refusal above only fires
     * when .java files are sitting somewhere this sweep did not look (round-19 review, Codex P2).
     */
    static List<String> testRootCandidates(String pom, String named) {
        String text = withoutXmlComments(pom);
        List<String> candidates = new ArrayList<>();
        Matcher declared = Pattern.compile("<testSourceDirectory>([^<]+)</testSourceDirectory>")
                .matcher(text);
        while (declared.find()) {
            String resolved = resolveProperties(declared.group(1).trim(), text);
            if (!resolved.contains("${")) {
                candidates.add(resolved);
            }
        }
        candidates.add("src/test/java");
        return candidates;
    }

    /** {@code ${project.basedir}} and friends away, and one pass of the pom's own properties. */
    private static String resolveProperties(String raw, String pom) {
        String value = raw.replace("${project.basedir}/", "").replace("${basedir}/", "")
                .replace("${pom.basedir}/", "").replace("${project.basedir}", "")
                .replace("${basedir}", "");
        Matcher property = Pattern.compile("\\$\\{([\\w.\\-]+)\\}").matcher(value);
        while (property.find()) {
            Matcher declared = Pattern.compile("<" + Pattern.quote(property.group(1)) + ">"
                    + "([^<]*)</" + Pattern.quote(property.group(1)) + ">").matcher(pom);
            if (declared.find()) {
                value = value.replace(property.group(), declared.group(1).trim())
                        .replace("${project.basedir}/", "").replace("${basedir}/", "");
            }
        }
        return value;
    }

    private static boolean containsJavaSources(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var walk = Files.walk(directory)) {
            return walk.anyMatch(f -> f.toString().endsWith(".java"));
        }
    }

    private static String withoutXmlComments(String pom) {
        return pom.replaceAll("(?s)<!--.*?-->", "");
    }
}
