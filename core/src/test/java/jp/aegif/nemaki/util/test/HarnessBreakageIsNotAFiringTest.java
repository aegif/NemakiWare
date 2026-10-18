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
 * use the assertion library ({@code assertX}, or {@code Assertions.fail(msg, cause)}), and never
 * build an assertion type yourself: the sweep below forbids {@code new AssertionError(…)} in any
 * form, because the control runner scores that type's NAME in a failure stanza as "the guard
 * tripped". A reflective call needs both arms — {@code InvocationTargetException} is the SUBJECT
 * throwing (assert), anything else is the lock being unable to look (breakage).
 *
 * <p>Two limits, stated rather than implied. (1) The modules outside {@code core} have no
 * {@code HarnessBroken} on their test path, so a lock there can only avoid throwing an assertion
 * type — its breakage reads to the runner as "did not fire" rather than being excluded by name.
 * (2) Saying breakage through an ASSERTION METHOD ({@code assertTrue(found > 0, "X is gone")})
 * cannot be swept at all: it is indistinguishable from an ordinary assertion, two predicates
 * measured it as 50 and 404 occurrences, and neither is the set. That half is residual R191.
 *
 * <p>Previously this rule lived in a {@code //} comment inside the sweep, where a new lock's
 * author would not read it (round-16 review, subagent P3).
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
        int filesRead = 0;
        for (Path root : roots) {
            int inThisRoot = 0;
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = readOrBreak(file);
                    filesRead++;
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
                "these locks BUILD (or hold) a type the control runner scores as a firing, so a "
                        + "sabotage that only broke the harness is recorded as the guard tripping. "
                        + "Say it with HarnessBroken when the lock could not look at its subject, "
                        + "or assert it with the assertion library when the production broke its "
                        + "promise: " + offenders);
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
        int filesRead = 0;
        for (Path root : testRoots()) {
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = readOrBreak(file);
                    filesRead++;
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
        }
        if (filesRead < 1) {
            throw new HarnessBroken("this reader walked " + testRoots() + " and read no sources");
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
                "        assert found > 0 : \"the view answered nothing\";")) {
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
                "assertTrue(found > 0, \"the view answered nothing\");")) {
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
        String tuple = code.substring(at, code.indexOf("))", at));
        List<String> names = new ArrayList<>();
        Matcher marker = Pattern.compile("\"([A-Z][A-Za-z0-9]+)\"").matcher(tuple);
        while (marker.find()) {
            names.add(marker.group(1));
        }
        if (names.size() < 5) {
            throw new HarnessBroken("the runner's marker tuple yielded " + names
                    + "; it has more type names than that, so it was not read");
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
                Pattern.compile("new\\s+" + qualifier + "(?:" + names + ")\\s*\\("),
                // held in a variable or field, which is the route to `throw it;` — but NOT the
                // meta-test's `AssertionError thrown = assertThrows(…)`, which asserts rather
                // than builds (round-17 review, subagent P3-10).
                Pattern.compile("(?:^|[\\s(,])" + qualifier + "(?:" + names
                        + ")\\s+[A-Za-z_$][\\w$]*\\s*(?:;|=(?!\\s*assertThrows))"),
                // the `assert` STATEMENT: the JVM builds a java.lang.AssertionError and surefire
                // enables assertions by default, so this is a firing with no `new` in sight
                // (round-17 review, subagent P2-2; eight of them were in ConnectionTestGroup).
                Pattern.compile("(?m)^\\s*assert\\s+[^;{]*;"));
    }

    /**
     * The test roots of every module this build has, read from the build itself.
     *
     * <p>Not a hand-written list: the first cut of this sweep hard-coded one directory and so never
     * read the two verifier modules, where 224 controls run their tests. A module's own
     * {@code <testSourceDirectory>} is honoured and nested reactors are followed, so a module that
     * keeps its tests elsewhere is read rather than silently skipped (round-17 review, Codex P2) —
     * and a module that has a {@code src/test} tree this method did not choose is refused rather
     * than passed over.
     */
    private static List<Path> testRoots() throws Exception {
        List<Path> roots = new ArrayList<>();
        collectTestRoots(Path.of(".."), roots);
        if (roots.size() < 2) {
            throw new HarnessBroken("the reactor pom yielded " + roots.size() + " test roots "
                    + roots + "; this build has more than that, so the pom was not read");
        }
        return roots;
    }

    private static void collectTestRoots(Path reactor, List<Path> into) throws Exception {
        Path pom = reactor.resolve("pom.xml");
        if (!Files.exists(pom)) {
            throw new HarnessBroken("there is no pom at " + pom.toAbsolutePath()
                    + ", so this sweep cannot know which modules exist");
        }
        String text = Files.readString(pom);
        Matcher modules = Pattern.compile("<module>([^<]+)</module>").matcher(text);
        while (modules.find()) {
            Path module = reactor.resolve(modules.group(1).trim());
            if (!Files.isDirectory(module)) {
                throw new HarnessBroken("the reactor names module " + modules.group(1)
                        + " and there is no such directory, so this sweep cannot say it read it");
            }
            Path modulePom = module.resolve("pom.xml");
            String moduleText = Files.exists(modulePom) ? Files.readString(modulePom) : "";
            if (moduleText.contains("<modules>")) {
                collectTestRoots(module, into);        // a nested reactor
                continue;
            }
            Matcher declared = Pattern.compile("<testSourceDirectory>([^<]+)</testSourceDirectory>")
                    .matcher(moduleText);
            Path tests = declared.find()
                    ? module.resolve(declared.group(1).trim().replace("${project.basedir}/", ""))
                    : module.resolve("src/test/java");
            if (Files.isDirectory(tests)) {
                into.add(tests);
            } else if (Files.isDirectory(module.resolve("src/test"))) {
                throw new HarnessBroken("module " + modules.group(1) + " has a src/test tree and "
                        + "this sweep chose " + tests + ", which does not exist — its tests would "
                        + "be silently skipped");
            }
        }
    }
}
