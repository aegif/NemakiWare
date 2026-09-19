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
package jp.aegif.nemaki.dao.impl.couch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.aegif.nemaki.dao.impl.couch.StoreBehaviourFacts.Fact;
import jp.aegif.nemaki.dao.impl.couch.StoreBehaviourFacts.Line;
import jp.aegif.nemaki.init.CouchDbVersionRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * "Measured" means measured by CI on every supported CouchDB line — not once, by hand, on one
 * (R7, plan A-7: 「使い捨て 1 回では完了にしない」).
 *
 * <h2>What this keeps from happening</h2>
 *
 * <p>{@link StoreBehaviourFactsIT} is the measurement; this is what stops the measurement from
 * quietly covering less than it claims. Three ways that happens, all of them silent:
 *
 * <ul>
 *   <li>a line is declared in the table and no CI job runs it — the row reads as measured;</li>
 *   <li>the product's floor drops to admit a line nobody added — the store is supported and
 *       unmeasured, which is the state R7 recorded in the first place;</li>
 *   <li>the matrix runs but the IT is not required, so every job skips and every job is green.</li>
 * </ul>
 *
 * <p>This test runs in the ordinary unit suite, with no store, so those three are caught on every
 * build rather than on whatever day someone next reads the workflow.
 */
class EverySupportedCouchDbIsMeasuredTest {

    private static final Path WORKFLOW = Path.of("../.github/workflows/integration-tests.yml");

    /** The job that does the measuring. Named here once; every assertion below scopes to it. */
    private static final String JOB = "store-behaviour-facts:";

    private static String workflow() throws IOException {
        assertTrue(Files.exists(WORKFLOW),
                "the integration-tests workflow is not where this test looks: "
                        + WORKFLOW.toAbsolutePath());
        return Files.readString(WORKFLOW, StandardCharsets.UTF_8);
    }

    /**
     * The measuring job's block: from its own key to the next thing at the same indent.
     *
     * <p>Scoped deliberately — a match anywhere in a 400-line workflow would let an unrelated job
     * satisfy these assertions.
     *
     * <p>The boundary is any two-space-indented line, NOT the next {@code job:} key. Ending at the
     * next key swept in the COMMENT BLOCK that introduces the following job, and the job below
     * this one happens to explain itself with the words {@code nemaki.test.couchdb.required=true}
     * — so {@link #theMatrixIsNotDecorative} passed with this job's own {@code required} turned
     * off. Measured: control EW3 did not fire until the boundary was tightened.
     */
    private static String measuringJob() throws IOException {
        String yaml = workflow();
        int start = yaml.indexOf("\n  " + JOB);
        assertTrue(start >= 0, "no job named '" + JOB + "' in " + WORKFLOW + " — the supported "
                + "CouchDB lines are declared in StoreBehaviourFacts and measured by nothing");
        int from = start + 1;
        Matcher nextTopLevel = Pattern.compile("\\n  \\S").matcher(yaml);
        int end = yaml.length();
        if (nextTopLevel.find(from + 1)) {
            end = nextTopLevel.start();
        }
        // Comment lines are dropped for the same reason the boundary was tightened: a sentence
        // ABOUT the job is not the job. Every assertion below is about YAML that runs.
        StringBuilder running = new StringBuilder();
        for (String line : yaml.substring(from, end).split("\n", -1)) {
            if (!line.trim().startsWith("#")) {
                running.append(line).append('\n');
            }
        }
        return running.toString();
    }

    @Test
    @DisplayName("CI runs one job per supported CouchDB line, and only those")
    void theMatrixIsExactlyTheSupportedLines() throws IOException {
        String job = measuringJob();
        Matcher list = Pattern.compile("couchdb:\\s*\\[([^\\]]*)\\]").matcher(job);
        assertTrue(list.find(), "the measuring job has no `couchdb: [...]` matrix — one run on "
                + "one version is what A-7 rules out:\n" + job);
        String rest = job.substring(list.end());
        assertFalse(Pattern.compile("couchdb:\\s*\\[").matcher(rest).find(),
                "the measuring job declares more than one couchdb matrix; this test would only "
                        + "check the first");

        List<String> inCi = new ArrayList<>();
        for (String raw : list.group(1).split(",")) {
            String tag = raw.trim().replace("'", "").replace("\"", "");
            if (!tag.isEmpty()) {
                inCi.add(tag);
            }
        }

        assertEquals(StoreBehaviourFacts.ciImageTags(), inCi,
                "the CI matrix and StoreBehaviourFacts.SUPPORTED_LINES disagree. A line in the "
                        + "table that CI does not run is a premise nobody re-measures; a line CI "
                        + "runs that the table does not declare fails the IT on arrival");
    }

    @Test
    @DisplayName("the matrix actually runs the IT, and fails when the store is unreachable")
    void theMatrixIsNotDecorative() throws IOException {
        String job = measuringJob();

        assertTrue(job.contains("StoreBehaviourFactsIT"),
                "the measuring job does not run StoreBehaviourFactsIT:\n" + job);
        assertTrue(job.contains("nemaki.test.couchdb.required=true"),
                "the measuring job does not require the store. Without it the IT ASSUMES its way "
                        + "past an unreachable CouchDB and every matrix entry reports green — a "
                        + "read that never happened, reported as a read that found nothing:\n"
                        + job);
    }

    @Test
    @DisplayName("the declared lines start exactly at the product's floor")
    void theLowestDeclaredLineIsTheFloor() {
        List<Line> lines = StoreBehaviourFacts.SUPPORTED_LINES;
        assertFalse(lines.isEmpty(), "no CouchDB line is declared at all");

        for (Line line : lines) {
            assertTrue(CouchDbVersionRequirement.isSatisfiedBy(line.ciImageTag()),
                    "CouchDB " + line.ciImageTag() + " is declared as supported but the product "
                            + "REFUSES to start against it — measuring premises there says nothing "
                            + "about the versions we run on");
        }

        Line lowest = lines.get(0);
        for (Line line : lines) {
            if (line.major() < lowest.major()
                    || (line.major() == lowest.major() && line.minor() < lowest.minor())) {
                lowest = line;
            }
        }
        assertEquals(CouchDbVersionRequirement.MINIMUM, lowest.key(),
                "the floor is " + CouchDbVersionRequirement.MINIMUM + " and the lowest measured "
                        + "line is " + lowest.key() + ". Lower the floor and a line the product "
                        + "now accepts has no row; raise it and a row measures a store we refuse");
    }

    @Test
    @DisplayName("every fact is declared for every supported line, and for no other")
    void thereAreNoGapsAndNoStaleRows() {
        List<String> supported = new ArrayList<>();
        for (Line line : StoreBehaviourFacts.SUPPORTED_LINES) {
            supported.add(line.key());
        }

        for (Line line : StoreBehaviourFacts.SUPPORTED_LINES) {
            // assertDoesNotThrow, because expect() REFUSES an undeclared line rather than
            // defaulting. A bare call would fail this test with that exception instead of on its
            // own assertion, and the negative-control runner counts that as a broken harness.
            assertDoesNotThrow(() -> StoreBehaviourFacts.expectedOn(line),
                    "CouchDB " + line.key() + " is in the CI matrix but at least one fact says "
                            + "nothing about it");
        }

        List<String> stale = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            for (String declared : fact.declaredLines().keySet()) {
                if (!supported.contains(declared)) {
                    stale.add(fact.name() + " declares " + declared);
                }
            }
        }
        assertTrue(stale.isEmpty(), "a fact declares a line nothing measures — the row reads as "
                + "evidence and is not:\n  " + String.join("\n  ", stale));
    }

    @Test
    @DisplayName("a store the table says nothing about is REFUSED, not quietly placed")
    void anUndeclaredVersionIsRefused() {
        // The fail-closed half, and the only one that can be measured without a store. CouchDB
        // 4.x, and 3.6 the day it ships, are versions the product's floor ACCEPTS: it will start
        // against them. If lineOf answered with the nearest line, the IT would go on to measure
        // 3.5's expectations against a store nobody has ever run these premises on and report
        // them as met.
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> StoreBehaviourFacts.lineOf("4.0.0"),
                "a version above the floor with no row in the table was placed anyway");
        assertTrue(refused.getMessage().contains("4.0"), refused.getMessage());

        // The other direction, so this is not satisfied by a lineOf that refuses everything.
        Line line = assertDoesNotThrow(() -> StoreBehaviourFacts.lineOf("3.3.3"),
                "a declared version was refused");
        assertEquals("3.3", line.key());
    }

    @Test
    @DisplayName("a store BELOW the floor is refused for being unsupported, not placed at 3.3")
    void aVersionBelowTheFloorIsRefused() {
        // 3.2 is nearer to the 3.3 row than 4.0 is, and that is exactly the trap: "close enough"
        // would measure the premises on a store the product refuses to start against, then
        // report the result as if it said something about the ones it does.
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> StoreBehaviourFacts.lineOf("3.2.3"),
                "a version the product refuses to start against was accepted for measurement");
        assertTrue(refused.getMessage().contains("REFUSES"), refused.getMessage());
    }

    @Test
    @DisplayName("every fact names the code that relies on it, and that file exists")
    void everyFactPointsAtItsPremise() {
        // A premise register whose pointers rot becomes a list of opinions. The file half is
        // checkable here; the line numbers are not, and this does not pretend to check them.
        List<String> missing = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            assertFalse(fact.premise().isBlank(), fact + " states no premise");
            String where = fact.where();
            String file = where.contains(":") ? where.substring(0, where.indexOf(':')) : where;
            if (!Files.exists(Path.of("..").resolve(file))) {
                missing.add(fact.name() + " -> " + file);
            }
        }
        assertTrue(missing.isEmpty(), "a fact points at code that is no longer there:\n  "
                + String.join("\n  ", missing));
    }
}
