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
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The unit job fails unless each lock it names RAN — a report, with tests, none skipped (R181).
 *
 * <p>Drives {@code tools/ci/named-locks-ran.sh}, the script the job runs after the suite, with surefire report
 * directories written here: the shape a silenced lock leaves (no report — an {@code @Tag} in the excluded group, a
 * pattern, {@code -Dtest}, a rename), a report with no tests, a report with a skipped test. Before R181 the job
 * read the selection instead, one way of silencing at a time, and a lock tagged {@code atlas-integration} passed
 * every check while not running (measured 2026-10-27: {@code mvn test} exits 0 with the tag on, and this script
 * exits 1). Then it reads the workflow, because a script nothing calls checks nothing.
 */
class EveryNamedLockRanTest {

    private static final Path SCRIPT = Path.of("../tools/ci/named-locks-ran.sh");
    private static final Path WORKFLOW = Path.of("../.github/workflows/integration-tests.yml");

    @TempDir
    Path reports;

    private record Outcome(int exit, String output) { }

    private Outcome run(String... locks) throws IOException, InterruptedException {
        return runOn(reports, locks);
    }

    private Outcome runOn(Path directory, String... locks) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString(), directory.toString()));
        command.addAll(List.of(locks));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script did not finish");
        return new Outcome(process.exitValue(), output);
    }

    private void report(String fileName, String testsuiteAttributes) throws IOException {
        Files.writeString(reports.resolve(fileName), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " + testsuiteAttributes + ">\n"
                + "</testsuite>\n", StandardCharsets.UTF_8);
    }

    private void ran(String lock, int tests, int skipped) throws IOException {
        report("TEST-jp.aegif.nemaki.x." + lock + ".xml", "name=\"jp.aegif.nemaki.x." + lock + "\" time=\"0.1\" tests=\""
                + tests + "\" errors=\"0\" skipped=\"" + skipped + "\" failures=\"0\"");
    }

    private void assertRefused(Outcome outcome, String lock, String why) {
        assertEquals(1, outcome.exit(), why + " — and the job's check passed it: " + outcome.output());
        assertTrue(outcome.output().contains("::error::" + lock), "the refusal does not name " + lock + ": "
                + outcome.output());
    }

    @Test
    @DisplayName("every named lock with a report, tests and none skipped passes")
    void locksThatRanPass() throws Exception {
        ran("FooTest", 3, 0);
        ran("BarTest", 1, 0);
        Outcome outcome = run("FooTest", "BarTest");
        assertEquals(0, outcome.exit(), "two locks that ran were refused: " + outcome.output());
    }

    @Test
    @DisplayName("a lock with no report — tagged into the excluded group, excluded by a pattern, renamed — fails the job")
    void aLockWithNoReportFails() throws Exception {
        ran("FooTest", 3, 0);
        assertRefused(run("FooTest", "BarTest"), "BarTest", "BarTest has no report, so it did not run");
    }

    @Test
    @DisplayName("a lock whose report counted no tests fails the job")
    void aLockThatRanNoTestsFails() throws Exception {
        ran("FooTest", 0, 0);
        assertRefused(run("FooTest"), "FooTest", "FooTest's report counts no tests");
    }

    @Test
    @DisplayName("a lock with a skipped test fails the job — @Disabled on a method, an assumption that did not hold")
    void aLockWithASkippedTestFails() throws Exception {
        ran("FooTest", 5, 1);
        assertRefused(run("FooTest"), "FooTest", "FooTest skipped one of its tests");
    }

    @Test
    @DisplayName("a nested class's report counts for its lock, skipped tests included")
    void aNestedClassCountsForItsLock() throws Exception {
        report("TEST-jp.aegif.nemaki.x.FooTest$Inner.xml", "name=\"jp.aegif.nemaki.x.FooTest$Inner\" tests=\"2\" "
                + "skipped=\"0\" errors=\"0\" failures=\"0\"");
        Outcome onlyNested = run("FooTest");
        assertEquals(0, onlyNested.exit(), "a lock whose tests are all in a nested class was refused: "
                + onlyNested.output());
        report("TEST-jp.aegif.nemaki.x.FooTest$Other.xml", "name=\"jp.aegif.nemaki.x.FooTest$Other\" tests=\"2\" "
                + "skipped=\"2\" errors=\"0\" failures=\"0\"");
        assertRefused(run("FooTest"), "FooTest", "a nested class of FooTest skipped its tests");
    }

    @Test
    @DisplayName("a report for a longer class name is not the lock's")
    void aReportForALongerNameIsNotTheLocks() throws Exception {
        ran("FooTestExtra", 4, 0);
        ran("PreFooTest", 4, 0);
        assertRefused(run("FooTest"), "FooTest", "only FooTestExtra and PreFooTest ran, not FooTest");
    }

    @Test
    @DisplayName("a report that does not state tests and skipped cannot show the lock ran")
    void anUnreadableReportFails() throws Exception {
        ran("FooTest", 3, 0);
        report("TEST-jp.aegif.nemaki.x.FooTest$Inner.xml", "name=\"jp.aegif.nemaki.x.FooTest$Inner\" errors=\"0\"");
        assertRefused(run("FooTest"), "FooTest", "one of FooTest's reports states no counts");
    }

    @Test
    @DisplayName("no report directory at all fails the job")
    void noReportDirectoryFails() throws Exception {
        Outcome outcome = runOn(reports.resolve("not-there"), "FooTest");
        assertEquals(1, outcome.exit(), "no surefire reports at all, and the check passed: " + outcome.output());
    }

    @Test
    @DisplayName("the unit job runs the check on the locks it names, from the one list both of its checks read")
    void theUnitJobChecksTheLocksItNames() throws IOException {
        String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        int start = workflow.indexOf("\n  unit-tests:\n");
        assertTrue(start >= 0, "the workflow has no unit-tests job");
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
            try (Stream<Path> found = Files.walk(Path.of("src/test/java"))) {
                assertTrue(found.anyMatch(p -> p.getFileName().toString().equals(lock + ".java")),
                        "the unit job names " + lock + ", which is not a test class here");
            }
        }
        assertTrue(job.contains("\n          LOCKS=\"$NAMED_LOCKS\"\n"),
                "the check before the run does not read the locks from NAMED_LOCKS, so the two checks can name "
                        + "different locks");
        int ranStep = job.indexOf("- name: The suite actually ran");
        assertTrue(ranStep >= 0, "the unit job has no step after the run that reads what ran");
        int stepEnd = job.indexOf("\n      - ", ranStep + 1);
        String step = stepEnd > 0 ? job.substring(ranStep, stepEnd) : job.substring(ranStep);
        assertTrue(step.contains("\n          bash tools/ci/named-locks-ran.sh \"$REPORTS\" $NAMED_LOCKS\n"),
                "the step after the run does not check that each named lock ran");
        assertFalse(step.contains("|| true"), "the step after the run lets a failure through");
    }
}
