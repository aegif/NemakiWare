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
    @DisplayName("no test BUILDS an AssertionError at all — breakage says HarnessBroken, a failure asserts")
    void noTestThrowsAnAssertionError() throws Exception {
        // BY THE FORM, not by the wording (owner's decision 2026-10-16, residual R189). Three
        // vocabularies were tried and each missed the next phrasing: "was renamed", then "missing
        // for a source file", then "ends inside" — and `throw new AssertionError(e)` cannot be
        // swept by words at all. The rule that needs no vocabulary: a lock never THROWS an
        // AssertionError. Either it is asserting, and the assertion library says so (which the
        // runner should score as a firing), or it could not look at its subject, and that is
        // HarnessBroken (which the runner must not).
        //
        // EVERY module's tests and every form the runner scores, not one directory and one exact
        // string. The first cut of this sweep walked core/src/test/java only and matched the
        // literal "throw new AssertionError(", while the runner scores a firing on the TYPE NAME
        // appearing anywhere in the failure stanza — "AssertionFailedError", "AssertionError",
        // "MockitoAssertionError" (run_negative_controls.py, failure_is_assertion). 224 controls
        // run tests in evidence-verifier-core and evidence-verifier-cli, which that cut never
        // read at all, and a qualified name, an extra space, a caught-and-rethrown one or a
        // variable of that type all walked through it (round-16 review, Codex P1).
        List<Path> roots = testRoots();
        List<String> offenders = new ArrayList<>();
        int filesRead = 0;
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code;
                    try {
                        code = JavaSource.withoutLiteralText(Files.readString(file));
                    } catch (Exception e) {
                        // A file this sweep cannot read is not a file it has cleared.
                        throw new HarnessBroken("could not read " + file + " while sweeping for "
                                + "thrown AssertionErrors", e);
                    }
                    filesRead++;
                    for (Pattern form : THROWN_ASSERTION_FORMS) {
                        Matcher hit = form.matcher(code);
                        while (hit.find()) {
                            offenders.add(file + " 「" + hit.group().replace('\n', ' ').trim() + "」");
                        }
                    }
                }
            }
        }

        // A sweep that read nothing clears everything. It has happened in this tree twice from a
        // path that moved, so the floor is asserted rather than assumed.
        assertTrue(filesRead > 500,
                "this sweep read only " + filesRead + " test sources from " + roots
                        + ", so whatever it cleared it did not look at");

        assertTrue(offenders.isEmpty(),
                "these locks BUILD (or hold, or catch) an AssertionError, which the control runner "
                        + "scores as a firing even when the sabotage only broke the harness. Say it "
                        + "with HarnessBroken when the lock could not look at its subject, or assert "
                        + "it with the assertion library when the production broke its promise: "
                        + offenders);
    }

    @Test
    @DisplayName("a lock that reflects tells a thrown subject apart from a method it could not find")
    void reflectionDoesNotCallAProductThrowAHarnessBreakage() throws Exception {
        // The other half of the same classification. `Method.invoke` wraps whatever the SUBJECT
        // threw in InvocationTargetException, so a `catch (Exception e) { throw new
        // HarnessBroken(e); }` around a reflective call reports a production regression as "this
        // lock could not look at its subject" — and sends the reader to fix the test (round-16
        // review, Codex P2). Three sites did exactly that, and five more caught only
        // NoSuchMethodException and let the wrapper escape unexplained.
        //
        // The rule, by file so that no brace matching is needed: a test that reflects AND converts
        // to HarnessBroken has to NAME InvocationTargetException. What this does not prove: that
        // the clause is placed around the right call — only that the distinction was considered.
        List<String> conflating = new ArrayList<>();
        for (Path root : testRoots()) {
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = JavaSource.withoutLiteralText(Files.readString(file));
                    if (code.contains(".invoke(") && code.contains("new HarnessBroken(")
                            && !code.contains("InvocationTargetException")) {
                        conflating.add(file.toString());
                    }
                }
            }
        }

        assertTrue(conflating.isEmpty(),
                "these locks reflect and convert to HarnessBroken without ever naming "
                        + "InvocationTargetException, so whatever the subject threw is reported as "
                        + "the lock being unable to look at it: " + conflating);
    }

    @Test
    @DisplayName("the sweep's forms match every shape the runner scores, and its roots cover every module's tests")
    void theSweepMatchesWhatTheRunnerScores() throws Exception {
        // What the first cut let through, each one a shape the runner scores as a firing
        // (round-16 review, Codex P1). Without this the sweep passes by matching nothing.
        for (String slips : List.of(
                "throw new AssertionError(\"x\");",
                "throw new AssertionError (\"x\");",
                "    .orElseThrow(() -> new AssertionError(\"the entry vanished\"));",
                "    AssertionError failure = new AssertionError(\"x\");",
                "throw new\n        AssertionError(\"x\");",
                "throw new java.lang.AssertionError(e);",
                "throw new AssertionFailedError(\"x\");",
                "throw new org.opentest4j.AssertionFailedError(\"x\");",
                "throw new MockitoAssertionError(\"x\");",
                "        AssertionError failure = somethingThatBuildsOne();",
                "        } catch (AssertionError e) {")) {
            assertTrue(THROWN_ASSERTION_FORMS.stream().anyMatch(f -> f.matcher(slips).find()),
                    "this sweep does not see 「" + slips + "」, and the runner would score the "
                            + "harness breaking as a control firing");
        }

        // And it must not call the ordinary ways of ASSERTING an AssertionError an offender, or
        // the rule cannot be kept at all.
        for (String fine : List.of(
                "assertThrows(AssertionError.class, () -> lock.run());",
                "throw new HarnessBroken(\"could not read \" + file, e);",
                "assertFalse(HarnessBroken.class.isAssignableFrom(AssertionError.class));",
                "Class<?> type = AssertionFailedError.class;")) {
            assertTrue(THROWN_ASSERTION_FORMS.stream().noneMatch(f -> f.matcher(fine).find()),
                    "this sweep calls 「" + fine + "」 an offender, so the rule it enforces cannot "
                            + "be followed");
        }

        List<String> roots = testRoots().stream().map(Path::toString).toList();
        assertTrue(roots.stream().anyMatch(r -> r.contains("evidence-verifier-core")),
                "the sweep does not read evidence-verifier-core's tests, where 210 controls run "
                        + "theirs: " + roots);
        assertTrue(roots.stream().anyMatch(r -> r.contains("evidence-verifier-cli")),
                "the sweep does not read evidence-verifier-cli's tests, where 14 controls run "
                        + "theirs: " + roots);
        assertTrue(roots.stream().anyMatch(r -> r.startsWith("src/test/java")
                        || r.contains("core/src/test/java")),
                "the sweep does not read this module's own tests: " + roots);
    }

    /**
     * Every form that puts an assertion type's NAME into a failure stanza by the test's own hand.
     *
     * <p>The runner matches the name as a substring, so the simple name is what matters and a
     * qualified one matters just as much. {@code \w*Assertion\w*Error} covers
     * {@code AssertionError}, {@code AssertionFailedError} and Mockito's
     * {@code MockitoAssertionError} — all three are in the runner's marker list.
     */
    private static final List<Pattern> THROWN_ASSERTION_FORMS = List.of(
            // BUILT, wherever it is built: `orElseThrow(() -> new AssertionError(…))` throws one
            // without the word `throw` next to it, and five locks were written that way
            // (round-16 review: both reviewers, independently, on the same line)
            Pattern.compile("new\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*\\w*Assertion\\w*Error\\s*\\("),
            // held in a variable or field, which is the route to `throw it;`
            Pattern.compile("(?:^|[\\s(,])(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*\\w*Assertion\\w*Error\\s+[a-z][\\w$]*\\s*(?:=|;)"),
            // caught, which is the route to rethrowing one the harness produced
            Pattern.compile("catch\\s*\\([^)]*\\w*Assertion\\w*Error"));

    /**
     * The test roots of every module this build has, read from the build itself.
     *
     * <p>Not a hand-written list: the first cut of this sweep hard-coded one directory and so
     * never read the two verifier modules, where 224 controls run their tests.
     */
    private static List<Path> testRoots() throws Exception {
        Path pom = Path.of("../pom.xml");
        if (!Files.exists(pom)) {
            throw new HarnessBroken("the reactor pom is not at " + pom.toAbsolutePath()
                    + ", so this sweep cannot know which modules exist");
        }
        List<Path> roots = new ArrayList<>();
        Matcher modules = Pattern.compile("<module>([^<]+)</module>")
                .matcher(Files.readString(pom));
        while (modules.find()) {
            Path module = Path.of("..", modules.group(1).trim());
            if (!Files.isDirectory(module)) {
                throw new HarnessBroken("the reactor names module " + modules.group(1)
                        + " and there is no such directory, so this sweep cannot say it read it");
            }
            Path tests = module.resolve("src/test/java");
            if (Files.isDirectory(tests)) {
                roots.add(tests);
            }
        }
        if (roots.size() < 2) {
            throw new HarnessBroken("the reactor pom yielded " + roots.size() + " test roots "
                    + roots + "; this build has more than that, so the pom was not read");
        }
        return roots;
    }

}
