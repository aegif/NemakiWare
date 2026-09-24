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
        // Comments are dropped for the same reason the boundary was tightened: a sentence ABOUT
        // the job is not the job. Trailing comments go too — dropping only whole-line ones left
        // `... # nemaki.test.couchdb.required=true` able to satisfy an assertion below (Codex
        // review, P2). The `#` is taken as a comment start only with whitespace before it, so a
        // `#` with no whitespace before it — a URL fragment like `…/x#y` — is left alone. A `#`
        // AFTER whitespace inside a quoted string would be cut, which is not YAML's rule; the
        // job has no such string and this is not a YAML parser.
        StringBuilder running = new StringBuilder();
        for (String line : yaml.substring(from, end).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            int hash = line.indexOf(" #");
            running.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
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
    @DisplayName("the canon's control count is the runner's")
    void theRecordedNumbersAreTheRealOnes() throws IOException {
        // Two numbers this batch got wrong by hand. The control count went into the canon as
        // 791 when the file held 793 — read off "791 not measured by this run" without adding
        // the two that were. And the allow-list widening covered the class that noticed the
        // problem while leaving its siblings out (both round-3 reviews).
        // Existence checked first, for the reason the same batch added it elsewhere: a moved or
        // renamed file throws NoSuchFileException, which the control runner scores as "fired for
        // the wrong reason" rather than as this lock doing its job (subagent review, P3).
        Path runnerFile = Path.of("../tools/negative-controls/run_negative_controls.py");
        Path canonFile = Path.of("../docs/design/fail-closed-reads.md");
        for (Path input : List.of(runnerFile, canonFile)) {
            assertTrue(Files.exists(input),
                    "this lock reads " + input + ", which is not there — it would fail as a "
                            + "missing file rather than as a disagreement about the numbers");
        }
        String runner = Files.readString(runnerFile, StandardCharsets.UTF_8);
        int declared = 0;
        Matcher ids = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(runner);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (ids.find()) {
            declared++;
            assertTrue(seen.add(ids.group(1)), "control id " + ids.group(1) + " is declared twice");
        }

        String canon = Files.readString(canonFile, StandardCharsets.UTF_8);
        Matcher recorded = Pattern.compile("コントロール \\*\\*(\\d+)\\*\\*").matcher(canon);
        assertTrue(recorded.find(), "the canon does not state a control count");
        assertEquals(declared, Integer.parseInt(recorded.group(1)),
                "the canon says " + recorded.group(1) + " controls and the runner declares "
                        + declared + ". A count nobody checks is how a retired or a missing "
                        + "control reads as a known rounding difference");


        // The other number in the same sentence. "N added since the fourth sweep" is
        // total − 704 by construction, and it was hand-written beside a hand-written total —
        // so a control could be added, the total corrected, and this one left behind, which is
        // how the paragraph's three figures came to disagree (subagent review, P2).
        // The ledger's job is to say WHICH controls have never been run together, so what has
        // to agree is the SET, not a derived number. Three rounds of review found the three
        // figures disagreeing with each other and with the list beside them — and an arithmetic
        // check passed while the list was short by one, because it never read the list.
        //
        // 704 + 106 does not equal 805, and cannot: two controls were added after the sweep and
        // then retired. So nothing here derives one figure from the others. What is checked is
        // that the enumeration covers exactly the controls declared from CK3 onward.
        // The set that has NEVER run together is "everything added after the last FULL sweep".
        // Until the fifth sweep that was "CK3 onward"; the fifth ran all 938, so today it is
        // empty and the boundary is the highest id that existed then (LM3). The canon names the
        // boundary and the count; both are checked against the runner, and the count is also
        // cross-checked against the sweep record itself (declared − swept), so an id added
        // BELOW the boundary — invisible to the id filter — still shows up as a mismatch.
        int sweptThen = 0;
        Matcher sweeps = Pattern.compile("(\\d+) 回目 20\\d\\d-\\d\\d-\\d\\d[^（]*（(\\d+) 本").matcher(canon);
        int latestRound = 0;
        while (sweeps.find()) {
            if (Integer.parseInt(sweeps.group(1)) >= latestRound) {
                latestRound = Integer.parseInt(sweeps.group(1));
                sweptThen = Integer.parseInt(sweeps.group(2));
            }
        }
        assertTrue(latestRound > 0, "the canon records no completed sweep");
        // EVERY statement of the boundary. It is written in §1 and in §5, and reading only the
        // first let the second drift unread — the same one-arm defect as the counts.
        Matcher boundary = Pattern.compile("境界 ([A-Z]{2}3)").matcher(canon);
        String from = null;
        int boundaries = 0;
        while (boundary.find()) {
            boundaries++;
            if (from == null) {
                from = boundary.group(1);
            }
            assertEquals(from, boundary.group(1), "the canon names the sweep boundary as " + from
                    + " in one place and " + boundary.group(1) + " in another");
        }
        assertTrue(boundaries >= 2, "the canon used to name the sweep boundary in more than one "
                + "place and now names it " + boundaries + " time(s)");
        // The "3" generation above the boundary, and the whole "4" generation that follows it
        // (AA4 came after ZZ3; every "4" id is newer than any "3" id, whatever the letters say),
        // in that order: the generation digit first, then the letters.
        java.util.Comparator<String> byGeneration = java.util.Comparator
                .comparing((String id) -> id.charAt(2)).thenComparing(id -> id.substring(0, 2));
        java.util.SortedSet<String> unswept = new java.util.TreeSet<>(byGeneration);
        for (String id : seen) {
            if ((id.compareTo(from) > 0 && id.matches("[A-Z]{2}3")) || id.matches("[A-Z]{2}4")) {
                unswept.add(id);
            }
        }
        assertEquals(declared - sweptThen, unswept.size(),
                "the runner declares " + declared + " controls, the last full sweep ran "
                        + sweptThen + ", and " + unswept.size() + " ids sit above the boundary "
                        + from + ". A control added with an id below the boundary would be "
                        + "unswept and invisible to the boundary filter");

        // EVERY statement of both counts, in EVERY document that carries them. The counts live
        // in three files and this lock read one; the plan sat at "CK3 以降の 110 本" while the
        // real figure was 219, so whoever scoped the overdue sweep from the plan would have run
        // half the set. Per-file locks cannot hold a claim that crosses files (both reviews, P2).
        List<Path> carriers = List.of(canonFile,
                Path.of("../docs/design/v3.4-release-readiness.md"),
                Path.of("../docs/design/v3.4.0-evidence-and-residuals-plan.md"));
        // Phrasings, by meaning. Each is a way one of these documents states one of the two
        // numbers; the minimum counts below catch a rephrase that escapes them all.
        // TWO numbers, two families. "The total today" and "what the last sweep ran" were one
        // family until the fifth sweep made them differ by design: the moment a control is
        // added, the sweep count stays 938 and the total moves. One family would fail on the
        // historical sentence; no family would let it go stale.
        List<String> totalForms = List.of("コントロール \\*\\*(\\d+)\\*\\*",
                "負のコントロール \\*\\*(\\d+) 本\\*\\*",
                "(?m)^\\| 通し negative-control \\|[^|]*\\| (\\d+) 本 \\|");
        List<String> sweptForms = List.of("通し negative-control は (\\d+) 本で完走した",
                "(\\d+) 本すべて", "「(\\d+) 本が通った」", "\\*\\*(\\d+)/\\d+ 発火");
        // Bold either way: the number alone (**1 本**) or the whole phrase (**…は 1 本**) —
        // the documents use both, and a regex that accepted one silently dropped the other
        // two sites out of reach (the guard below is what caught it).
        List<String> unsweptForms = List.of("以後に足した control は \\*{0,2}(\\d+) 本");

        int totalsSeen = 0;
        int sweptSeen = 0;
        int unsweptSeen = 0;
        for (Path carrier : carriers) {
            assertTrue(Files.exists(carrier), "this lock reads " + carrier + ", which is not there");
            String text = Files.readString(carrier, StandardCharsets.UTF_8);
            for (String form : sweptForms) {
                Matcher m = Pattern.compile(form).matcher(text);
                while (m.find()) {
                    sweptSeen++;
                    assertEquals(sweptThen, Integer.parseInt(m.group(1)),
                            carrier + " says the last sweep ran " + m.group(1) + " and the "
                                    + "sweep record says " + sweptThen);
                }
            }
            for (String form : totalForms) {
                Matcher m = Pattern.compile(form).matcher(text);
                while (m.find()) {
                    totalsSeen++;
                    assertEquals(declared, Integer.parseInt(m.group(1)),
                            carrier + " states the control total as " + m.group(1)
                                    + " and the runner declares " + declared
                                    + ". A reader takes whichever document they open");
                }
            }
            for (String form : unsweptForms) {
                Matcher m = Pattern.compile(form).matcher(text);
                while (m.find()) {
                    unsweptSeen++;
                    assertEquals(unswept.size(), Integer.parseInt(m.group(1)),
                            carrier + " states the never-swept count as " + m.group(1)
                                    + " and the runner declares " + unswept.size()
                                    + ". This is the number the next full sweep is scoped from");
                }
            }
        }
        assertTrue(totalsSeen >= 3, "the three documents used to state the control total at "
                + "least three times between them and now state it " + totalsSeen + " time(s). "
                + "Either a statement went away or its wording drifted out of this check's "
                + "reach, which is exactly how the plan's copy went stale");
        assertTrue(sweptSeen >= 5, "the three documents used to state what the last sweep ran "
                + "at least five times and now state it " + sweptSeen + " time(s)");
        assertTrue(unsweptSeen >= 4, "the three documents used to state the added-since-sweep "
                + "count at least four times between them and now state it " + unsweptSeen
                + " time(s), so a statement has drifted out of reach");

        // The enumeration is required only while something is unswept. With the set empty a
        // demanded list would have to be invented, and an invented list is what the ledger
        // exists to prevent. When it is non-empty it must name exactly the set — both
        // directions, ranges expanded, retired ids the one thing allowed in the list and not
        // in the runner.
        if (!unswept.isEmpty()) {
            int listStart = canon.indexOf("（" + unswept.first());
            assertTrue(listStart >= 0, "controls were added after the last full sweep and the "
                    + "canon does not enumerate them starting at " + unswept.first()
                    + ". Unlisted, they read as already swept: " + unswept);
            int listEnd = canon.indexOf("）は", listStart);
            assertTrue(listEnd > listStart, "the ledger's enumeration does not end where this looks");
            String ledger = canon.substring(listStart, listEnd);
            java.util.Set<String> named = new java.util.LinkedHashSet<>();
            Matcher single = Pattern.compile("\\b([A-Z]{2}[34])\\b").matcher(ledger);
            while (single.find()) {
                named.add(single.group(1));
            }
            Matcher range = Pattern.compile("([A-Z]{2}[34])〜([A-Z]{2}[34])").matcher(ledger);
            while (range.find()) {
                for (String id : unswept) {
                    if (byGeneration.compare(id, range.group(1)) >= 0 && byGeneration.compare(id, range.group(2)) <= 0) {
                        named.add(id);
                    }
                }
            }
            java.util.SortedSet<String> namedButAbsent = new java.util.TreeSet<>(named);
            namedButAbsent.removeAll(seen);
            namedButAbsent.removeAll(java.util.Set.of("DG3", "DJ3"));
            assertTrue(namedButAbsent.isEmpty(),
                    "the ledger names a control the runner does not declare: " + namedButAbsent);
            java.util.SortedSet<String> unnamed = new java.util.TreeSet<>(unswept);
            unnamed.removeAll(named);
            assertTrue(unnamed.isEmpty(),
                    "a control is unswept and is not named in the canon's list, so the next full "
                            + "sweep would treat it as already covered: " + unnamed);
        }

    }

    @Test
    @DisplayName("the workflow starts for changes to the files these locks read")
    void theGateRunsForTheFilesItGuards() throws IOException {
        // Three paths were added because a check that never starts is a check that never gates
        // (Codex, round 3). Nothing then measured the three lines — so the same hole could be
        // reopened, one line at a time, in the file that fixed it (subagent review, P2).
        String yaml = workflow();
        for (String needed : List.of(
                "core/src/main/webapp/ui/src/**",      // the screens a lock reads
                "tools/negative-controls/**",          // the controls this class counts
                "docs/design/fail-closed-reads.md")) { // the ledger it compares them with
            int occurrences =
                    yaml.split(java.util.regex.Pattern.quote("'" + needed + "'"), -1).length - 1;
            assertEquals(2, occurrences,
                    "'" + needed + "' should appear in BOTH the push and pull_request paths of "
                            + WORKFLOW + " and appears " + occurrences + " time(s). A change to "
                            + "it would not start the workflow that checks it");
        }
    }

    @Test
    @DisplayName("each matrix entry actually starts THAT version of CouchDB")
    void theMatrixVersionReachesTheContainer() throws IOException {
        // Without this the matrix is three runs of whatever image is hardcoded: all three jobs
        // go green, the unit lock above still matches the three tags, and EV3 through EZ3 keep
        // firing — while 3.4 and 3.5 are never started (Codex review, P2).
        String job = measuringJob();

        assertTrue(job.contains("image: couchdb:${{ matrix.couchdb }}"),
                "the service container does not take its image from the matrix, so the three "
                        + "entries do not measure three versions:\n" + job);
    }

    @Test
    @DisplayName("nothing in the measuring job lets a failure pass for a success")
    void theMeasuringJobCannotPassWhileSkipping() throws IOException {
        // A job or step that is conditioned off, or allowed to fail, reports green having
        // measured nothing — the same defect as an IT that assumes past an unreachable store,
        // one level up (subagent review, P3).
        String job = measuringJob();

        assertFalse(job.contains("continue-on-error: true"),
                "a step in the measuring job may fail without failing the job:\n" + job);
        assertFalse(job.contains("if: false"),
                "a step in the measuring job is switched off:\n" + job);
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
    @DisplayName("a failed attachment read is not the finding that the binary is gone")
    void onlyA404EstablishesThatTheBinaryWent() {
        // The measuring instrument's own version of this branch's defect: StoreBehaviourFactsIT
        // recorded EVERY exception from the attachment read as "the binary is gone", which is
        // the value the deletion fact expects — so a transient 500 confirmed the fact (Codex
        // review, P1). The classification lives here, apart from the read, because the IT does
        // not run without a store: an inline catch could be reverted and every gate stay green.
        // Mocked: these SDK exceptions read an okhttp Response in their constructor, which a
        // unit test has no business building.
        assertTrue(StoreBehaviourFacts.readEstablishesTheBinaryIsGone(
                        org.mockito.Mockito.mock(
                                com.ibm.cloud.sdk.core.service.exception.NotFoundException.class)),
                "a 404 is the store ANSWERING that the attachment is not there");

        for (Throwable notAnAnswer : List.<Throwable>of(
                new java.io.IOException("connection reset"),
                new RuntimeException("something else"),
                org.mockito.Mockito.mock(
                        com.ibm.cloud.sdk.core.service.exception.BadRequestException.class))) {
            assertFalse(StoreBehaviourFacts.readEstablishesTheBinaryIsGone(notAnAnswer),
                    notAnAnswer + " was read as the store saying the binary is gone");
        }
    }

    @Test
    @DisplayName("allow_fallback has three answers, and a failed query is the third")
    void theFallbackVerdictKeepsTheThirdOutcome() {
        // Same shape: every RuntimeException that was not invalid_key counted as "the parameter
        // was honoured", so a connection reset became evidence about CouchDB's behaviour.
        assertEquals(StoreBehaviourFacts.FallbackVerdict.NOT_ESTABLISHED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(
                        new RuntimeException("connection reset")),
                "a query that never reached a verdict was counted as one");
        assertEquals(StoreBehaviourFacts.FallbackVerdict.NOT_ESTABLISHED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(
                        new java.io.IOException("socket closed")));

        // The other two arms, on the codes live servers actually return (measured: 3.3.3 says
        // invalid_key, 3.4.3 says invalid_index).
        assertEquals(StoreBehaviourFacts.FallbackVerdict.REJECTED_AS_UNKNOWN_KEY,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(badRequest("invalid_key")),
                "the code the store returns when it does not know the parameter");
        assertEquals(StoreBehaviourFacts.FallbackVerdict.HONOURED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(badRequest("invalid_index")),
                "the code the store returns when it acted on the parameter");
        assertEquals(StoreBehaviourFacts.FallbackVerdict.HONOURED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(badRequest("no_usable_index")),
                "the same answer with no use_index given");

        // Both directions of the misclassification that free-text matching produced.
        assertEquals(StoreBehaviourFacts.FallbackVerdict.NOT_ESTABLISHED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(
                        badRequest("invalid_selector")),
                "a 400 about the selector answers a different question — and its REASON can "
                        + "contain the word index, which is how substring matching read it as "
                        + "an answer to this one");
        assertEquals(StoreBehaviourFacts.FallbackVerdict.NOT_ESTABLISHED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(badRequest("something_new")),
                "an unknown code establishes nothing rather than defaulting to an answer");
        assertEquals(StoreBehaviourFacts.FallbackVerdict.NOT_ESTABLISHED,
                StoreBehaviourFacts.classifyAllowFallbackRefusal(badRequest(null)),
                "a refusal carrying no code at all is not a verdict");
    }

    /** A 400 whose body carries the given CouchDB error code, as the SDK exposes it. */
    private static Throwable badRequest(String errorCode) {
        com.ibm.cloud.sdk.core.service.exception.BadRequestException thrown =
                org.mockito.Mockito.mock(
                        com.ibm.cloud.sdk.core.service.exception.BadRequestException.class);
        org.mockito.Mockito.when(thrown.getDebuggingInfo()).thenReturn(
                errorCode == null ? java.util.Map.of()
                        : java.util.Map.of("error", errorCode, "reason",
                                "property 'index' is malformed"));
        return thrown;
    }


    @Test
    @DisplayName("every fact points at code that is still where it says, LINES included")
    void everyFactPointsAtItsPremise() throws IOException {
        // The file half used to be all this checked, and it said so honestly — which left the
        // line numbers free to rot. Four of the seven were wrong the day they were written, and
        // a review found two more after those were corrected. Each fact now carries an ANCHOR:
        // an identifier that has to appear inside the range it cites.
        List<String> wrong = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            assertFalse(fact.premise().isBlank(), fact + " states no premise");
            assertFalse(fact.anchor().isBlank(), fact + " names no anchor");

            for (String citation : fact.where().split(" and ")) {
                String trimmed = citation.trim();
                String file = trimmed.contains(":")
                        ? trimmed.substring(0, trimmed.indexOf(':')) : trimmed;
                Path source = Path.of("..").resolve(file.isEmpty()
                        ? lastFileOf(fact.where()) : file);
                if (!Files.exists(source)) {
                    wrong.add(fact.name() + " -> " + source + " (no such file)");
                    continue;
                }
                    int[] range = lineRange(trimmed);
                // REQUIRED, not optional. Skipping a citation with no line numbers meant the
                // easiest way to silence this lock when a refactor moved code was to delete the
                // numbers — which restores exactly the file-only check it replaced (subagent
                // review, P2). All seven carry a range today, so requiring one refuses nothing.
                assertTrue(range != null,
                        fact.name() + " cites " + trimmed + " with no line numbers, so nothing "
                                + "checks that the premise is still there");
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                // Comments are KEPT, deliberately. A review suggested dropping them — "a sentence
                // about the code is not the code" — and it is right for the facts whose premise
                // is a call. But two of these premises ARE javadoc: the allow_fallback version
                // boundary is a claim about versions, made in prose, and this branch counts such
                // a claim as equal to a line of code. Stripping comments made those two facts
                // uncheckable (measured: the lock went red on them immediately).
                String cited = String.join("\n", lines.subList(
                        Math.max(0, range[0] - 1), Math.min(lines.size(), range[1])));
                // A citation that names its own method in parentheses — ":1197 (putBack, …)" —
                // is checked against THAT name. The fact's anchor covers the ones that do not.
                String expected = anchorOf(trimmed, fact.anchor());
                // Word-bounded: plain contains() let `getDoc` be satisfied by a `getDocument`
                // call sitting in the range, so the pointer could rot to a different method and
                // stay green (Codex review, P2).
                if (!Pattern.compile("\\b" + Pattern.quote(expected) + "\\b").matcher(cited).find()) {
                    wrong.add(fact.name() + " cites " + trimmed + ", which does not contain "
                            + expected);
                }
            }
        }

        assertTrue(wrong.isEmpty(), "a fact points at code that is not there, so a reader "
                + "following it lands somewhere unrelated — which is the failure R7 itself "
                + "describes:\n  " + String.join("\n  ", wrong));
    }

    /** The identifier a single citation must contain: its own parenthetical, or the fact's. */
    private static String anchorOf(String citation, String fallback) {
        Matcher named = Pattern.compile("\\((\\w+)[,)]").matcher(citation);
        return named.find() ? named.group(1) : fallback;
    }

    /** The file named by the first citation, for a follow-on ":123" that omits it. */
    private static String lastFileOf(String where) {
        String first = where.split(" and ")[0].trim();
        return first.contains(":") ? first.substring(0, first.indexOf(':')) : first;
    }

    /** The {@code :from-to} or {@code :line} in one citation, or null when it has none. */
    private static int[] lineRange(String citation) {
        Matcher span = Pattern.compile(":(\\d+)-(\\d+)").matcher(citation);
        if (span.find()) {
            return new int[] {Integer.parseInt(span.group(1)), Integer.parseInt(span.group(2))};
        }
        Matcher one = Pattern.compile(":(\\d+)").matcher(citation);
        if (one.find()) {
            int line = Integer.parseInt(one.group(1));
            return new int[] {line, line};
        }
        return null;
    }

}
