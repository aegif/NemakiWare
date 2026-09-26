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

        // EVERY exit in THIS reader, not the first one. What this does NOT mean: that the
        // table row was unguarded. EverySupportedCouchDbIsMeasuredTest's totalForms has read
        // it since 3538ac296 (2026-09-21, when the form stopped requiring the number to be
        // bold), so a stale table row was already red over there — this is a second reader,
        // not a closed gap, and saying otherwise was the third repeat of the same over-claim
        // (both reviewers, twenty-ninth review, P2). A four-digit "N 本" in this document is
        // a control count; the unit totals are written with a comma.
        // The TOTAL, unless the sentence says it is what a sweep RAN ("… 本を流し", "… 本すべて",
        // "… 本で完走", "… 本が通った"): that figure is the canon's sweep record, not today's
        // count. The two were the same number from the sixth sweep (1551 of 1551) until a
        // control was added after it — then every true record of the sixth sweep read as a stale
        // total and failed here (2026-09-26). A sweep figure is compared with the sweep record
        // instead, so a stale one is still caught.
        // Each sweep the canon records, by round. A sweep figure is compared with the sweep its
        // sentence names ("6 回目"), and a figure whose sentence names no round is a TOTAL.
        // Comparing every sweep figure with the latest would reject the true record of the sixth
        // sweep the moment a seventh is written (subagent review, P3); taking the ending alone
        // as the mark of a sweep let 「現行の control は 1551 本すべて」 hide a stale total (Codex,
        // P3). What still passes: a stale total in a sentence that names a round AND equals that
        // round's record — a figure cannot be told from that round's record by its words.
        java.util.Map<Integer, Integer> sweptInRound = LedgerText.sweptInRound(read(CANON));
        assertFalse(sweptInRound.isEmpty(), "the canon records no completed sweep");
        Matcher everywhere = Pattern.compile("(?<![0-9,])([0-9]{4}) 本(を流し|すべて|で完走|が通った)?")
                .matcher(readiness);
        int exits = 0;
        while (everywhere.find()) {
            exits++;
            String sentence = LedgerText.sentenceAround(readiness, everywhere.start());
            int offset = everywhere.start() - LedgerText.sentenceStart(readiness, everywhere.start());
            Integer round = everywhere.group(2) == null ? null
                    : LedgerText.roundNamedNear(sentence, offset);
            if (round != null) {
                assertTrue(sweptInRound.containsKey(round), "the readiness document records a "
                        + "sweep for round " + round + ", which the canon does not: 「"
                        + sentence.trim() + "」");
                assertEquals(sweptInRound.get(round), Integer.parseInt(everywhere.group(1)),
                        "the readiness document says sweep " + round + " ran " + everywhere.group(1)
                                + " controls and the canon's record of it says "
                                + sweptInRound.get(round) + ": 「" + sentence.trim() + "」");
                continue;
            }
            assertEquals(declared.size(), Integer.parseInt(everywhere.group(1)),
                    "the readiness document states " + everywhere.group(1) + " controls "
                            + "somewhere and the runner declares " + declared.size() + ": 「"
                            + sentence.trim() + "」");
        }
        // The CANON states it too, in its own wording, and was not read here — so updating the
        // readiness document alone left the two disagreeing with every lock green (both
        // reviewers, twenty-sixth review, P2/P3).
        Matcher inCanon = Pattern.compile("コントロール \\*\\*([0-9]{3,4})\\*\\*")
                .matcher(read(CANON));
        while (inCanon.find()) {
            exits++;
            assertEquals(declared.size(), Integer.parseInt(inCanon.group(1)),
                    "the canon states " + inCanon.group(1) + " controls and the runner "
                            + "declares " + declared.size());
        }
        assertTrue(exits >= 3, "only " + exits + " control-count exits were found, so this "
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
        int possiblyExcluded = 0;
        try (Stream<Path> walk = Files.walk(Path.of("src/test/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (file.toString().contains("/cmis/tck/") || name.endsWith("IT")
                        || name.equals("MultiThreadTest") || name.equals("InheritedFlagTest")
                        || name.equals("AtlasManualDataLoader")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int here = 0;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.equals("@Test") || trimmed.startsWith("@ParameterizedTest")) {
                        here++;
                    }
                }
                declared += here;
                // NOT an emulation of what surefire skips — an UPPER BOUND on it. Four shapes
                // of "does this class carry the excluded group?" have now been wrong, in both
                // directions: a contains() over each line matched the COMMENT explaining the
                // rule and excluded this file from its own count (subagent, twenty-fourth
                // review, P1); a start-of-line anchor missed a wrapped annotation (both
                // reviewers, twenty-fifth); comment-stripping alone still matched a string
                // literal in this method (measured); and the syntactic form still misreads a
                // METHOD-level tag as excluding the whole class, and misses a qualified or
                // concatenated one (Codex, twenty-seventh review, P2).
                //
                // The bound does not need the answer. It needs to know the MOST the
                // exclusions could remove, so it subtracts every test in a file that so much
                // as MENTIONS an excluded group.
                //
                // That is safe WHILE the tag is written as a literal in the class it tags,
                // which is how all five of them are written today. A constant from another
                // file (@Tag(AtlasTags.INTEGRATION)) would not be mentioned here, the
                // subtraction would be too small, and the floor too high — so the failure
                // this could produce is a refusal of a correct record, never acceptance of a
                // stale one. Saying it was "impossible to get wrong" was a claim without that
                // condition attached (subagent, twenty-eighth review, P2).
                if (excludedGroups.stream().anyMatch(String.join("\n", lines)::contains)) {
                    possiblyExcluded += here;
                }
            }
        }
        assertTrue(declared > 5000, "only " + declared + " tests were found, so the name "
                + "exclusions this mirrors have stopped matching the suite's own");
        assertTrue(possiblyExcluded < declared / 2, possiblyExcluded + " of " + declared
                + " tests sit in a file mentioning an excluded group, which leaves this bound "
                + "with nothing to say");

        Matcher stated = Pattern.compile("\\*\\*([0-9],[0-9]{3}) 本 green\\*\\*（2026")
                .matcher(read(READINESS));
        assertTrue(stated.find(), "the readiness document no longer states a dated unit total");
        int recorded = Integer.parseInt(stated.group(1).replace(",", ""));
        int floor = declared - possiblyExcluded;
        assertTrue(recorded >= floor,
                "the readiness document records " + recorded + " unit tests; the sources "
                        + "declare " + declared + " test annotations, of which at most "
                        + possiblyExcluded + " can be removed by the excluded groups, so a real "
                        + "run cannot be below " + floor + ". A recorded run can EXCEED the "
                        + "annotation count (parameterised tests) but never fall below this");
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

        // The gate's own HEADING and the sentence under it repeat the count the table carries,
        // and nothing read them — so the gate could announce "12 classified" over a table of
        // ten (Codex, twenty-ninth review, P2). Counted from the table's rows, which is where
        // the classification actually is.
        // The IDS in the first cell, not the fragments between slashes. A row reading
        // "R55 / note" counted two, "R61・R62・R63" — a separator this document already uses
        // elsewhere — would count one, and either way the heading could then be edited to
        // agree with a wrong number (both reviewers, thirtieth review, P2/P3).
        int classified = 0;
        for (String line : gate.split("\n")) {
            if (!line.startsWith("| R")) {
                continue;
            }
            Matcher id = Pattern.compile("\\bR\\d+\\b").matcher(line.split("\\|", 3)[1]);
            while (id.find()) {
                classified++;
            }
        }
        assertTrue(classified > 0, "the RC gate's table carries no residual ids: " + gate);

        // ANCHORED to the heading and to the sentence under it, and each required to be the
        // ONLY one in the section — an unanchored find() would take a figure from anywhere in
        // the gate's prose, so a wrong heading could be left standing beside a right sentence
        // (Codex, thirtieth review, P2).
        // The INTRODUCTION — the heading and the paragraph before the table — not the whole
        // section. An unanchored search would take the figure from anywhere in the gate's
        // prose, including a struck-through sentence or a historical note further down, and
        // leave a wrong heading standing beside it (Codex, thirty-first review, P2).
        String intro = gate.split("(?m)^\\| ", 2)[0];
        assertFalse(intro.contains("~~"), "the RC gate's introduction carries a withdrawn "
                + "sentence, so the count this compares may be one that was taken back: "
                + intro);
        Matcher says = Pattern.compile("(?m)^### RC 条件 11[^\n]*?(\\d+) 件を分類した")
                .matcher(intro);
        assertTrue(says.find(), "the RC gate's heading no longer says how many it classified");
        assertEquals(classified, Integer.parseInt(says.group(1)),
                "the RC gate's heading says it classified " + says.group(1) + " residuals and "
                        + "its table carries " + classified);
        assertFalse(says.find(), "the section carries more than one RC condition 11 heading");
        Matcher under = Pattern.compile("書ける状態にまだない\\*{0,2}。開いている (\\d+) 件を")
                .matcher(intro);
        assertTrue(under.find(), "the RC gate's prose no longer says how many are open");
        assertEquals(classified, Integer.parseInt(under.group(1)),
                "the RC gate's prose says " + under.group(1) + " open residuals and its table "
                        + "carries " + classified);
        assertFalse(under.find(), "the RC gate states its open count in more than one sentence, "
                + "so which one this compares is a guess");
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
        // The boundary the canon names — the highest id when the last full sweep ran. It was
        // "境界 LM3" written into this lock, which the sixth sweep (to JP4) left behind.
        Matcher boundary = Pattern.compile("境界 ([A-Z]{2}[34])").matcher(canon);
        assertTrue(boundary.find(), "the canon no longer names the sweep boundary");
        String last = boundary.group(1);

        // The three-letter sequence the ids run through, from the boundary to the newest: the
        // "3" generation AA3…ZZ3 and then the "4" generation AA4… that follows it (the first id
        // after ZZ3 was AA4; a sequence that stopped at ZZ3 would count no gap past it).
        List<String> sequence = new ArrayList<>();
        for (char generation = '3'; generation <= '4'; generation++) {
            for (char first = 'A'; first <= 'Z'; first++) {
                for (char second = 'A'; second <= 'Z'; second++) {
                    sequence.add("" + first + second + generation);
                }
            }
        }
        String newest = declared.stream().filter(sequence::contains)
                .max((a, b) -> sequence.indexOf(a) - sequence.indexOf(b)).orElseThrow();
        int at = sequence.indexOf(last);
        int to = sequence.indexOf(newest);
        assertTrue(at >= 0 && to >= at, "the sweep boundary " + last + " is not in the id sequence at or below "
                + "the newest id " + newest);
        Matcher stated = Pattern.compile("この範囲には欠番が (\\d+) ある").matcher(canon);
        boolean statesACount = stated.find();
        if (to == at) {
            // Nothing was added after the last full sweep: there is no range above the boundary,
            // and a gap count for one would be invented (the sixth sweep emptied it, 2026-09-26).
            assertFalse(statesACount, "nothing sits above the sweep boundary " + last + ", and the canon "
                    + "still states a gap count for a range above it: 「" + (statesACount ? stated.group() : "") + "」");
            return;
        }
        int gaps = 0;
        for (String id : sequence.subList(at + 1, to + 1)) {
            if (!declared.contains(id)) {
                gaps++;
            }
        }

        assertTrue(statesACount, "the canon no longer states a gap count");
        assertEquals(gaps, Integer.parseInt(stated.group(1)),
                "the canon says " + stated.group(1) + " gaps between " + last + " and " + newest
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

    /**
     * The R59 row names its locks and counts them; both must be the test sources' (review,
     * thirty-sixth round, P3 — the count "R59 の N 本" was read by no lock, so adding, dropping
     * or renaming a lock left the canon green and wrong).
     */
    @Test
    @DisplayName("the locks the R59 row names exist, and its two counts are the sources'")
    void theR59RowsLocksExistAndItsCountsAreTheSources() throws IOException {
        String canon = read(CANON);
        Matcher row = Pattern.compile("(?m)^\\| R59 \\|.*$").matcher(canon);
        assertTrue(row.find(), "the canon has no R59 row");
        Matcher counts = Pattern.compile("錠 (\\d+) 本中 R59 の (\\d+) 本（(.*?)）、control").matcher(row.group());
        assertTrue(counts.find(), "the R59 row no longer states its lock counts in the expected shape");
        int totalStated = Integer.parseInt(counts.group(1));
        int r59Stated = Integer.parseInt(counts.group(2));
        String listed = counts.group(3);

        String notionClass = "jp.aegif.nemaki.rest.ingest.note.NotionPartialReadsAreNotCompleteTest";
        int totalTests = testMethodsOf(notionClass).size();
        assertEquals(totalTests, totalStated, "the R59 row says NotionPartialReadsAreNotCompleteTest has "
                + totalStated + " locks and the class declares " + totalTests);

        int r59Listed = 0;
        java.util.Set<String> qualified = new java.util.TreeSet<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher name = Pattern.compile("`(?:([A-Za-z]+)#)?([a-z][A-Za-z0-9]+)`").matcher(listed);
        while (name.find()) {
            String cls = name.group(1);
            String method = name.group(2);
            // A name listed twice kept the count right while a dropped lock went unnoticed
            // (review, fortieth round, P2).
            assertTrue(seen.add((cls == null ? "" : cls + "#") + method),
                    "the R59 row lists a lock twice: " + method);
            String className = notionClass;
            if (cls != null) {
                qualified.add(cls + "#" + method);
                List<Path> found;
                try (Stream<Path> walk = Files.walk(Path.of("src/test/java"))) {
                    found = walk.filter(p -> p.getFileName().toString().equals(cls + ".java")).toList();
                }
                // Exactly one: a simple name that two packages declare would otherwise be
                // resolved by walk order (review, P3). Fail closed instead.
                assertEquals(1, found.size(), "the R59 row names a test class that " + (found.isEmpty()
                        ? "does not exist: " : "exists in more than one package: ") + cls + " " + found);
                // src/test/java/jp/aegif/.../Cls.java -> jp.aegif....Cls, built from the path's
                // segments rather than its string, so the platform separator does not matter
                // (review, P2: '/' alone left every Windows run ClassNotFound).
                Path relative = Path.of("src/test/java").relativize(found.get(0));
                StringBuilder fqcn = new StringBuilder();
                for (int seg = 0; seg < relative.getNameCount(); seg++) {
                    String part = relative.getName(seg).toString();
                    if (seg == relative.getNameCount() - 1) part = part.substring(0, part.length() - ".java".length());
                    if (fqcn.length() > 0) fqcn.append('.');
                    fqcn.append(part);
                }
                className = fqcn.toString();
            } else {
                r59Listed++;
            }
            assertTrue(testMethodsOf(className).contains(method),
                    "the R59 row names something that is not a test method: " + (cls == null ? "" : cls + "#") + method);
        }
        assertEquals(r59Listed, r59Stated, "the R59 row says it has " + r59Stated
                + " locks in NotionPartialReadsAreNotCompleteTest and lists " + r59Listed);
        // The one R59 lock outside that class is pinned by name: dropping it from the row left
        // both counts right and every listed name real (review, thirty-seventh round, P3).
        assertTrue(qualified.contains("DlqRetryRefusalStatusTest#theStrippedBinaryNoteDoesNotPromiseARefetch"),
                "the R59 row no longer names its DLQ-side lock: " + qualified);
    }

    private static String readPomForExcludedGroups() {
        try {
            return Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError("core/pom.xml could not be read: " + unreadable.getMessage());
        }
    }

    /**
     * The names of the test methods a test class RUNS, as JUnit itself discovers them: the
     * Platform launcher is asked to discover the class, and every identifier backed by a
     * method — a test, or the container a {@code @ParameterizedTest} / {@code @TestTemplate}
     * expands into — is one lock. Reading source lines, then re-deriving Jupiter's rules by
     * reflection (private methods, inherited and default methods, overrides, nested classes),
     * each left a form JUnit would run and this count would not, or the reverse; four review
     * rounds found one apiece. Discovery is the rule, so nothing is re-derived here.
     *
     * <p>The request carries the tag exclusion surefire runs under, so what is counted is what
     * the suite runs, not what the engine could run. Two refusals remain the lock's own: a
     * {@code @Disabled} method or enclosing class (discovered but not run; looked up the way
     * Jupiter looks it up, meta-annotations included) fails the count, and two runnable methods
     * of one name are refused, because the R59 row names locks by method name and could not
     * tell them apart.
     */
    private static java.util.Set<String> testMethodsOf(String className) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("the R59 row names a test class that is not on the class path: " + className);
        }
        // The same tag filters surefire runs under, so a tagged lock that the suite skips is not
        // counted as run (review, P3). The EFFECTIVE values: surefire forwards -D user
        // properties into the forked JVM, so an override on the command line is read before the
        // pom's default (review, forty-seventh round, P3); the include side (-Dgroups) likewise.
        String excludedSetting = System.getProperty("surefire.excludedGroups");
        if (excludedSetting == null) {
            Matcher excluded = Pattern.compile("<surefire\\.excludedGroups>([^<]*)</")
                    .matcher(readPomForExcludedGroups());
            assertTrue(excluded.find(), "core/pom.xml no longer declares the excluded groups, so this "
                    + "count cannot mirror what the suite skips");
            excludedSetting = excluded.group(1);
        }
        String[] excludedGroups = java.util.Arrays.stream(excludedSetting.split(","))
                .map(String::trim).filter(group -> !group.isEmpty()).toArray(String[]::new);
        String[] includedGroups = java.util.Arrays.stream(System.getProperty("groups", "").split(","))
                .map(String::trim).filter(group -> !group.isEmpty()).toArray(String[]::new);
        org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder builder =
                org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                        .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(type));
        if (excludedGroups.length > 0) {
            builder.filters(org.junit.platform.launcher.TagFilter.excludeTags(excludedGroups));
        }
        if (includedGroups.length > 0) {
            builder.filters(org.junit.platform.launcher.TagFilter.includeTags(includedGroups));
        }
        org.junit.platform.launcher.LauncherDiscoveryRequest request = builder.build();
        org.junit.platform.launcher.TestPlan plan =
                org.junit.platform.launcher.core.LauncherFactory.create().discover(request);
        java.util.Set<String> names = new TreeSet<>();
        for (org.junit.platform.launcher.TestIdentifier root : plan.getRoots()) {
            for (org.junit.platform.launcher.TestIdentifier id : plan.getDescendants(root)) {
                if (id.getSource().isEmpty()
                        || !(id.getSource().get() instanceof org.junit.platform.engine.support.descriptor.MethodSource)) {
                    continue;
                }
                org.junit.platform.engine.support.descriptor.MethodSource source =
                        (org.junit.platform.engine.support.descriptor.MethodSource) id.getSource().get();
                // Only identifiers whose parent is a class (or the engine): a parameterized
                // test's invocations, if any were discovered, sit under their own template.
                java.util.Optional<org.junit.platform.launcher.TestIdentifier> parent = plan.getParent(id);
                java.util.Optional<Class<?>> discoveredIn = parent
                        .flatMap(org.junit.platform.launcher.TestIdentifier::getSource)
                        .filter(s -> s instanceof org.junit.platform.engine.support.descriptor.ClassSource)
                        .map(s -> ((org.junit.platform.engine.support.descriptor.ClassSource) s).getJavaClass());
                if (discoveredIn.isEmpty()) continue;
                java.lang.reflect.Method method = source.getJavaMethod();
                // Disabled is looked up the way Jupiter looks it up — meta-annotations included —
                // on the method and on the class it was DISCOVERED in and every class enclosing
                // that one (not the declaring class: an inherited test runs under the subclass,
                // and a @Disabled @Nested subclass is discovered but never run — reviews, P3).
                assertFalse(org.junit.platform.commons.support.AnnotationSupport
                                .isAnnotated(method, org.junit.jupiter.api.Disabled.class),
                        "a disabled test makes this count wrong: " + source.getClassName() + "#" + method.getName());
                for (Class<?> enclosing = discoveredIn.get(); enclosing != null; enclosing = enclosing.getEnclosingClass()) {
                    assertFalse(org.junit.platform.commons.support.AnnotationSupport
                                    .isAnnotated(enclosing, org.junit.jupiter.api.Disabled.class),
                            "a disabled class makes this count wrong: " + enclosing.getName() + " (holds " + method.getName() + ")");
                }
                assertTrue(names.add(method.getName()),
                        "two test methods share a name the R59 row could not tell apart: "
                                + source.getClassName() + "#" + method.getName());
            }
        }
        assertFalse(names.isEmpty(), "JUnit discovered no test in " + className + ", so this counted nothing");
        return names;
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

        // The SUMMARY LINE above the breakdown repeats the same three figures, and nothing
        // read it — so §0 could say one thing while the breakdown two lines below said
        // another (subagent, twenty-sixth review, P2).
        // Anchored on the BRACKET, not on 「うち」 — that word also opens "残件表 100 行のうち"
        // one clause earlier, so the span started there and swept in figures the breakdown
        // does not state (subagent, twenty-eighth review, P2, measured).
        //
        // To the bracket that CLOSES THE SENTENCE — "）。" — with a class that crosses
        // newlines. Three anchors have now been tried and each traded one correct document
        // for another: to the line end (breaks on the reflow this document really does),
        // to the first bracket (breaks on a nested one, which a reviewer wrote out as a
        // plausible edit), and now to the sentence end, which survives both because the
        // inner bracket is followed by "・" and the outer one by "。" (subagent, thirtieth
        // review, P2; Codex, thirty-first, P2).
        //
        // Exactly ONE, so a second summary added elsewhere cannot be compared in its place.
        // NOTE: none of these refinements is measured by a control — UN3 fires on any anchor,
        // because the span always contains the same three labels.
        Matcher inline = Pattern.compile("（うち[\\s\\S]*?）。").matcher(readiness);
        assertTrue(inline.find(), "the readiness document's summary line no longer repeats the "
                + "breakdown, so this check has nothing to compare");
        String summary = inline.group();
        assertFalse(inline.find(), "the readiness document states more than one 「（うち…）」 "
                + "summary, so which one this compares is a guess: " + summary);

        // Each group states a count and then names its members. Both are read: the count beside
        // a list is precisely the figure that went stale in §5's ledger twice.
        SortedSet<String> grouped = new TreeSet<>();
        Matcher groups = Pattern.compile("\\*\\*([^*]+?) (\\d+)\\*\\* — ([^\\n]+)").matcher(here);
        int found = 0;
        while (groups.find()) {
            found++;
            // BY LABEL, not by position: reordering the breakdown's bullets would
            // otherwise compare different groups and report a correct edit as stale
            // (subagent, twenty-seventh review, P3).
            Matcher labelled = Pattern.compile(Pattern.quote(groups.group(1).trim())
                    + " (\\d+)").matcher(summary);
            assertTrue(labelled.find(), "the readiness document's summary line does not name "
                    + "the group 「" + groups.group(1).trim() + "」 the breakdown lists");
            assertEquals(labelled.group(1), groups.group(2),
                    "the readiness document's summary line says " + labelled.group(1)
                            + " for 「" + groups.group(1).trim() + "」 and the breakdown below "
                            + "says " + groups.group(2) + ". One of the two is stale");
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
            assertTrue(LedgerText.listedInBothTriggers(yaml, needed),
                    "'" + needed + "' is not a path entry of BOTH the push and pull_request "
                            + "triggers of " + workflow + ". Editing a count in it would not start "
                            + "the workflow that checks the count");
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

        // EVERY file a lock opens outside core, not only docs/. The pattern was "../docs/…", so a
        // lock reading ../RELEASE_NOTES.md, or docs/ itself as a root, was invisible here and its
        // input never had to start the workflow (subagent review, P2). A literal that does not
        // name an existing file inside the repository — the path-traversal fixtures, "../x" — is
        // not a document anything reads.
        SortedSet<String> read = new TreeSet<>();
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path tests = Path.of("src/test/java");
        try (java.util.stream.Stream<Path> walk = Files.walk(tests)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher opened = Pattern.compile("\"\\.\\./([^\"]+)\"")
                        .matcher(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                while (opened.find()) {
                    Path target = repo.resolve(opened.group(1)).normalize();
                    if (!target.startsWith(repo) || target.equals(repo) || !Files.exists(target)) {
                        continue;
                    }
                    read.add(repo.relativize(target).toString().replace('\\', '/'));
                }
            }
        }
        assertFalse(read.isEmpty(),
                "no lock appears to open a file outside core. Either the tests moved or this "
                        + "check's pattern no longer matches how they name a path, and it would "
                        + "pass by finding nothing");
        assertTrue(read.contains("RELEASE_NOTES.md") && read.contains("docs"),
                "the locks that read the release notes and docs/ as a root are not seen here, so "
                        + "this check no longer sees files outside docs/ or directory roots: " + read);

        SortedSet<String> ungated = new TreeSet<>();
        for (String document : read) {
            // Either the file itself is listed, or a directory above it is listed with /**.
            // Both are real ways to start the workflow; requiring the exact path would force
            // every new document into the list by hand, which is the shape being replaced.
            // The path itself, then the path AS a directory — the forbidden-claim lint opens
            // docs/operations and docs/compliance as directories, and a check that only walked
            // upwards from the last slash asked for 'docs/**' and missed the entry that covers
            // them — then climb.
            boolean gated = LedgerText.listedInBothTriggers(yaml, document)
                    || LedgerText.listedInBothTriggers(yaml, document + "/**");
            for (int cut = document.lastIndexOf('/'); cut > 0 && !gated;
                    cut = document.lastIndexOf('/', cut - 1)) {
                gated = LedgerText.listedInBothTriggers(yaml, document.substring(0, cut) + "/**");
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
        // THREE documents, not two. The plan states the same sentence and was not read, so
        // a count updated in two places and left behind in the third stayed green (both
        // reviewers, twenty-sixth review, P2/P3).
        Pattern site = Pattern.compile("(?m)^.*以後に足した control は \\*{0,2}(\\d+) 本.*$");
        for (var doc : List.of(Map.entry("canon", canon), Map.entry("readiness", readiness),
                Map.entry("plan", read(PLAN)))) {
            Matcher m = site.matcher(doc.getValue());
            int sites = 0;
            while (m.find()) {
                sites++;
                // The NUMBER as well as the phrase. It was only ever checked for being
                // stated, so a control added without updating it left every assertion green
                // and the figure wrong (Codex, twenty-sixth review, P3).
                assertEquals(declared - sweptThen, Integer.parseInt(m.group(1)),
                        "the " + doc.getKey() + " says " + m.group(1) + " controls were added "
                                + "since the sweep, and the runner declares " + declared
                                + " with " + sweptThen + " swept: 「" + m.group().trim() + "」");
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
