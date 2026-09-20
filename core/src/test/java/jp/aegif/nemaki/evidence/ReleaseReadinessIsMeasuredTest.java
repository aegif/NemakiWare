package jp.aegif.nemaki.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The progress documents state numbers. This measures them.
 *
 * <p>On 2026-09-20 the readiness document still read "処置済み 35 / 開いている 19 / 710 本"
 * while the canon held 43 closed, 20 open and the runner declared 813 controls. Nothing was
 * wrong with the tree; the summary of the tree was a day stale, and reading it gave the wrong
 * answer to "how much is left". A hand-written count beside a hand-written list is the shape
 * that has now drifted in this branch three times — the control ledger twice, this once — so
 * the rule here is the same one §5's ledger settled on: <b>compare the sets and the sources,
 * never derive one figure from another.</b>
 *
 * <p>Four things are measured, and each fails on its own assertion:
 *
 * <ol>
 * <li>the readiness document's residual counts are the canon's actual rows;
 * <li>its control count is what the runner declares;
 * <li>the open residuals it groups are exactly the canon's open rows, each in one group;
 * <li>a claim that is true only while something else is true — the Phase 0 gate, the frozen
 * residuals, the Track A rows, and the last completed sweep — says so in both documents, and
 * <b>stops</b> saying so when the condition lifts.
 * </ol>
 *
 * <p>That last direction is the one worth keeping. A lock that only demands "the document says
 * NOT MET" would hold that sentence in place after the gate is met, which is the same defect
 * pointed the other way: a document stronger, or weaker, than the tree.
 */
class ReleaseReadinessIsMeasuredTest {

    private static final Path CANON = Path.of("../docs/design/fail-closed-reads.md");
    private static final Path PLAN = Path.of("../docs/design/v3.4.0-evidence-and-residuals-plan.md");
    private static final Path READINESS = Path.of("../docs/design/v3.4-release-readiness.md");
    private static final Path SPEC = Path.of("../docs/design/evidence-profile-v1.md");
    private static final Path RUNNER = Path.of("../tools/negative-controls/run_negative_controls.py");

    /** The three residuals the owner froze on 2026-09-20. */
    private static final Set<String> FROZEN = Set.of("R61", "R62", "R63");

    private static final String FREEZE_MARK = "凍結（ユーザー指示 2026-09-20）";

    /**
     * Read, having first checked the file is there.
     *
     * <p>Existence first for the reason a sibling lock records: a moved or renamed file throws
     * {@link java.nio.file.NoSuchFileException}, which the control runner scores as "fired for
     * the wrong reason" rather than as this lock doing its job.
     */
    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path),
                "this lock reads " + path + ", which is not there — it would fail as a missing "
                        + "file rather than as a disagreement about the numbers");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The text between two markers.
     *
     * <p>Every grep here is scoped to the passage that makes the claim, not to the file. Three
     * times in this branch a file-wide search was satisfied by prose elsewhere in the same
     * document, so deleting the thing being checked changed nothing.
     */
    private static String slice(String text, String from, String to, String what) {
        int start = text.indexOf(from);
        assertTrue(start >= 0, what + " does not start where this lock looks (\"" + from + "\")");
        int end = text.indexOf(to, start + from.length());
        assertTrue(end > start, what + " does not end where this lock looks (\"" + to + "\")");
        return text.substring(start, end);
    }

    /** Residual ids in the canon's table, in file order. */
    private static List<String> residualRows(String canon) {
        List<String> ids = new java.util.ArrayList<>();
        Matcher rows = Pattern.compile("(?m)^\\| (R\\d+) \\|").matcher(canon);
        while (rows.find()) {
            ids.add(rows.group(1));
        }
        return ids;
    }

    /**
     * Whether a residual row is closed.
     *
     * <p>The canon's convention is that a treated residual has its original description struck
     * through, so the row opens with {@code ~~}. Rows that were frozen by instruction carry a
     * bold marker first and are <b>not</b> closed — the marker is deliberately not a strike.
     */
    private static boolean isClosed(String canon, String id) {
        Matcher row = Pattern.compile("(?m)^\\| " + id + " \\| (.*)$").matcher(canon);
        assertTrue(row.find(), "the canon has no row for " + id);
        return row.group(1).startsWith("~~");
    }

    @Test
    @DisplayName("the readiness document's residual counts are the canon's actual rows")
    void theReadinessCountsAreTheCanonsRows() throws IOException {
        String canon = read(CANON);
        String readiness = read(READINESS);

        List<String> ids = residualRows(canon);
        assertEquals(ids.size(), new LinkedHashSet<>(ids).size(),
                "the canon declares a residual id twice: " + ids);
        long closed = ids.stream().filter(id -> isClosed(canon, id)).count();
        long open = ids.size() - closed;

        String here = slice(readiness, "## 0. 現在地", "## 1.", "the readiness document's §0");
        Matcher stated = Pattern.compile("残件表 (\\d+) 行のうち \\*\\*処置済み (\\d+) / 開いている (\\d+)\\*\\*")
                .matcher(here);
        assertTrue(stated.find(), "the readiness document's §0 does not state the residual counts");

        assertEquals(ids.size(), Integer.parseInt(stated.group(1)),
                "the readiness document says the canon has " + stated.group(1) + " residual rows "
                        + "and it has " + ids.size());
        assertEquals(closed, Integer.parseInt(stated.group(2)),
                "the readiness document says " + stated.group(2) + " residuals are treated and "
                        + closed + " rows are struck through. A summary nobody counts is how "
                        + "yesterday's progress reads as today's");
        assertEquals(open, Integer.parseInt(stated.group(3)),
                "the readiness document says " + stated.group(3) + " residuals are open and "
                        + open + " rows are not struck through");
    }

    @Test
    @DisplayName("the readiness document's control count is what the runner declares")
    void theReadinessControlCountIsTheRunners() throws IOException {
        String runner = read(RUNNER);
        String readiness = read(READINESS);

        SortedSet<String> declared = new TreeSet<>();
        Matcher ids = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(runner);
        while (ids.find()) {
            assertTrue(declared.add(ids.group(1)), "control id " + ids.group(1) + " declared twice");
        }

        String here = slice(readiness, "## 0. 現在地", "## 1.", "the readiness document's §0");
        Matcher stated = Pattern.compile("負のコントロール \\*\\*(\\d+) 本\\*\\*").matcher(here);
        assertTrue(stated.find(), "the readiness document's §0 does not state a control count");
        assertEquals(declared.size(), Integer.parseInt(stated.group(1)),
                "the readiness document says " + stated.group(1) + " controls and the runner "
                        + "declares " + declared.size());
    }

    @Test
    @DisplayName("every open residual is in exactly one of the readiness document's three groups")
    void everyOpenResidualIsInExactlyOneGroup() throws IOException {
        String canon = read(CANON);
        String readiness = read(READINESS);

        SortedSet<String> open = new TreeSet<>();
        for (String id : residualRows(canon)) {
            if (!isClosed(canon, id)) {
                open.add(id);
            }
        }

        String here = slice(readiness, "開いている残件の内訳", "- **進捗は計画",
                "the readiness document's breakdown of the open residuals");

        // Each group states a count and then names its members. Both are read: the count beside
        // a list is precisely the figure that went stale in §5's ledger twice.
        SortedSet<String> grouped = new TreeSet<>();
        Matcher groups = Pattern.compile("\\*\\*([^*]+?) (\\d+)\\*\\* — ([^\\n]+)").matcher(here);
        int found = 0;
        while (groups.find()) {
            found++;
            String name = groups.group(1);
            SortedSet<String> members = idsIn(groups.group(3), open);
            assertEquals(members.size(), Integer.parseInt(groups.group(2)),
                    "the group 「" + name + "」 says " + groups.group(2) + " residuals and names "
                            + members.size() + ": " + members);
            for (String id : members) {
                assertTrue(grouped.add(id),
                        id + " is named in more than one group, so the breakdown double-counts it");
            }
        }
        assertEquals(3, found,
                "the readiness document's breakdown should have three groups (intended design / "
                        + "out of 3.4.0 / actually open) and this read " + found);

        // Both directions. Only union-covers-open was checked at first, which let a group name a
        // residual that is closed — or one that does not exist — while the word "内訳" still read
        // as measured.
        SortedSet<String> namedButNotOpen = new TreeSet<>(grouped);
        namedButNotOpen.removeAll(open);
        assertTrue(namedButNotOpen.isEmpty(),
                "the breakdown names " + namedButNotOpen + " as open, and the canon has them "
                        + "closed or absent");
        SortedSet<String> openButNotNamed = new TreeSet<>(open);
        openButNotNamed.removeAll(grouped);
        assertTrue(openButNotNamed.isEmpty(),
                "the canon has " + openButNotNamed + " open and the readiness document's "
                        + "breakdown does not account for them, so the summary reads as smaller "
                        + "than the work");
    }

    /**
     * Residual ids named in a fragment, ranges expanded.
     *
     * <p>Ranges expand over ids that exist, never over the integers between the endpoints — a
     * range wider than the canon has would otherwise invent residuals and satisfy the check
     * with them.
     */
    private static SortedSet<String> idsIn(String fragment, Set<String> universe) {
        SortedSet<String> named = new TreeSet<>();
        Matcher single = Pattern.compile("\\b(R\\d+)\\b").matcher(fragment);
        while (single.find()) {
            named.add(single.group(1));
        }
        Matcher range = Pattern.compile("R(\\d+)〜R(\\d+)").matcher(fragment);
        while (range.find()) {
            int low = Integer.parseInt(range.group(1));
            int high = Integer.parseInt(range.group(2));
            assertTrue(low <= high, "the range R" + low + "〜R" + high + " runs backwards");
            for (String id : universe) {
                int n = Integer.parseInt(id.substring(1));
                if (n >= low && n <= high) {
                    named.add(id);
                }
            }
        }
        return named;
    }

    @Test
    @DisplayName("the frozen residuals say so in the canon, and the plan names exactly those three")
    void theFrozenResidualsAreMarkedInBothPlaces() throws IOException {
        String canon = read(CANON);
        String plan = read(PLAN);

        for (String id : new TreeSet<>(FROZEN)) {
            Matcher row = Pattern.compile("(?m)^\\| " + id + " \\| (.*)$").matcher(canon);
            assertTrue(row.find(), "the canon has no row for " + id);
            assertTrue(row.group(1).contains(FREEZE_MARK),
                    id + " is frozen by instruction and its canon row does not say so. The row is "
                            + "where the next batch looks before opening an area");
            assertFalse(row.group(1).startsWith("~~"),
                    id + " is struck through as treated and also marked frozen. A frozen residual "
                            + "is open work that is not to be opened, not finished work");
        }

        // Ends at a horizontal rule at the start of a line. "---" alone found the table's own
        // separator row (|---|---|---|) and cut the slice off above the rows being checked.
        String freeze = slice(plan, "### 凍結中の残件", "\n---", "the plan's §17 freeze table");
        SortedSet<String> namedInPlan = new TreeSet<>();
        Matcher rows = Pattern.compile("(?m)^\\| (R\\d+) \\|").matcher(freeze);
        while (rows.find()) {
            namedInPlan.add(rows.group(1));
        }
        assertEquals(new TreeSet<>(FROZEN), namedInPlan,
                "the plan's freeze table names " + namedInPlan + " and the frozen set is " + FROZEN
                        + ". Both directions matter: a residual quietly dropped from this table "
                        + "is one the next batch will open");
    }

    @Test
    @DisplayName("Phase 0's gate is not claimed while the profile spec says the canonical form is unspecified")
    void phaseZerosGateTracksTheSpec() throws IOException {
        String spec = read(SPEC);
        String plan = read(PLAN);
        String readiness = read(READINESS);

        // Scoped to the spec's own "what this does not regulate" table. Searching the whole file
        // would be satisfied by §3, which specifies canonical encoding and mentions .c14n nowhere
        // near a disclaimer.
        String unspecified = slice(spec, "## 0. この文書が規定しないもの", "## 1.",
                "the profile spec's list of what it does not regulate");
        boolean canonicalFormOpen = unspecified.contains("`.c14n` の正準化形式");
        boolean trustMeaningOpen = unspecified.contains("P5 の ERS");

        String phaseZero = slice(plan, "| 0 | `BASE_SHA`", "\n", "the plan's Phase 0 row");
        boolean planSaysNotMet = phaseZero.contains("未達");
        boolean readinessSaysNotMet = readiness.contains("Phase 0 のゲートは満たしていない");

        if (canonicalFormOpen || trustMeaningOpen) {
            assertTrue(planSaysNotMet,
                    "the profile spec says the canonical form or the trust meanings are "
                            + "unspecified, which is exactly what Phase 0's gate asks about, and "
                            + "the plan's Phase 0 row does not say 未達. Phase 2 may be written "
                            + "from the part that is specified; G0 may not be read as met");
            assertTrue(readinessSaysNotMet,
                    "the profile spec leaves the canonical form or the trust meanings "
                            + "unspecified and the readiness document does not say Phase 0's gate "
                            + "is unmet. The readiness document is what gets read for 'how much "
                            + "is left'");
        } else {
            // The other direction. Once the spec regulates both, a lock that still demanded 未達
            // would hold a false sentence in place — the same defect pointed the other way.
            assertFalse(planSaysNotMet,
                    "the profile spec now regulates the canonical form and the trust meanings, "
                            + "and the plan still records Phase 0's gate as 未達");
            assertFalse(readinessSaysNotMet,
                    "the profile spec now regulates the canonical form and the trust meanings, "
                            + "and the readiness document still says Phase 0's gate is unmet");
        }
    }

    @Test
    @DisplayName("every Track A row agrees with the residual table it points at")
    void everyTrackARowAgreesWithTheResidualTable() throws IOException {
        String canon = read(CANON);
        String plan = read(PLAN);

        String trackA = slice(plan, "## 13. トラック A", "入れない:", "the plan's Track A table");
        Matcher rows = Pattern.compile("(?m)^\\| (A-\\d) \\| ([^|]+?) \\| (.*)$").matcher(trackA);
        int checked = 0;
        SortedSet<String> seen = new TreeSet<>();
        while (rows.find()) {
            String item = rows.group(1);
            String pointsAt = rows.group(2).trim();
            String description = rows.group(3);
            seen.add(item);
            if (!pointsAt.matches("R\\d+")) {
                // A-0 is the UI version, which has no residual row. Anything else naming a
                // non-residual would be a way to leave this check with nothing to compare.
                assertEquals("A-0", item,
                        item + " points at 「" + pointsAt + "」, which is not a residual id, so "
                                + "nothing cross-checks whether it is done");
                continue;
            }
            checked++;
            boolean planSaysTreated = description.contains("処置済み");
            boolean canonSaysTreated = isClosed(canon, pointsAt);
            assertEquals(canonSaysTreated, planSaysTreated,
                    item + " (" + pointsAt + "): the plan's Track A table says "
                            + (planSaysTreated ? "treated" : "outstanding")
                            + " and the canon says "
                            + (canonSaysTreated ? "treated" : "outstanding")
                            + ". The plan is what gets read for 'what is Phase 1 waiting on'");
        }
        assertEquals(new TreeSet<>(List.of("A-0", "A-1", "A-2", "A-3", "A-4", "A-5", "A-6", "A-7", "A-8")),
                seen,
                "the plan's Track A table should hold A-0 through A-8 and this read " + seen);
        assertEquals(8, checked, "eight Track A items point at a residual row and this checked " + checked);
    }

    @Test
    @DisplayName("the workflow starts for the documents this lock reads")
    void theGateRunsForTheDocumentsThisLockReads() throws IOException {
        // Owned here rather than added to the sibling lock's list, because the sibling's list is
        // "the files THOSE locks read" and this batch's lesson (R63) is that an enumeration
        // widened by hand for a fourth time is the wrong shape. Each class guards its own inputs.
        Path workflow = Path.of("../.github/workflows/integration-tests.yml");
        String yaml = read(workflow);
        for (String needed : List.of(
                "docs/design/v3.4.0-evidence-and-residuals-plan.md",
                "docs/design/v3.4-release-readiness.md")) {
            int occurrences = yaml.split(Pattern.quote("'" + needed + "'"), -1).length - 1;
            assertEquals(2, occurrences,
                    "'" + needed + "' should appear in BOTH the push and pull_request paths of "
                            + workflow + " and appears " + occurrences + " time(s). Editing a "
                            + "count in it would not start the workflow that checks the count");
        }
    }

    @Test
    @DisplayName("a finished sweep of fewer controls does not read as a sweep of today's")
    void aFinishedSweepOfFewerControlsDoesNotReadAsTodays() throws IOException {
        String canon = read(CANON);
        String readiness = read(READINESS);
        String runner = read(RUNNER);

        int declared = 0;
        Matcher ids = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(runner);
        while (ids.find()) {
            declared++;
        }

        // The canon records each completed sweep as "N 回目 <date>（M 本、…". The latest one is
        // what the documents mean when they say the sweep passed.
        int latestRound = 0;
        int sweptThen = 0;
        Matcher sweeps = Pattern.compile("(\\d+) 回目 20\\d\\d-\\d\\d-\\d\\d[^（]*（(\\d+) 本").matcher(canon);
        while (sweeps.find()) {
            int round = Integer.parseInt(sweeps.group(1));
            if (round >= latestRound) {
                latestRound = round;
                sweptThen = Integer.parseInt(sweeps.group(2));
            }
        }
        assertTrue(latestRound > 0, "the canon records no completed sweep");
        assertTrue(sweptThen <= declared,
                "the canon says the last sweep ran " + sweptThen + " controls and the runner "
                        + "declares only " + declared + ". A sweep cannot have run controls that "
                        + "do not exist — one of the two figures is wrong");

        boolean outstanding = sweptThen < declared;
        boolean canonSaysOutstanding = canon.contains("通しは未実施");
        boolean readinessSaysOutstanding = readiness.contains("期限切れ");

        if (outstanding) {
            assertTrue(canonSaysOutstanding,
                    "the last completed sweep ran " + sweptThen + " of today's " + declared
                            + " controls and the canon does not say the sweep is outstanding. "
                            + "'704 本で完走した' then reads as a statement about this tree");
            assertTrue(readinessSaysOutstanding,
                    "the last completed sweep ran " + sweptThen + " of today's " + declared
                            + " controls and the readiness document does not mark the sweep "
                            + "outstanding");
        } else {
            assertFalse(canonSaysOutstanding,
                    "the last sweep covered every declared control and the canon still says the "
                            + "sweep is outstanding");
            assertFalse(readinessSaysOutstanding,
                    "the last sweep covered every declared control and the readiness document "
                            + "still marks it outstanding");
        }
    }
}
