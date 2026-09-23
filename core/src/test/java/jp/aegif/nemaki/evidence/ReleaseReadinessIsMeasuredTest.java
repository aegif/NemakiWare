package jp.aegif.nemaki.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

        // EVERY exit, not the first one. The §0 figure was checked and the table row beside it
        // was not, so half of a count update left the document stating two different totals
        // with every lock green (Codex, twenty-first review, P3). A four-digit "N 本" in this
        // document is a control count; the unit totals are written with a comma.
        Matcher everywhere = Pattern.compile("(?<![0-9,])([0-9]{4}) 本").matcher(readiness);
        int exits = 0;
        while (everywhere.find()) {
            exits++;
            assertEquals(declared.size(), Integer.parseInt(everywhere.group(1)),
                    "the readiness document states " + everywhere.group(1) + " controls "
                            + "somewhere and the runner declares " + declared.size());
        }
        assertTrue(exits >= 2, "only " + exits + " control-count exits were found, so this "
                + "checked one place and called it every place");
    }

    /**
     * The readiness document's unit total is at least what the sources declare.
     *
     * <p>A LOWER BOUND, and the document says so beside the figure. An exact lock is not
     * available: {@code @ParameterizedTest} runs one annotation many times, so a source scan
     * counts fewer than the suite runs, and replicating the invocation count would mean
     * running the suite from inside it. The figures are deliberately NOT written here: two
     * that were are what both reviewers found stale the next round. What this catches is the gross drift — a figure left
     * behind while hundreds of tests were added — not the off-by-one that a stale edit leaves
     * (Codex, twenty-second review, P3, and the limit is recorded rather than papered over).
     */
    @Test
    @DisplayName("the readiness document's unit total is at least what the sources declare")
    void theReadinessUnitTotalIsAtLeastTheSources() throws IOException {
        // The groups surefire is told to skip, from the build itself.
        Matcher excluded = Pattern.compile("<surefire\\.excludedGroups>([^<]*)</")
                .matcher(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
        assertTrue(excluded.find(), "core/pom.xml no longer declares the excluded groups, so "
                + "this test cannot mirror what the suite skips");
        List<String> excludedGroups = java.util.Arrays.stream(excluded.group(1).split(","))
                .map(String::trim).filter(group -> !group.isEmpty()).toList();
        assertFalse(excludedGroups.isEmpty(), "the build excludes no group, so the skip below "
                + "is checking nothing");

        int declared = 0;
        try (Stream<Path> walk = Files.walk(Path.of("src/test/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (file.toString().contains("/cmis/tck/") || name.endsWith("IT")
                        || name.equals("MultiThreadTest") || name.equals("InheritedFlagTest")
                        || name.equals("AtlasManualDataLoader")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                // The GROUPS surefire excludes as well as the names: core/pom.xml sets
                // excludedGroups to the atlas integration tag, so such a class does not run,
                // and counting its tests put invocations into a LOWER bound the real suite can
                // never reach (Codex, twenty-third review, P2).
                //
                // COMMENTS STRIPPED, and the excluded group READ FROM THE POM rather than
                // written here. Three shapes of this test have now been wrong: a contains()
                // over each raw line matched the COMMENT that explains the rule, so this file
                // excluded ITSELF (subagent, twenty-fourth review, P1); anchoring to the start
                // of a line then missed a wrapped annotation and one preceded by another on
                // the same line, counting tests the suite never runs (both reviewers,
                // twenty-fifth review, P2); and stripping comments alone STILL matched,
                // because the group name was a string literal in this very method (measured).
                // Taking the name from the build is what makes the rule describe the suite
                // instead of resembling it. Neither direction is caught by the bound below —
                // it has hundreds of invocations of slack — so this is measured by reading,
                // and said so rather than claimed as a control.
                String source = String.join("\n", lines)
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)//.*$", " ");
                if (source.contains("@Tag")
                        && excludedGroups.stream().anyMatch(source::contains)) {
                    continue;
                }
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.equals("@Test") || trimmed.startsWith("@ParameterizedTest")) {
                        declared++;
                    }
                }
            }
        }
        assertTrue(declared > 5000, "only " + declared + " tests were found, so the exclusions "
                + "this mirrors have stopped matching the suite's own");

        Matcher stated = Pattern.compile("\\*\\*([0-9],[0-9]{3}) 本 green\\*\\*（2026")
                .matcher(read(READINESS));
        assertTrue(stated.find(), "the readiness document no longer states a dated unit total");
        int recorded = Integer.parseInt(stated.group(1).replace(",", ""));
        assertTrue(recorded >= declared,
                "the readiness document records " + recorded + " unit tests and the sources "
                        + "declare " + declared + " test annotations under the same exclusions. "
                        + "A recorded run can exceed the annotation count (parameterised tests) "
                        + "but never fall below it");
    }

    /**
     * Every open residual is classified where the release gate reads the classification.
     *
     * <p>The gate's conclusion — "one P1 remains, and it is R59" — is drawn from a table that
     * listed ten residuals while §0 counted twelve, because two newly opened ones were never
     * added. Leaving a residual out of the classification makes the gate's answer stronger
     * than the evidence for it (Codex, twenty-first review, P2).
     */
    @Test
    @DisplayName("every open residual is classified in the RC gate's own table")
    void everyOpenResidualIsClassifiedForTheGate() throws IOException {
        String canon = read(CANON);
        String gate = slice(read(READINESS), "### RC 条件 11", "## ",
                "the readiness document's RC condition 11 section");

        // The group the gate is ABOUT: the readiness document's own "actually open" list.
        // Comparing against every unstruck canon row pulled in the two groups this document
        // separates out — intended design, and not-in-3.4.0 — which the gate does not classify
        // and should not.
        String breakdown = slice(read(READINESS), "開いている残件の内訳", "- **進捗は計画",
                "the readiness document's breakdown of the open residuals");
        // Taken as "unstruck, minus the two groups this document sets aside" — those two name
        // their members one by one, while the open group uses a RANGE (R58〜R63), so reading
        // the open group directly would silently skip the four ids inside the range.
        StringBuilder setAside = new StringBuilder();
        Matcher groups = Pattern.compile("\\*\\*(意図した設計|3\\.4\\.0 に入れない) \\d+\\*\\* — ([^\\n]+)")
                .matcher(breakdown);
        int found = 0;
        while (groups.find()) {
            found++;
            setAside.append(groups.group(2)).append('\n');
        }
        assertEquals(2, found, "the readiness document no longer sets aside two groups: "
                + breakdown);

        SortedSet<String> missing = new TreeSet<>();
        for (String id : residualRows(canon)) {
            if (isClosed(canon, id)
                    || setAside.toString().matches("(?s).*\\b" + id + "\\b.*")) {
                continue;
            }
            // A ROW, not a mention. The prose under the table names residuals too, so a
            // residual dropped from the classification stayed "covered" by the sentence that
            // explains why it was added (measured — the control did not fire).
            boolean classified = false;
            for (String line : gate.split("\n")) {
                if (!line.startsWith("| ")) {
                    continue;
                }
                // The FIRST CELL. Rows group residuals that share a classification
                // ("R55 / R58 / R60"), so the subject of a row is everything before the
                // second pipe — and only that. Reading the whole line let the reason column
                // stand in for a classification, and reading the whole section let the prose
                // under the table do it (both measured, control did not fire).
                String subject = line.split("\\|", 3)[1];
                if (subject.matches("(?s).*\\b" + id + "\\b.*")) {
                    classified = true;
                }
            }
            if (!classified) {
                missing.add(id);
            }
        }
        assertEquals(new TreeSet<String>(), missing,
                "open residuals the RC gate's classification does not mention: " + missing
                        + ". The gate concludes from that table, so a residual missing from it "
                        + "is one the conclusion did not account for");
    }

    /**
     * The canon's "N gaps in this range" is counted from the runner, not asserted.
     *
     * <p>Neither this number nor the verifier's test count was read by anything — the one
     * measured number in reach of a lock was the control total (Codex, twentieth review, P3).
     * A retired or resurrected control moves the gap count, and a stale figure then reads as a
     * measurement: the canon already carried a wrong one for two rounds (it said 13 while RB3
     * had been put back).
     */
    @Test
    @DisplayName("the canon's gap count is the runner's")
    void theCanonsGapCountIsTheRunners() throws IOException {
        String runner = read(RUNNER);
        String canon = read(CANON);

        SortedSet<String> declared = new TreeSet<>();
        Matcher ids = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(runner);
        while (ids.find()) {
            declared.add(ids.group(1));
        }
        Matcher boundary = Pattern.compile("境界 LM3").matcher(canon);
        assertTrue(boundary.find(), "the canon no longer names the sweep boundary");

        // The three-letter sequence the ids run through, from the boundary to the newest.
        List<String> sequence = new ArrayList<>();
        for (char first = 'A'; first <= 'Z'; first++) {
            for (char second = 'A'; second <= 'Z'; second++) {
                sequence.add("" + first + second + "3");
            }
        }
        String newest = declared.stream().filter(sequence::contains)
                .max((a, b) -> sequence.indexOf(a) - sequence.indexOf(b)).orElseThrow();
        int from = sequence.indexOf("LN3");
        int to = sequence.indexOf(newest);
        assertTrue(from >= 0 && to > from, "the sweep boundary is not in the id sequence");
        int gaps = 0;
        for (String id : sequence.subList(from, to + 1)) {
            if (!declared.contains(id)) {
                gaps++;
            }
        }

        Matcher stated = Pattern.compile("この範囲には欠番が (\\d+) ある").matcher(canon);
        assertTrue(stated.find(), "the canon no longer states a gap count");
        assertEquals(gaps, Integer.parseInt(stated.group(1)),
                "the canon says " + stated.group(1) + " gaps between LN3 and " + newest
                        + " and the runner leaves " + gaps + ". A retired or resurrected "
                        + "control moves this, and a stale figure reads as a measurement");
    }

    /**
     * The readiness document's verifier test count is what those modules declare.
     *
     * <p>Counted from the sources rather than from a run, because this module cannot run the
     * verifier's suite — which is exactly why the number drifted unchecked.
     */
    @Test
    @DisplayName("the readiness document's verifier test count is what the verifier declares")
    void theReadinessVerifierCountIsTheVerifiers() throws IOException {
        int tests = 0;
        for (String root : List.of("../evidence-verifier-core/src/test/java")) {
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (trimmed.equals("@Test") || trimmed.startsWith("@ParameterizedTest")) {
                            tests++;
                        }
                        assertFalse(trimmed.startsWith("@Disabled"),
                                "a disabled test makes this count wrong: " + file);
                    }
                }
            }
        }
        assertTrue(tests > 100, "only " + tests + " tests were found, so this counted nothing");

        Matcher stated = Pattern.compile("verifier-core (\\d+) / cli").matcher(read(READINESS));
        assertTrue(stated.find(), "the readiness document does not state a verifier-core count");
        assertEquals(tests, Integer.parseInt(stated.group(1)),
                "the readiness document says verifier-core runs " + stated.group(1)
                        + " tests and its sources declare " + tests);
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

    /**
     * "Which phases have been started" agrees with the plan that records the work.
     *
     * <p>The readiness document is the one people read for how much is left, and this line is
     * the first thing in it. It said 「未着手 = Phase 3〜9」 while Phases 3 through 7 had all
     * landed — five phases of work reported as not begun, in the document whose own opening
     * paragraph says uncounted numbers go stale.
     *
     * <p>Both directions, and for opposite harms: a phase reported as not started makes the
     * release look further away than it is and invites someone to do the work twice; a phase
     * reported as started when it is not is the direction that ships.
     */
    @Test
    @DisplayName("the phases said to be started are the ones the plan records work for")
    void thePhaseStatusAgreesWithThePlan() throws IOException {
        String readiness = read(READINESS);
        String plan = read(PLAN);

        Matcher started = Pattern.compile("\\*\\*着手済み = Phase (\\d)〜(\\d)\\*\\*").matcher(readiness);
        assertTrue(started.find(),
                "the readiness document no longer says which phases have been started. That "
                        + "line is what a reader takes for the project's position");
        Matcher notStarted = Pattern.compile("\\*\\*未着手 = Phase ([\\d・]+)\\*\\*").matcher(readiness);
        assertTrue(notStarted.find(), "the readiness document no longer says which phases are "
                + "untouched, so 'started' has nothing to be the complement of");

        int from = Integer.parseInt(started.group(1));
        int to = Integer.parseInt(started.group(2));
        SortedSet<String> claimedNotStarted = new TreeSet<>(
                List.of(notStarted.group(1).split("・")));

        // The two lines have to be a PARTITION of 0-9. Checking only the phases each line names
        // leaves a phase dropped from both silently unchecked — the reader sees a document that
        // simply does not mention it, which is the quietest way for a phase to go missing
        // (both reviews, P2).
        SortedSet<String> unclassified = new TreeSet<>();
        for (int phase = 0; phase <= 9; phase++) {
            boolean claimed = (phase >= from && phase <= to)
                    || claimedNotStarted.contains(String.valueOf(phase));
            if (!claimed) {
                unclassified.add(String.valueOf(phase));
            }
        }
        assertTrue(unclassified.isEmpty(),
                "the readiness document's two lines do not account for every phase: "
                        + unclassified + " appears in neither 着手済み nor 未着手. A phase in "
                        + "neither list is invisible to a reader deciding what is left");

        // The plan's phase rows carry the evidence: a row that has been worked says so with a
        // dated 着手 or 途中経過 note. Nothing here reads a summary — the row IS the record.
        String phases = slice(plan, "## 16. 実装順とゲート", "\n## 17",
                "the plan's phase table");
        for (int phase = 0; phase <= 9; phase++) {
            Matcher row = Pattern.compile("(?m)^\\| " + phase + " \\| (.*)$").matcher(phases);
            assertTrue(row.find(), "the plan has no row for Phase " + phase);
            String text = row.group(1);
            // A DATE, not a vocabulary. The first version looked for 着手 / 途中経過 / 達成 and
            // missed Phase 1, which says 完了, and would have missed the next word someone
            // reached for. What every worked row actually carries is the day it was worked.
            boolean planRecordsWork = text.matches(".*20\\d\\d-\\d\\d-\\d\\d.*");

            if (phase >= from && phase <= to) {
                assertTrue(planRecordsWork,
                        "the readiness document says Phase " + phase + " has been started and "
                                + "the plan's row for it records no work. The direction that "
                                + "ships is this one: a phase claimed as begun that nobody began");
            }
            if (claimedNotStarted.contains(String.valueOf(phase))) {
                assertFalse(planRecordsWork,
                        "the readiness document lists Phase " + phase + " as untouched and the "
                                + "plan's row for it records work. Someone reading the readiness "
                                + "document to decide what to pick up will do it a second time");
            }
        }
    }

    /**
     * The G0 leftovers list stops naming a leftover once it exists.
     *
     * <p>The sentence beside the gate says what is still only a FORMAT question. It was written
     * when none of the three existed, and nothing read it afterwards — so the trust profile
     * file format was written into the release runbook and the readiness document went on
     * listing it as outstanding. Understating is the gentler direction, but it makes the one
     * document people read for "how much is left" wrong, which is the defect this whole branch
     * is about pointed the other way.
     *
     * <p>Both directions, for the same reason every other gate here has both: a lock that only
     * demanded the item be absent would let it be dropped from the list before it was written.
     */
    @Test
    @DisplayName("G0's leftovers list agrees with what has actually been written")
    void theLeftoversListAgreesWithWhatExists() throws IOException {
        String readiness = read(READINESS);
        Path release = Path.of("../docs/operations/evidence-verifier-release.md");
        assertTrue(Files.exists(release), "the verifier release runbook is not at " + release);
        String runbook = read(release);

        // EVERY sentence that says what is left, not the one in §1.5. The document states this
        // twice — §0's summary and §1.5's gate — and the first version of this lock read only
        // §1.5, so §0 went on naming the format as outstanding after it was written. One claim,
        // many exits: the exits here are sentences, so the check collects them.
        List<String> leftoverSentences = new java.util.ArrayList<>();
        for (String line : readiness.split("\n")) {
            if ((line.contains("残るのは") || line.contains("残っているのは")) && line.contains("形式")) {
                leftoverSentences.add(line);
            }
        }
        assertTrue(leftoverSentences.size() >= 2,
                "the readiness document used to say what G0 leaves open in more than one place "
                        + "and now says it " + leftoverSentences.size() + " time(s). Either a "
                        + "statement was deleted or its wording drifted out of this check's "
                        + "reach — which is how §0 went stale while §1.5 was locked");

        // "Written" means the runbook carries the format's OWN section with its fields, not
        // that the words appear somewhere: the phrase occurs in prose about what is missing too.
        boolean formatIsWritten = runbook.contains("## trust profile のファイル形式")
                && runbook.contains("anchors")
                && runbook.contains("requireRevocationAtIssuance");

        // ACROSS the three documents, not just this one. The first version read the readiness
        // document alone, so the plan went on listing the format as outstanding in two more
        // places — one of them the plan's own copy of the very sentence this polices. A claim
        // that lives in three files cannot be held by a lock that opens one (both reviews, P2).
        int statements = 0;
        for (Path document : List.of(READINESS, PLAN, CANON)) {
            String text = read(document);
            // By SENTENCE. A whole-file check cannot tell "we wrote it" from "it is still to
            // write" when both sentences are in the same file — and after this batch, both are.
            for (String sentence : text.split("。")) {
                if (!sentence.contains("trust profile のファイル形式")) {
                    continue;
                }
                statements++;
                boolean saysItIsWritten = sentence.contains("書いた");
                if (formatIsWritten) {
                    assertTrue(saysItIsWritten,
                            document + " names the trust profile file format in a sentence that "
                                    + "does not say it was written, while it IS written in "
                                    + release + ": 「" + sentence.trim().replace('\n', ' ')
                                    + "」. Whoever reads that to decide what is left will write "
                                    + "it a second time, or report the release as further away "
                                    + "than it is");
                } else {
                    assertFalse(saysItIsWritten,
                            document + " says the trust profile file format was written and "
                                    + release + " does not define it: 「"
                                    + sentence.trim().replace('\n', ' ') + "」. A verifier "
                                    + "operator would have no way to construct the file the CLI "
                                    + "demands, and this sentence would stop anyone tracking it");
                }
            }
        }
        assertTrue(statements >= 2,
                "the trust profile file format is mentioned " + statements + " time(s) across "
                        + "the three documents. It used to be mentioned more, so either the "
                        + "wording drifted out of this check's reach or the documents stopped "
                        + "saying where it lives");
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

    /**
     * Every document any lock reads is a document that starts the workflow.
     *
     * <p>The sibling lock names its own two files, and its comment says an enumeration widened
     * by hand a fourth time is the wrong shape. It was right, and the hand list proved it:
     * three locks written this batch read files under {@code docs/operations/}, and the
     * forbidden-claim lint has read {@code docs/operations/} and {@code docs/compliance/} for
     * longer than that — none of which were in the workflow's paths. A pull request deleting
     * the ABSENT row, or the sentence saying this version does not renew, touched only files
     * that never start the workflow, so every one of those locks was green by not running.
     *
     * <p>So this does not carry a list. It reads the test sources for the documents they open
     * and requires the workflow to start for each one. A lock added tomorrow that reads a new
     * document is covered the day it is written, which a hand list never manages.
     */
    @Test
    @DisplayName("every document a lock reads starts the workflow that runs that lock")
    void everyDocumentALockReadsStartsTheWorkflow() throws IOException {
        Path workflow = Path.of("../.github/workflows/integration-tests.yml");
        String yaml = read(workflow);

        SortedSet<String> read = new TreeSet<>();
        Path tests = Path.of("src/test/java");
        try (java.util.stream.Stream<Path> walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher opened = Pattern.compile("\"\\.\\./(docs/[^\"]+)\"")
                        .matcher(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                while (opened.find()) {
                    read.add(opened.group(1));
                }
            }
        }
        assertFalse(read.isEmpty(),
                "no lock appears to open a document under docs/. Either the tests moved or this "
                        + "check's pattern no longer matches how they name a path, and it would "
                        + "pass by finding nothing");

        SortedSet<String> ungated = new TreeSet<>();
        for (String document : read) {
            // Either the file itself is listed, or a directory above it is listed with /**.
            // Both are real ways to start the workflow; requiring the exact path would force
            // every new document into the list by hand, which is the shape being replaced.
            // The path itself, then the path AS a directory — the forbidden-claim lint opens
            // docs/operations and docs/compliance as directories, and a check that only walked
            // upwards from the last slash asked for 'docs/**' and missed the entry that covers
            // them — then climb.
            boolean gated = occurrences(yaml, document) == 2
                    || occurrences(yaml, document + "/**") == 2;
            for (int cut = document.lastIndexOf('/'); cut > 0 && !gated;
                    cut = document.lastIndexOf('/', cut - 1)) {
                gated = occurrences(yaml, document.substring(0, cut) + "/**") == 2;
            }
            if (!gated) {
                ungated.add(document);
            }
        }
        assertTrue(ungated.isEmpty(),
                "a lock reads these documents and no path in " + workflow + " starts the "
                        + "workflow for them, in BOTH push and pull_request. A change that "
                        + "breaks one of those locks would be green because the job never "
                        + "ran: " + ungated);
    }

    private static int occurrences(String yaml, String path) {
        return yaml.split(Pattern.quote("'" + path + "'"), -1).length - 1;
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
        // One phrase for both documents: 「通し未実施」. And required AT EVERY SITE that states
        // the added-since-sweep count, on the same line — not once per file. The readiness
        // document states the count in §1.4 and in §4; a single contains() was satisfied by §4
        // after GY3 deleted the phrase from §1.4, so the control did not fire (measured).
        Pattern site = Pattern.compile("(?m)^.*以後に足した control は \\*{0,2}\\d+ 本.*$");
        for (var doc : List.of(Map.entry("canon", canon), Map.entry("readiness", readiness))) {
            Matcher m = site.matcher(doc.getValue());
            int sites = 0;
            while (m.find()) {
                sites++;
                boolean saysOutstanding = m.group().contains("通し未実施");
                if (outstanding) {
                    assertTrue(saysOutstanding,
                            "the last completed sweep ran " + sweptThen + " of today's "
                                    + declared + " controls, and the " + doc.getKey()
                                    + " states the added-since-sweep count without saying those "
                                    + "controls are unswept: 「" + m.group().trim() + "」. A "
                                    + "finished sweep of fewer controls then reads as a "
                                    + "measurement of today's tree");
                } else {
                    assertFalse(saysOutstanding,
                            "the last sweep covered every declared control and the "
                                    + doc.getKey() + " still marks the sweep outstanding: 「"
                                    + m.group().trim() + "」");
                }
            }
            assertTrue(sites >= 1, "the " + doc.getKey() + " no longer states the "
                    + "added-since-sweep count anywhere, so nothing here can be checked");
        }
    }
}
