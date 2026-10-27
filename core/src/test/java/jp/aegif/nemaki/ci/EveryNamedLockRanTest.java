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
package jp.aegif.nemaki.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * The unit job fails unless each lock it names RAN, whole — a report, with tests, none skipped, every declared test
 * method among them (R181).
 *
 * <p>Drives {@code tools/ci/named-locks-ran.sh}, the script the job runs after the suite, with surefire report
 * directories and lock sources written here: the shape a silenced lock leaves (no report — an {@code @Tag} in the
 * excluded group, a pattern, {@code -Dtest}, a rename), a report with no tests, a report with a skipped test, a
 * report missing a method the source declares (a method-level {@code @Tag} takes it out at discovery and leaves no
 * skipped count), and a report for a class of the same simple name in another package. Before R181 the job read the
 * selection instead, one way of silencing at a time, and a lock tagged {@code atlas-integration} passed every check
 * while not running (measured 2026-10-27: {@code mvn test} exits 0 with the tag on, and this script exits 1). Then it
 * reads the workflow, because a script nothing calls checks nothing.
 */
class EveryNamedLockRanTest {

    private static final Path SCRIPT = Path.of("../tools/ci/named-locks-ran.sh");
    private static final Path WORKFLOW = Path.of("../.github/workflows/integration-tests.yml");
    private static final String FOO = "jp.aegif.nemaki.x.FooTest";
    private static final String BAR = "jp.aegif.nemaki.x.BarTest";

    @TempDir
    Path reports;

    @TempDir
    Path sources;

    private record Outcome(int exit, String output) { }

    private Outcome run(String... locks) throws IOException, InterruptedException {
        return runOn(reports, locks);
    }

    private Outcome runOn(Path directory, String... locks) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(
                List.of("bash", SCRIPT.toString(), directory.toString(), sources.toString()));
        command.addAll(List.of(locks));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script did not finish");
        return new Outcome(process.exitValue(), output);
    }

    /** A lock's source declaring the given test methods (each a plain {@code @Test void name()}). */
    private void source(String lock, String... methods) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String method : methods) {
            body.append("    @Test\n    void ").append(method).append("() { }\n");
        }
        sourceText(lock, body.toString());
    }

    private void sourceText(String lock, String body) throws IOException {
        int dot = lock.lastIndexOf('.');
        Path file = sources.resolve(lock.substring(0, dot).replace('.', '/')).resolve(lock.substring(dot + 1) + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + lock.substring(0, dot) + ";\n\nimport org.junit.jupiter.api.Test;\n\n"
                + "class " + lock.substring(dot + 1) + " {\n" + body + "}\n", StandardCharsets.UTF_8);
    }

    /** A lock's source written whole, for shapes the one-class wrapper cannot hold (a second top-level class it extends). */
    private void rawSource(String lock, String text) throws IOException {
        int dot = lock.lastIndexOf('.');
        Path file = sources.resolve(lock.substring(0, dot).replace('.', '/')).resolve(lock.substring(dot + 1) + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + lock.substring(0, dot) + ";\n\n" + text, StandardCharsets.UTF_8);
    }

    /** A report naming each case with the class that declares it, as surefire writes them (measured: classname). */
    private void report(String fileName, String testsuiteAttributes, String classname, String... cases) throws IOException {
        StringBuilder testcases = new StringBuilder();
        for (String c : cases) {
            testcases.append("  <testcase name=\"").append(c).append("\" classname=\"").append(classname)
                    .append("\" time=\"0\"/>\n");
        }
        Files.writeString(reports.resolve(fileName), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " + testsuiteAttributes + ">\n"
                + testcases + "</testsuite>\n", StandardCharsets.UTF_8);
    }

    /** A report for {@code lock} counting {@code skipped} of its cases skipped. */
    private void ran(String lock, int skipped, String... cases) throws IOException {
        report("TEST-" + lock + ".xml", "name=\"" + lock + "\" time=\"0.1\" tests=\"" + cases.length
                + "\" errors=\"0\" skipped=\"" + skipped + "\" failures=\"0\"", lock, cases);
    }

    private void assertRefused(Outcome outcome, String lock, String why) {
        assertEquals(1, outcome.exit(), why + " — and the job's check passed it: " + outcome.output());
        assertTrue(outcome.output().contains("::error::" + lock), "the refusal does not name " + lock + ": "
                + outcome.output());
    }

    @Test
    @DisplayName("every named lock with a report, tests, none skipped and every declared method passes")
    void locksThatRanPass() throws Exception {
        source(FOO, "a", "b", "c");
        source(BAR, "d");
        ran(FOO, 0, "a", "b", "c");
        ran(BAR, 0, "d");
        Outcome outcome = run(FOO, BAR);
        assertEquals(0, outcome.exit(), "two locks that ran were refused: " + outcome.output());
    }

    @Test
    @DisplayName("a lock with no report — tagged into the excluded group, excluded by a pattern, renamed — fails the job")
    void aLockWithNoReportFails() throws Exception {
        source(FOO, "a");
        source(BAR, "d");
        ran(FOO, 0, "a");
        assertRefused(run(FOO, BAR), BAR, "BarTest has no report, so it did not run");
    }

    @Test
    @DisplayName("a lock whose report counted no tests fails the job")
    void aLockThatRanNoTestsFails() throws Exception {
        // The report names the declared method, so «no tests» is the one reason left to refuse it.
        source(FOO, "a");
        report("TEST-" + FOO + ".xml", "name=\"" + FOO + "\" tests=\"0\" skipped=\"0\" errors=\"0\" failures=\"0\"", FOO, "a");
        assertRefused(run(FOO), FOO, "FooTest's report counts no tests");
    }

    @Test
    @DisplayName("a lock with a skipped test fails the job — @Disabled on a method, an assumption that did not hold")
    void aLockWithASkippedTestFails() throws Exception {
        source(FOO, "a", "b", "c", "d", "e");
        ran(FOO, 1, "a", "b", "c", "d", "e");
        assertRefused(run(FOO), FOO, "FooTest skipped one of its tests");
    }

    @Test
    @DisplayName("a method the source declares and the report does not name fails the job — a method-level @Tag")
    void aMethodTakenOutAtDiscoveryFails() throws Exception {
        // Measured 2026-10-27 on IsNullNamesTheSolrFieldTest: one method tagged atlas-integration, and the report
        // said tests=4 skipped=0 — the method gone, nothing skipped, and the old three questions passed it
        // (closing batch 5 review, subagent P2-2).
        source(FOO, "a", "b", "c");
        ran(FOO, 0, "a", "b");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "FooTest's method c did not run");
        assertTrue(outcome.output().contains(": FooTest.c "), "the refusal does not name the method that did not run: "
                + outcome.output());
    }

    @Test
    @DisplayName("a parameterized method whose annotation holds a brace is still a declared test, and its absence fails the job")
    void aParameterizedMethodTakenOutFails() throws Exception {
        // The head of a method was cut at the first brace before its annotations were stripped, so the brace inside
        // @ValueSource(strings = { ... }) hid the method: a tag on it went unseen (closing batch 6, found by TN5 not firing).
        sourceText(FOO, "    @ParameterizedTest\n    @ValueSource(strings = { \"x\", \"y\" })\n    void each(String v) { }\n"
                + "    @Test\n    void plain() { }\n");
        ran(FOO, 0, "plain");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "FooTest's parameterized method did not run");
        assertTrue(outcome.output().contains(": FooTest.each "), "the refusal does not name the parameterized method: "
                + outcome.output());
    }

    @Test
    @DisplayName("a method of the same name in another class of the lock does not stand in for it")
    void aSameNamedMethodElsewhereInTheLockIsNotIt() throws Exception {
        // Both sides were sets of method names, so the outer guard() that ran answered for Inner's guard() that was
        // taken out at discovery (closing batch 6 review, both reviewers). Surefire reports each case with the class
        // that declares it, all of them in the report of the innermost class (measured).
        sourceText(FOO, "    @Test\n    void guard() { }\n    @Nested\n    class Inner {\n        @Test\n"
                + "        void guard() { }\n    }\n");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "guard");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "only the outer guard() ran");
        assertTrue(outcome.output().contains(": FooTest.Inner.guard "), "the refusal does not name the nested method: "
                + outcome.output());
    }

    @Test
    @DisplayName("a brace in a nested class's header is not its body, so its method is still its own (R216)")
    void aBraceInANestedClassHeaderIsNotItsBody() throws Exception {
        // The first brace after `class Inner` was taken as Inner's body; an annotation's arguments in the header hold
        // one, so Inner ended there and its guard() was read as the outer one's (closing batch 7 review, Codex P1).
        sourceText(FOO, "    @Test\n    void guard() { }\n    @Nested\n    class Inner implements @A(values = {1}) Marker {\n"
                + "        @Test\n        void guard() { }\n    }\n");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "guard");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "only the outer guard() ran");
        assertTrue(outcome.output().contains(": FooTest.Inner.guard "), "the refusal does not name the nested method: "
                + outcome.output());
    }

    @Test
    @DisplayName("@Nested is read past an annotation whose arguments hold braces (R216)")
    void aNestedMarkBehindABracedAnnotationIsRead() throws Exception {
        // The words before `class` are read back to the previous ; { or } — but not one inside an annotation's
        // parentheses, or @SuppressWarnings({"a"}) would hide the @Nested before it. Where @Nested decides the answer:
        // a @Nested base runs its own test, so it is required there AND under the @Nested class that extends it; read
        // as a plain base it would be required under the heir only. So a report with the heir's run and without the
        // base's own is refused only when the mark is read (closing batch 9 review, subagent P2-2).
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    @SuppressWarnings({\"a\"})\n    class Base {\n"
                + "        @Test\n        void guard() { }\n    }\n    @Nested\n    class Impl extends Base {\n"
                + "        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"3\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine", "guard");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the @Nested base's own run is missing");
        assertTrue(outcome.output().contains(": FooTest.Base.guard "), "the refusal does not name the base's own run: "
                + outcome.output());
    }

    @Test
    @DisplayName("a test method of a nested class JUnit does not run as written is not required there (R216)")
    void aMethodOfANestedBaseClassIsNotRequiredThere() throws Exception {
        // A nested base class without @Nested runs nothing itself; its @Test runs in the @Nested class that extends it
        // and is reported under that class. Requiring it under the base refused a lock whose tests all ran (closing
        // batch 7 review, subagent P3-4).
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        @Test\n        void inherited() { }\n"
                + "    }\n    @Nested\n    class Impl extends Base {\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "inherited");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a lock whose tests all ran was refused for its base class: " + outcome.output());
    }

    @Test
    @DisplayName("a test method of a nested base class is required under the @Nested class that extends it (R216)")
    void aNestedBaseClassMethodIsRequiredUnderItsSubclass() throws Exception {
        // Not requiring the base's method at all let a @Tag on it take it out at discovery unseen. It runs, and is
        // reported, under the @Nested class that extends the base, so that is where it is required.
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        @Test\n        void inherited() { }\n"
                + "    }\n    @Nested\n    class Impl extends Base {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the base's method did not run under the class that extends it");
        assertTrue(outcome.output().contains(": FooTest.Impl.inherited "), "the refusal does not name the base's method "
                + "under its subclass: " + outcome.output());
    }

    @Test
    @DisplayName("an interface's default test method is required under the @Nested class that implements it")
    void anInterfaceDefaultTestIsRequiredUnderItsImplementer() throws Exception {
        // Only `extends` was read, so a @Tag on an interface's default @Test took it out unseen (closing batch 8 review,
        // both reviewers).
        sourceText(FOO, "    @Test\n    void own() { }\n    interface Contract {\n        @Test\n        default void guard() { }\n"
                + "    }\n    @Nested\n    class Impl implements Contract {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the interface's default test did not run under its implementer");
        assertTrue(outcome.output().contains(": FooTest.Impl.guard "), "the refusal does not name the default method "
                + "under its implementer: " + outcome.output());
    }

    @Test
    @DisplayName("a nested class that lost its @Nested is still the lock's, so its silent tests are refused")
    void aNestedClassWithoutItsMarkIsStillRequired() throws Exception {
        // Not requiring a member class without @Nested let a lock be silenced by deleting the mark (closing batch 8
        // review, subagent P3-9): nothing extends it, so its tests run nowhere and are required where they are written.
        sourceText(FOO, "    @Test\n    void own() { }\n    class Inner {\n        @Test\n        void guard() { }\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "only own() ran");
        assertTrue(outcome.output().contains(": FooTest.Inner.guard "), "the refusal does not name the silenced "
                + "method: " + outcome.output());
    }

    @Test
    @DisplayName("a base class held in a plain holder is required under the @Nested class that extends Holder.Base")
    void aBaseInAPlainHolderIsRequiredUnderItsHeir() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    static class Holder {\n        abstract static class Base {\n"
                + "            @Test\n            void inherited() { }\n        }\n    }\n    @Nested\n"
                + "    class Impl extends Holder.Base {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the held base's test did not run under the class that extends it");
        assertTrue(outcome.output().contains(": FooTest.Impl.inherited "), "the refusal does not name the base's "
                + "method under its heir: " + outcome.output());
    }

    @Test
    @DisplayName("a type parameter's bound is not a superclass, so its tests are not required there")
    void aTypeParameterBoundIsNotASuperclass() throws Exception {
        // `class Impl<T extends Base> implements Marker` matched `extends Base` and required Base's test under Impl, where
        // it never runs (closing batch 8 review, subagent P3-9).
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        @Test\n        void inherited() { }\n"
                + "    }\n    interface Marker { }\n    @Nested\n    class Real extends Base { }\n    @Nested\n"
                + "    class Impl<T extends Base> implements Marker {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Real.xml", "name=\"" + FOO + "$Real\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Real2.xml", "name=\"" + FOO + "$Real\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Real", "inherited");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a lock whose tests all ran was refused for a type parameter's bound: "
                + outcome.output());
    }

    @Test
    @DisplayName("a base two classes up is required under the @Nested class that inherits it through the middle one")
    void aBaseTwoStepsUpIsRequiredUnderItsHeir() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        @Test\n        void inherited() { }\n"
                + "    }\n    abstract static class Middle extends Base { }\n    @Nested\n    class Impl extends Middle {\n"
                + "        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the base's test did not run under the class that inherits it through Middle");
        assertTrue(outcome.output().contains(": FooTest.Impl.inherited "), "the refusal does not name the base's "
                + "method under its heir: " + outcome.output());
    }

    @Test
    @DisplayName("an @Test inside a local class is not one of the lock's")
    void aLocalClassIsNotTheLocks() throws Exception {
        sourceText(FOO, "    @Test\n    void a() {\n        class Local {\n            @Test\n            void notRun() { }\n"
                + "        }\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of a local class was required of the lock: " + outcome.output());
    }

    @Test
    @DisplayName("an anonymous class with a long type before its arguments is still an anonymous class")
    void aLongAnonymousTypeIsStillAnonymous() throws Exception {
        String longType = "java.util.function.Supplier<java.util.Map<java.lang.String, java.util.List<java.util.Map<"
                + "java.lang.String, java.util.Set<java.util.Map.Entry<java.lang.String, java.lang.Integer>>>>>>";
        String type = longType + " ".repeat(Math.max(0, 320 - longType.length()));
        sourceText(FOO, "    @Test\n    void a() {\n        Object r = new " + type + "() {\n            @Test\n"
                + "            public void notRun() { }\n            public Object get() { return null; }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of an anonymous class with a long type was required of the lock: "
                + outcome.output());
    }

    @Test
    @DisplayName("a @Nested base's test is required under the @Nested class that extends it too")
    void aRunningBaseIsRequiredUnderItsHeirToo() throws Exception {
        // The declaring class running ended the search, so a @Nested heir that overrides a hook and was tagged out
        // whole went unseen (closing batch 9 review, subagent P2-2).
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    class Default {\n        @Test\n        void guard() { }\n"
                + "    }\n    @Nested\n    class Hardened extends Default { }\n");
        report("TEST-" + FOO + "$Default.xml", "name=\"" + FOO + "$Default\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Default2.xml", "name=\"" + FOO + "$Default\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Default", "guard");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the heir that runs the base's test did not run");
        assertTrue(outcome.output().contains(": FooTest.Hardened.guard "), "the refusal does not name the heir's run: "
                + outcome.output());
    }

    @Test
    @DisplayName("a second top-level class the lock extends is the lock's, so its tests are required under the lock")
    void aSecondTopLevelBaseOfTheLockIsRequiredUnderTheLock() throws Exception {
        // Another top-level class was skipped whole, so a contract the lock inherits could be tagged out unseen
        // (closing batch 9 review, subagent P2-1: a regression of closing batch 9).
        rawSource(FOO, "import org.junit.jupiter.api.Test;\n\nabstract class FooContract {\n    @Test\n    void guard() { }\n"
                + "    @Test\n    void second() { }\n}\n\nclass FooTest extends FooContract {\n    @Test\n    void own() { }\n}\n");
        ran(FOO, 0, "own", "guard");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the inherited second() did not run");
        assertTrue(outcome.output().contains(": FooTest.second "), "the refusal does not name the inherited method "
                + "under the lock: " + outcome.output());
    }

    @Test
    @DisplayName("an heir with an empty body {} is still an heir")
    void anEmptyHeirBodyIsStillAnHeir() throws Exception {
        // `{}` has no offset strictly inside it, so the class dropped out of its own chain and its base's test was
        // required where it is written (closing batch 9 review, subagent P2-3).
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        @Test\n        void inherited() { }\n"
                + "    }\n    @Nested\n    class Impl extends Base {}\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "inherited");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a lock whose tests all ran was refused for an heir with an empty body: "
                + outcome.output());
    }

    @Test
    @DisplayName("an anonymous class with a type-use annotation is still an anonymous class")
    void anAnnotatedAnonymousClassIsNotTheLocks() throws Exception {
        sourceText(FOO, "    @Test\n    void a() {\n        Runnable r = new @Deprecated(since = \"1\") Runnable() {\n"
                + "            @Test\n            public void ghost() { }\n            public void run() { }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of an annotated anonymous class was required of the lock: "
                + outcome.output());
    }

    @Test
    @DisplayName("an enum constant's body is an anonymous class, so its @Test is not the lock's")
    void anEnumConstantBodyIsNotTheLocks() throws Exception {
        sourceText(FOO, "    @Test\n    void a() { }\n    enum Helper {\n        ONE {\n            @Test\n"
                + "            void ghost() { }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of an enum constant's body was required of the lock: "
                + outcome.output());
    }

    @Test
    @DisplayName("a test method of another top-level class in the lock's file is not the lock's")
    void anotherTopLevelClassIsNotTheLocks() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n}\nclass Neighbour {\n    @Test\n    void notTheLocks() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of another top-level class was required of the lock: "
                + outcome.output());
    }

    @Test
    @DisplayName("an escaped triple quote does not end a text block, so what follows it is still text")
    void anEscapedTripleQuoteDoesNotEndATextBlock() throws Exception {
        sourceText(FOO, "    @Test\n    void own() {\n        String s = \"\"\"\n            a \\\"\"\" inside\n"
                + "            @Test void ghost() { }\n            \"\"\";\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "text inside a text block was read as a test method: " + outcome.output());
    }

    @Test
    @DisplayName("an @Test inside an anonymous class is not one of the lock's (R216)")
    void anAnonymousClassIsNotTheLocks() throws Exception {
        sourceText(FOO, "    @Test\n    void a() {\n        Runnable r = new Runnable() {\n            @Test\n"
                + "            public void notRun() { }\n            public void run() { }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method of an anonymous class was required of the lock: " + outcome.output());
    }

    @Test
    @DisplayName("a nested class's report counts for its lock, skipped tests included")
    void aNestedClassCountsForItsLock() throws Exception {
        sourceText(FOO, "    @Nested\n    class Inner {\n        @Test\n        void a() { }\n"
                + "        @Test\n        void b() { }\n    }\n");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Inner", "a", "b");
        Outcome onlyNested = run(FOO);
        assertEquals(0, onlyNested.exit(), "a lock whose tests are all in a nested class was refused: "
                + onlyNested.output());
        report("TEST-" + FOO + "$Other.xml", "name=\"" + FOO + "$Other\" tests=\"2\" skipped=\"2\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Other", "a", "b");
        assertRefused(run(FOO), FOO, "a nested class of FooTest skipped its tests");
    }

    @Test
    @DisplayName("a report for a longer class name is not the lock's")
    void aReportForALongerNameIsNotTheLocks() throws Exception {
        // The cases inside name the LOCK, so it is the report's file name alone that must keep them out: the class in
        // each case is checked separately (aSameNamedMethodElsewhereInTheLockIsNotIt), and a control on the choice of
        // files has to see this lock fail on its own (closing batch 7, RT5).
        source(FOO, "a");
        report("TEST-jp.aegif.nemaki.x.FooTestExtra.xml", "name=\"jp.aegif.nemaki.x.FooTestExtra\" tests=\"1\" skipped=\"0\" "
                + "errors=\"0\" failures=\"0\"", FOO, "a");
        report("TEST-jp.aegif.nemaki.x.PreFooTest.xml", "name=\"jp.aegif.nemaki.x.PreFooTest\" tests=\"1\" skipped=\"0\" "
                + "errors=\"0\" failures=\"0\"", FOO, "a");
        assertRefused(run(FOO), FOO, "only FooTestExtra and PreFooTest ran, not FooTest");
    }

    @Test
    @DisplayName("a class of the same simple name in another package does not stand in for the lock")
    void aClassOfTheSameNameElsewhereIsNotTheLock() throws Exception {
        // Chosen by the simple name, TEST-<other.pkg>.FooTest.xml was the lock's (closing batch 5 review, Codex P1).
        // As above, the cases name the lock, so the file name alone has to keep the decoy's report out (closing batch 7, TJ5).
        source(FOO, "a");
        report("TEST-jp.aegif.nemaki.decoy.FooTest.xml", "name=\"jp.aegif.nemaki.decoy.FooTest\" tests=\"1\" skipped=\"0\" "
                + "errors=\"0\" failures=\"0\"", FOO, "a");
        assertRefused(run(FOO), FOO, "only a decoy FooTest in another package ran");
    }

    @Test
    @DisplayName("a lock named by its simple name is refused, since that name cannot pick out its report")
    void aSimpleNameIsRefused() throws Exception {
        source(FOO, "a");
        ran(FOO, 0, "a");
        Outcome outcome = run("FooTest");
        assertRefused(outcome, "FooTest", "a simple name was taken as a lock");
        assertTrue(outcome.output().contains("not a fully qualified class name"),
                "a simple name was refused for another reason, so nothing refuses it as a name: " + outcome.output());
    }

    @Test
    @DisplayName("a lock with no source cannot be checked against what it declares, and fails the job")
    void aLockWithNoSourceFails() throws Exception {
        ran(FOO, 0, "a");
        assertRefused(run(FOO), FOO, "FooTest has a report and no source");
    }

    @Test
    @DisplayName("a source from which no test method can be read fails the job rather than passing everything")
    void aSourceWithNoReadableTestFails() throws Exception {
        sourceText(FOO, "    void notATest() { }\n");
        ran(FOO, 0, "notATest");
        assertRefused(run(FOO), FOO, "nothing could be read from FooTest's source, and the check passed");
    }

    @Test
    @DisplayName("@Test in a comment or a string is not a declared test, and a parameterized case counts for its method")
    void onlyDeclaredTestMethodsAreRequired() throws Exception {
        // Over-refusal is the other direction: a source that MENTIONS @Test must not require a method it never
        // declared, and a case reported as name(args)[n] is that method.
        sourceText(FOO, "    // @Test\n    void helperOne() { }\n    /* @Test */ void helperTwo() { }\n"
                + "    String s = \"@Test void helperThree()\";\n"
                + "    @ParameterizedTest\n    @ValueSource(strings = { \"x\", \"y\" })\n    void each(String v) { }\n"
                + "    @Test\n    @DisplayName(\"with (parentheses)\")\n    void plain() { }\n"
                + "    @TestFactory\n    Stream<DynamicTest> locks() { return Stream.empty(); }\n");
        // A dynamic test is reported as factory()[n] — measured with surefire 3.5.2 (closing batch 6 review, Codex P3).
        ran(FOO, 0, "each(String)[1]", "each(String)[2]", "plain", "locks()[1]");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a lock that ran every declared method was refused: " + outcome.output());
    }

    @Test
    @DisplayName("a report that does not state tests and skipped cannot show the lock ran")
    void anUnreadableReportFails() throws Exception {
        source(FOO, "a", "b", "c");
        ran(FOO, 0, "a", "b", "c");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" errors=\"0\"", FOO + "$Inner");
        assertRefused(run(FOO), FOO, "one of FooTest's reports states no counts");
    }

    @Test
    @DisplayName("no report directory at all fails the job")
    void noReportDirectoryFails() throws Exception {
        source(FOO, "a");
        Outcome outcome = runOn(reports.resolve("not-there"), FOO);
        assertEquals(1, outcome.exit(), "no surefire reports at all, and the check passed: " + outcome.output());
    }

    @Test
    @DisplayName("the unit job runs the check on the locks it names, from the one list both of its checks read")
    void theUnitJobChecksTheLocksItNames() throws IOException {
        String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        int start = workflow.indexOf("\n  unit-tests:\n");
        HarnessBroken.unless(start >= 0, "the workflow has no unit-tests job");
        Matcher next = Pattern.compile("(?m)^  [a-z][a-z0-9_-]*:\\n").matcher(workflow);
        String job = next.find(start + 5) ? workflow.substring(start, next.start()) : workflow.substring(start);

        Matcher list = Pattern.compile("(?m)^      NAMED_LOCKS: >-\\n((?:        \\S+\\n)+)").matcher(job);
        assertTrue(list.find(), "the unit job does not name its locks in NAMED_LOCKS");
        List<String> named = new ArrayList<>();
        for (String line : list.group(1).split("\n")) {
            named.add(line.trim());
        }
        assertTrue(named.size() >= 10, "the unit job names " + named.size() + " locks: " + named);
        for (String lock : named) {
            assertTrue(lock.contains("."), "the unit job names " + lock + " by its simple name");
            assertTrue(Files.isRegularFile(Path.of("src/test/java", lock.replace('.', '/') + ".java")),
                    "the unit job names " + lock + ", which is not a test class here");
        }
        assertTrue(job.contains("\n          LOCKS=\"$NAMED_LOCKS\"\n"),
                "the check before the run does not read the locks from NAMED_LOCKS, so the two checks can name "
                        + "different locks");
        int ranStep = job.indexOf("- name: The suite actually ran");
        assertTrue(ranStep >= 0, "the unit job has no step after the run that reads what ran");
        int stepEnd = job.indexOf("\n      - ", ranStep + 1);
        String step = stepEnd > 0 ? job.substring(ranStep, stepEnd) : job.substring(ranStep);
        assertTrue(step.contains("\n          bash tools/ci/named-locks-ran.sh \"$REPORTS\" core/src/test/java $NAMED_LOCKS\n"),
                "the step after the run does not check that each named lock ran");
        assertFalse(step.contains("|| true"), "the step after the run lets a failure through");
    }
}
