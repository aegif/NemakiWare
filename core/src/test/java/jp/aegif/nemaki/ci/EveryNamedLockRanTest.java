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
        rawSource(lock, "import org.junit.jupiter.api.Test;\n\nclass " + lock.substring(lock.lastIndexOf('.') + 1) + " {\n"
                + body + "}\n");
    }

    /** A lock's source after its package line, written whole — for a lock that is not declared as a plain class. */
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
        sourceText(FOO, "    @Test\n    void guard() { }\n    @Nested\n    class Inner<@A(values = {1}) T> {\n"
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
        // parentheses, or @SuppressWarnings({"a"}) would hide the @Nested before it and the class would be refused as one
        // JUnit does not run.
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    @SuppressWarnings({\"a\"})\n    class Inner {\n"
                + "        @Test\n        void guard() { }\n    }\n");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Inner2.xml", "name=\"" + FOO + "$Inner\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Inner", "guard");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a @Nested class behind a braced annotation was not read as @Nested: "
                + outcome.output());
    }

    // What this check JUDGES is one shape: tests written directly in the lock's class or a chain of @Nested classes, none
    // inheriting from a class of the file. Every other shape is REFUSED by name. Four review rounds (closing batches 7 to
    // 10) each found a new shape a model of JUnit's inheritance and discovery read wrong — an override, a @Nested class
    // inside an inherited one, two classes of one simple name — so the check stops at what it reads exactly.

    @Test
    @DisplayName("a test method in a member class without @Nested is refused by name: JUnit does not run it as written")
    void aTestInAClassWithoutNestedIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    class Inner {\n        @Test\n        void guard() { }\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a class JUnit does not run passed");
        assertTrue(outcome.output().contains("guard() at line 9 is in FooTest.Inner, which this check does not judge"),
                "the refusal does not name the method, its line and its class: " + outcome.output());
    }

    @Test
    @DisplayName("an interface's default test method is refused by name")
    void aTestInAnInterfaceIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    interface Contract {\n        @Test\n        default void guard() { }\n"
                + "    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in an interface passed");
        assertTrue(outcome.output().contains("guard() at line 9 is in FooTest.Contract, which this check does not judge"), "the refusal does not name the interface: " + outcome.output());
    }

    @Test
    @DisplayName("a test method of a local class is refused by name")
    void aTestInALocalClassIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void a() {\n        class Local {\n            @Test\n            void notRun() { }\n"
                + "        }\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a local class passed");
        assertTrue(outcome.output().contains("notRun() at line 9 is in FooTest.Local, which this check does not judge"), "the refusal does not name the local class: " + outcome.output());
    }

    @Test
    @DisplayName("a test method of another top-level class of the file is refused by name")
    void aTestInAnotherTopLevelClassIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n}\nclass Neighbour {\n    @Test\n    void notTheLocks() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in another top-level class passed");
        assertTrue(outcome.output().contains("notTheLocks() at line 10 is in Neighbour, another top-level class of the "
                + "file"), "the refusal does not name the other class: " + outcome.output());
    }

    @Test
    @DisplayName("a test method inside an anonymous class is refused by name")
    void aTestInAnAnonymousClassIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void a() {\n        Runnable r = new @Deprecated Runnable() {\n            @Test\n"
                + "            public void ghost() { }\n            public void run() { }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in an anonymous class passed");
        assertTrue(outcome.output().contains("ghost() at line 9 is inside a method body or an anonymous class"),
                "the refusal does not name the anonymous class's method: " + outcome.output());
    }

    @Test
    @DisplayName("a test method in an enum constant's body is refused by name")
    void aTestInAnEnumConstantBodyIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void a() { }\n    enum Helper {\n        ONE {\n            @Test\n"
                + "            void ghost() { }\n        };\n    }\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in an enum constant's body passed");
        assertTrue(outcome.output().contains("ghost() at line 10 is inside a method body or an anonymous class"),
                "the refusal does not name the constant body's method: " + outcome.output());
    }

    @Test
    @DisplayName("a running class that extends a class of the file is refused by name, at the line it is declared")
    void inheritanceFromAClassOfTheFileIsRefusedByName() throws Exception {
        // The header runs on to a second line, so the line of its body's brace is not the line of its declaration.
        sourceText(FOO, "    @Test\n    void own() { }\n    abstract static class Base {\n        void helper() { }\n    }\n"
                + "    @Nested\n    class Impl\n            extends Base {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "inheritance within the lock's file passed");
        assertTrue(outcome.output().contains("FooTest.Impl (line 12) extends or implements Base, so a test it inherits"),
                "the refusal does not name the class and what it inherits: " + outcome.output());
    }

    @Test
    @DisplayName("a running class that implements an interface of the file is refused by name")
    void anImplementedInterfaceOfTheFileIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    interface Marker { }\n    @Nested\n    class Impl implements Marker {\n"
                + "        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "implementing an interface of the file passed");
        assertTrue(outcome.output().contains("extends or implements Marker, so a test it inherits"),
                "the refusal does not name the interface: " + outcome.output());
    }

    @Test
    @DisplayName("a qualified supertype is read by its last name, so extends Holder.Base is inheritance from Base")
    void aQualifiedSupertypeIsReadByItsLastName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    static class Holder {\n        static class Base { }\n    }\n"
                + "    @Nested\n    class Impl extends Holder.Base {\n        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a qualified supertype of the file passed");
        assertTrue(outcome.output().contains("extends or implements Base, so a test it inherits"),
                "the refusal does not name Base: " + outcome.output());
    }

    @Test
    @DisplayName("a test two @Nested classes deep is required under the whole chain's name")
    void aDeepNestedChainIsRequiredByItsFullName() throws Exception {
        sourceText(FOO, "    @Nested\n    class Inner {\n        @Nested\n        class Deeper {\n            @Test\n"
                + "            void d() { }\n        }\n    }\n");
        report("TEST-" + FOO + "$Inner$Deeper.xml", "name=\"" + FOO + "$Inner$Deeper\" tests=\"1\" skipped=\"0\" "
                + "errors=\"0\" failures=\"0\"", FOO + "$Inner$Deeper", "d");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a test two @Nested classes deep was not taken under the chain's name: "
                + outcome.output());
    }

    @Test
    @DisplayName("a test two @Nested classes deep that did not run is refused under the whole chain's name")
    void aDeepNestedChainMethodThatDidNotRunIsRefused() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    class Inner {\n        @Nested\n        class Deeper {\n"
                + "            @Test\n            void d() { }\n        }\n    }\n");
        report("TEST-" + FOO + "$Inner$Deeper.xml", "name=\"" + FOO + "$Inner$Deeper\" tests=\"1\" skipped=\"0\" "
                + "errors=\"0\" failures=\"0\"", FOO, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "the deep test did not run and the check passed");
        assertTrue(outcome.output().contains(": FooTest.Inner.Deeper.d "), "the refusal does not name the whole chain: "
                + outcome.output());
    }

    @Test
    @DisplayName("a @Nested class inside a member class without @Nested is refused by name: JUnit does not reach it")
    void aNestedClassInsideAPlainHolderIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    static class Holder {\n        @Nested\n        class X {\n"
                + "            @Test\n            void x() { }\n        }\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a @Nested class JUnit does not reach passed");
        assertTrue(outcome.output().contains("x() at line 11 is in FooTest.Holder.X, which this check does not judge"),
                "the refusal does not name the unreachable class: " + outcome.output());
    }

    @Test
    @DisplayName("a test method in a record is refused by name")
    void aTestInARecordIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    record Point(int x, int y) {\n        @Test\n        void p() { }\n"
                + "    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a record passed");
        assertTrue(outcome.output().contains("p() at line 9 is in FooTest.Point, which this check does not judge"),
                "the refusal does not name the record: " + outcome.output());
    }

    @Test
    @DisplayName("a lock declared as an interface is refused by name: JUnit does not run an interface")
    void aLockThatIsAnInterfaceIsRefusedByName() throws Exception {
        // Only member classes were asked what they are; the lock's own declaration was taken as a class whatever its
        // keyword (closing batch 11 review).
        rawSource(FOO, "import org.junit.jupiter.api.Test;\n\ninterface FooTest {\n    @Test\n    default void a() { }\n}\n");
        ran(FOO, 0, "a");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a lock that is an interface passed");
        assertTrue(outcome.output().contains("a() at line 6 is in FooTest, which this check does not judge"),
                "the refusal does not name the interface: " + outcome.output());
    }

    @Test
    @DisplayName("an enum marked @Nested is refused by name: JUnit runs a @Nested class, not an enum")
    void aNestedEnumIsRefusedByName() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    enum Kind {\n        ONE;\n        @Test\n"
                + "        void e() { }\n    }\n");
        report("TEST-" + FOO + "$Kind.xml", "name=\"" + FOO + "$Kind\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Kind2.xml", "name=\"" + FOO + "$Kind\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Kind", "e");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a @Nested enum passed");
        assertTrue(outcome.output().contains("e() at line 11 is in FooTest.Kind, which this check does not judge"),
                "the refusal does not name the enum: " + outcome.output());
    }

    @Test
    @DisplayName("a static, a private and an abstract @Nested class are each refused by name: JUnit runs none of them")
    void aStaticPrivateOrAbstractNestedClassIsRefusedByName() throws Exception {
        // @Nested on a static, private or abstract class does not make JUnit run it, and the report shows no skip
        // for it (closing batch 11 review: Codex P2, subagent P3-2).
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    static class Holder {\n        @Test\n        void s() { }\n"
                + "    }\n    @Nested\n    private class Hidden {\n        @Test\n        void p() { }\n    }\n"
                + "    @Nested\n    abstract class Partial {\n        @Test\n        void q() { }\n    }\n");
        ran(FOO, 0, "own");
        report("TEST-" + FOO + "$Holder.xml", "name=\"" + FOO + "$Holder\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Holder", "s");
        report("TEST-" + FOO + "$Hidden.xml", "name=\"" + FOO + "$Hidden\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Hidden", "p");
        report("TEST-" + FOO + "$Partial.xml", "name=\"" + FOO + "$Partial\" tests=\"1\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Partial", "q");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a static, private or abstract @Nested class passed");
        assertTrue(outcome.output().contains("cannot judge 3 shape(s)")
                        && outcome.output().contains("s() at line 10 is in FooTest.Holder, which this check does not judge")
                        && outcome.output().contains("p() at line 15 is in FooTest.Hidden, which this check does not judge")
                        && outcome.output().contains("q() at line 20 is in FooTest.Partial, which this check does not judge"),
                "the refusal does not name each of the three classes: " + outcome.output());
    }

    @Test
    @DisplayName("a test annotation with a comment or spaces after its @ is a test JUnit runs, and its absence fails the job")
    void aTestAnnotationWithACommentAfterItsAtSignIsRequired() throws Exception {
        // `@ /* note */ Test` compiles and JUnit runs it; a pattern that wanted the name right after @ did not see it, so
        // a method tagged out at discovery went unrequired (closing batch 11 review, Codex P1).
        sourceText(FOO, "    @Test\n    void own() { }\n    @ /* note */ Test\n    void spaced() { }\n"
                + "    @ org.junit.jupiter.api . Test\n    void qualified() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test annotation with a comment after its @ went unrequired");
        assertTrue(outcome.output().contains("2 of the 3 test methods it declares did not run: FooTest.qualified, "
                + "FooTest.spaced "), "the refusal does not name both methods: " + outcome.output());
    }

    @Test
    @DisplayName("@Nested with a comment after its @ is read, so its class is judged rather than refused")
    void aNestedMarkWithACommentAfterItsAtSignIsRead() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    @ /* note */ Nested\n    class Inner {\n        @Test\n"
                + "        void guard() { }\n    }\n");
        report("TEST-" + FOO + "$Inner.xml", "name=\"" + FOO + "$Inner\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Inner2.xml", "name=\"" + FOO + "$Inner\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Inner", "guard");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a @Nested class with a comment after the @ was not read as @Nested: "
                + outcome.output());
    }

    @Test
    @DisplayName("a refusal names the file's line, the lines of a block comment and a text block before it counted")
    void aRefusalCountsTheLinesOfACommentAndATextBlock() throws Exception {
        // Comments and text blocks were blanked to one space, so every refusal after a licence header named a line 15
        // lines early (closing batch 11 review, both reviewers).
        sourceText(FOO, "    /*\n     * a\n     * b\n     */\n    @Test\n    void own() {\n        String s = \"\"\"\n"
                + "            x\n            y\n            \"\"\";\n    }\n    class Inner {\n        @Test\n"
                + "        void guard() { }\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in a class JUnit does not run passed");
        assertTrue(outcome.output().contains("guard() at line 18 is in FooTest.Inner"),
                "the refusal names a line other than the file's: " + outcome.output());
    }

    @Test
    @DisplayName("a second test method of the same name in a class is refused by name: a report names a case without its parameters")
    void aSecondTestOfTheSameNameIsRefusedByName() throws Exception {
        // The report names a case `each` either way, so one of two same-named methods could be tagged out and the other's
        // run would stand in for it (closing batch 11 review, subagent P2-2).
        sourceText(FOO, "    @Test\n    void each() { }\n    @ParameterizedTest\n    @ValueSource(strings = { \"x\" })\n"
                + "    void each(String v) { }\n");
        ran(FOO, 0, "each");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "two same-named test methods passed on one report case");
        assertTrue(outcome.output().contains("each() at line 8 is the second test method of that name in FooTest"),
                "the refusal does not name the second method: " + outcome.output());
    }

    // The source is read as javac reads it (closing batch 12 review): Unicode escapes first (JLS 3.3), a carriage return
    // alone as a line's end (JLS 3.4), identifiers in any script. Each fixture below writes the escape itself, so the Java
    // strings double the backslash.

    @Test
    @DisplayName("a test annotation written with a Unicode escape is a test JUnit runs, and its absence fails the job")
    void aTestAnnotationWrittenWithAUnicodeEscapeIsRequired() throws Exception {
        sourceText(FOO, "    @Test\n    void decoy() { }\n    @\\u0054est\n    void guard() { }\n");
        ran(FOO, 0, "decoy");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test annotation written with an escape went unrequired");
        assertTrue(outcome.output().contains("1 of the 2 test methods it declares did not run: FooTest.guard "),
                "the refusal does not name the escaped test: " + outcome.output());
    }

    @Test
    @DisplayName("a method name written with a Unicode escape is the name javac reads and surefire reports")
    void aMethodNameWrittenWithAUnicodeEscapeIsTheNameJavaReads() throws Exception {
        sourceText(FOO, "    @Test\n    void g\\u0075ard() { }\n");
        ran(FOO, 0, "guard");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a method named with an escape was read by its raw text: " + outcome.output());
    }

    @Test
    @DisplayName("a backslash right before an escape's backslash keeps it text, so nothing is translated or refused")
    void aBackslashBeforeAnEscapeKeepsItText() throws Exception {
        // \\u000a with two backslashes is text: the second one has an odd number of backslashes before it.
        sourceText(FOO, "    // written out: \\\\u000a\n    @Test\n    void own() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "an escaped backslash before u was read as an escape: " + outcome.output());
    }

    @Test
    @DisplayName("a Unicode escape of a line terminator or a backslash is refused by name, at its line")
    void anEscapedLineTerminatorOrBackslashIsRefusedByName() throws Exception {
        // The one ends a // comment and moves every line after it; the other changes which backslash begins the next
        // escape. Neither is modelled.
        sourceText(FOO, "    // a \\u000a @Test void hidden() { }\n    @Test\n    void own() { }\n    /* \\u005c */\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "an escaped line terminator and an escaped backslash passed");
        assertTrue(outcome.output().contains("cannot judge 2 shape(s)")
                        && outcome.output().contains("(\\u000a) at line 6")
                        && outcome.output().contains("(\\u005c) at line 9"),
                "the refusal does not name both escapes at their lines: " + outcome.output());
    }

    @Test
    @DisplayName("a carriage return alone ends a line, so a // comment does not run on over the tests after it")
    void aCarriageReturnAloneEndsALine() throws Exception {
        sourceText(FOO, "    // note\r    @Test\r    void hidden() { }\r    @Test\n    void own() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test after a carriage-return line went unrequired");
        assertTrue(outcome.output().contains(": FooTest.hidden "), "the refusal does not name the test after the comment: "
                + outcome.output());
    }

    @Test
    @DisplayName("a test method named outside ASCII is required, and its absence fails the job")
    void aTestMethodNamedOutsideAsciiIsRequired() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    @Test\n    void 確かめる() { }\n    @Test\n    void €cost() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "tests named outside ASCII went unrequired");
        assertTrue(outcome.output().contains("2 of the 3 test methods it declares did not run: FooTest.€cost, "
                + "FooTest.確かめる "), "the refusal does not name both tests: " + outcome.output());
    }

    @Test
    @DisplayName("a @Nested class named outside ASCII is a class of the lock, judged by its own tests")
    void aNestedClassNamedOutsideAsciiIsJudged() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    class 内側 {\n        @Test\n        void x() { }\n    }\n");
        report("TEST-" + FOO + "$内側.xml", "name=\"" + FOO + "$内側\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$内側2.xml", "name=\"" + FOO + "$内側\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$内側", "x");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a @Nested class named outside ASCII was not read as a class: " + outcome.output());
    }

    @Test
    @DisplayName("an annotation named outside ASCII, or with a space after its @, is passed over to the method's name")
    void anAnnotationNamedOutsideAsciiIsPassedOverToTheMethod() throws Exception {
        sourceText(FOO, "    @Test\n    @ DisplayName(\"y\")\n    @注記(\"x\")\n    void own() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "an annotation's name was read as the method's: " + outcome.output());
    }

    @Test
    @DisplayName("a test annotation followed by nothing read as a method name is refused by name, not skipped")
    void aTestAnnotationOnNoMethodIsRefusedByName() throws Exception {
        // Skipped, it was the one way the check said nothing about a test annotation it had found (closing batch 12
        // review, subagent P2-1).
        sourceText(FOO, "    @Test\n    void own() { }\n    @Test\n    @interface Composed { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test annotation on no method was skipped");
        assertTrue(outcome.output().contains("the test annotation at line 8 is followed by nothing this check reads as a "
                + "method name"), "the refusal does not name the annotation's line: " + outcome.output());
    }

    @Test
    @DisplayName("a lambda's parameter named record is not a record declaration, so the body after it is not a class")
    void aLambdaParameterNamedRecordIsNotAClass() throws Exception {
        // What follows class, interface, enum or record is a declaration only when it is a Java identifier: `record ->`
        // names none, and taken as one the anonymous class after it was read as a member class of the lock.
        sourceText(FOO, "    @Test\n    void own() { }\n"
                + "    java.util.function.Function<Object, Object> f = record -> new Object() {\n        @Test\n"
                + "        void ghost() { }\n    };\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test in an anonymous class passed");
        assertTrue(outcome.output().contains("ghost() at line 9 is inside a method body or an anonymous class"),
                "the refusal does not say the test is in an anonymous class: " + outcome.output());
    }

    @Test
    @DisplayName("a test whose annotations run past any window is still a declared test, and its absence fails the job")
    void aTestWhoseAnnotationsRunPastAnyWindowIsRequired() throws Exception {
        // A 4,000-character window ran out inside a long @CsvSource, so the method went unread (closing batch 12 review,
        // subagent P2-1).
        sourceText(FOO, "    @ParameterizedTest\n    @CsvSource({\n" + "        \"row\",\n".repeat(600) + "    })\n"
                + "    void big(String s) { }\n    @Test\n    void own() { }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a test behind a long annotation went unrequired");
        assertTrue(outcome.output().contains(": FooTest.big "), "the refusal does not name the long-annotated test: "
                + outcome.output());
    }

    @Test
    @DisplayName("a lock with several shapes this check cannot judge names every one of them")
    void aLockWithSeveralShapesNamesEach() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    class Inner {\n        @Test\n        void a() { }\n    }\n"
                + "    interface Contract {\n        @Test\n        default void b() { }\n    }\n");
        ran(FOO, 0, "own");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "two shapes this check cannot judge passed");
        assertTrue(outcome.output().contains("cannot judge 2 shape(s)")
                        && outcome.output().contains("a() at line 9 is in FooTest.Inner")
                        && outcome.output().contains("b() at line 13 is in FooTest.Contract"),
                "the refusal does not name both shapes: " + outcome.output());
    }

    @Test
    @DisplayName("a type parameter's bound is not inheritance, so the class is judged")
    void aTypeParameterBoundIsNotInheritance() throws Exception {
        sourceText(FOO, "    @Test\n    void own() { }\n    static class Base { }\n    @Nested\n    class Impl<T extends Base> {\n"
                + "        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertEquals(0, outcome.exit(), "a type parameter's bound was read as inheritance: " + outcome.output());
    }

    @Test
    @DisplayName("a superclass outside the file is refused by name: a test it holds would run under the class's name")
    void aSuperclassOutsideTheFileIsRefusedByName() throws Exception {
        // Its tests are reported under the running class's name, so one of the same name stood in for a test declared
        // here that a tag took out (closing batch 12 review, subagent P3-1).
        sourceText(FOO, "    @Test\n    void own() { }\n    @Nested\n    class Impl extends java.util.ArrayList<String> {\n"
                + "        @Test\n        void mine() { }\n    }\n");
        report("TEST-" + FOO + "$Impl.xml", "name=\"" + FOO + "$Impl\" tests=\"2\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO, "own");
        report("TEST-" + FOO + "$Impl2.xml", "name=\"" + FOO + "$Impl\" tests=\"0\" skipped=\"0\" errors=\"0\" "
                + "failures=\"0\"", FOO + "$Impl", "mine");
        Outcome outcome = run(FOO);
        assertRefused(outcome, FOO, "a class inheriting from outside the file passed");
        assertTrue(outcome.output().contains("FooTest.Impl (line 9) extends or implements ArrayList, so a test it inherits"),
                "the refusal does not name the class and what it inherits: " + outcome.output());
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
