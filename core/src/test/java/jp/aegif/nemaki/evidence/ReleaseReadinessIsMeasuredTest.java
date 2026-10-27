package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.util.test.HarnessBroken;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
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
    private static final Path BATCH_PLAN = Path.of("../docs/design/v3.4.1-plan.md");
    private static final Path READINESS = Path.of("../docs/design/v3.4-release-readiness.md");
    private static final Path SPEC = Path.of("../docs/design/evidence-profile-v1.md");
    private static final Path RUNNER = Path.of("../tools/negative-controls/run_negative_controls.py");

    /**
     * The residuals the owner froze on 2026-09-20 that are frozen still. R61 was the third: on
     * 2026-09-29 the owner had all three classified for RC condition 11, R61 came out P1, and
     * the owner had it opened and treated that day.
     */
    private static final Set<String> FROZEN = Set.of("R62", "R63");

    private static final String FREEZE_MARK = "凍結（ユーザー指示 2026-09-20）";

    /**
     * The canon's one line that states the plan-figure lock is withdrawn — a line IS it or is not.
     *
     * <p>EVERY ROUND SINCE BATCH 33. The escapes came in two families, and conflating them
     * was itself a
     * finding (batch-41 review, Codex P3).
     * Reading the statement as prose broke first (batches 33-38: a bare substring, an emphasised
     * token, a cell, a quotation, a strike-through, {@code __…__}, spacing variants, a column the
     * document does not render). Moving it to a line of its own fixed that and moved the problem
     * into the SKIPPING: a fence's length and which line closes it, a nested fence, a backtick
     * run's parity, four spaces, a tab that is not first, an HTML comment, a sliced section
     * defeated by one plain-text mention of its own heading (batches 39-40: Codex P2 ×7, subagent
     * P2 ×8, each measured, several of them on the real canon).
     *
     * <p>NO CONTEXT IS READ. This reader counts lines equal to this string after Unicode
     * normalisation and nothing else — no fence rule, no indent rule, no parity, no section. A
     * line is the statement or it is not, and neither a quotation nor an example nor an emphasis
     * can be it. The sentence here said 「raw, with nothing skipped and nothing stripped but a
     * trailing carriage return」 for a batch after the normalisation went in and the carriage
     * return moved into the split (batch-42 review: Codex P2, subagent P2-4).
     *
     * <p>THE PRICE, and it is the canon's to pay: this line may not appear a second time ANYWHERE,
     * not even inside a fenced example. §5 says so. A format can be described without reproducing
     * it on a line of its own, which is what §5 now does.
     *
     * <p>WHAT IS NO LONGER CLAIMED: where the line sits. 「§5 の中」 was asserted for one batch
     * with a section reader, and the reader was defeated by adding one sentence mentioning
     * 「## 5. 測定」 elsewhere — measured on the real canon (batch-40 review, subagent P2-2). A
     * second copy outside §5 still refuses, because the count is over the whole document; what is
     * not read is a SINGLE copy that moved.
     */
    private static final String WITHDRAWN_LINE = "- 取り下げ: plan-figure-lock（R212）";

    /**
     * The line that would take that statement back, counted over the whole canon — NOT the same
     * way.
     *
     * <p>THE TWO ARMS DO NOT SHARE A COMPARISON, and that is this pair's whole point: for the
     * statement a near miss must not SATISFY the lock, for the retraction a near miss must not
     * SLIP PAST it. So this one is compared as a NEAR-COPY — with everything that carries no
     * meaning here taken out, anywhere in the line.
     * The sentence
     * here said 「counted the same way」 for a batch after that stopped being true (batch-42
     * review, subagent P2-4).
     *
     * <p>A quotation of it inside a sentence, a copy inside backticks and an emphasised phrase
     * containing it are all NOT it — none is a line equal to this string — which is why §5 can
     * explain the format by quoting this line. A copy ON A LINE OF ITS OWN **is** read, fenced or
     * indented or not: this reader has no context rules, so 「it is only an example」 is not
     * something it can tell. The javadoc here claimed the opposite for a batch (batch-42 review,
     * subagent P3-5, measured both ways).
     */
    private static final String REINSTATED_LINE = "- 取り下げ撤回: plan-figure-lock（R212）";

    /**
     * Read, having first checked the file is there.
     *
     * <p>Existence first for the reason a sibling lock records: a moved or renamed file throws
     * {@link java.nio.file.NoSuchFileException}, which the control runner scores as "fired for
     * the wrong reason" rather than as this lock doing its job.
     */
    private static String read(Path path) throws IOException {
        HarnessBroken.unless(Files.exists(path),
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
        HarnessBroken.unless(start >= 0, what + " does not start where this lock looks (\"" + from + "\")");
        int end = text.indexOf(to, start + from.length());
        HarnessBroken.unless(end > start, what + " does not end where this lock looks (\"" + to + "\")");
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
        // AN ASSERTION, NOT HarnessBroken. Batch 39 made this a refusal on the premise that the
        // ids come from residualRows, so 「no such row」 would be this reader disagreeing with its
        // own caller. THAT PREMISE IS FALSE FOR ONE OF THE FOUR CALLERS: the track-A check reads
        // its ids from the evidence plan's own table, and there 「the canon has no row for this
        // id」 is exactly the finding — two documents disagreeing. Batch 39 therefore took a
        // FIRING away: the runner scores HarnessBroken ahead of an assertion and does not count it
        // as a protection firing (batch-39 review, subagent P2-3, who also measured that this is
        // the only caller the arm is reachable from).
        //
        // KNOWN LIMITS, recorded rather than closed:
        //   - residualRows does not see a row whose id cell is written any other way
        //     (「| **R84** |」), so such a row drops out of the ids instead of being reported here.
        //     Dropping one row changes the count theReadinessCountsAreTheCanonsRows compares with
        //     the readiness document, so that edit alone is loud. RENAMING one keeps 212/135/77,
        //     and whether anything notices depends on the row: an OPEN one is caught by
        //     everyOpenResidualIsInExactlyOneGroup, which requires every open id to be named
        //     somewhere, so a CLOSED row is the one that can be renamed unnoticed. HOW MANY is
        //     not stated here: the figure 「37」 written a batch ago reproduced at no scope the
        //     reviewer tried — tracked files 19, docs/ 33, docs/design 50, the three carriers 58,
        //     readiness alone 70 — because 「no other document」 was never defined (batch-39
        //     review, Codex P2; batch-40 review, subagent P3-3; batch-41 review, subagent P3-2 —
        //     the earlier versions claimed first too little, then too much, then a number).
        //   - NO CONTROL REACHES THIS METHOD. Every control anchored in this class sabotages a
        //     line BELOW it, in the readers; what batch 40 changed here is the CLASSIFICATION of a
        //     failure — assertion or harness breakage — which a sabotage of another line cannot
        //     move. HOW MANY such controls there are is deliberately NOT written here: the figure
        //     was nineteen, then sixteen, and both were this class's count BEFORE the same commit
        //     added or retired controls in it — three generations of one sentence going stale in
        //     the commit that wrote it (batch-40 review, subagent P3-8; batch-41 review, subagent
        //     P2-2; batch-42 review: Codex P2, subagent P2-1). Count it when you need it, with ast
        //     over the runner's CONTROLS grouped by the file each sabotages.
        //   - the locator batch 39 removed also refused two rows claiming one id. This reader
        //     takes the first silently; theReadinessCountsAreTheCanonsRows notices a duplicate id
        //     in the same class, and nothing else does (batch-39 review, subagent P3-5).
        Matcher row = Pattern.compile("(?m)^\\| " + Pattern.quote(id) + " \\| (.*)$")
                .matcher(canon);
        HarnessBroken.unless(row.find(), "the canon has no row for " + id + ", so this reader cannot say "
                + "whether it is closed. If the id came from another document, the two disagree");
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
        HarnessBroken.unless(stated.find(), "the readiness document's §0 does not state the residual counts");

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
        HarnessBroken.unless(stated.find(), "the readiness document's §0 does not state a control count");
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
        // The TOTAL, unless it is written in the one form a sweep figure takes —
        // 「N 回目の通し（M 本）」 (LedgerText.SWEEP_FIGURE) — which is compared with the canon's
        // record of THAT round. The two were the same number from the sixth sweep (1551 of 1551)
        // until a control was added after it, and then every true record of the sixth sweep read
        // as a stale total (2026-09-26). Three readings of free text for which round a figure
        // belonged to were each broken in review (the ending alone, the nearest round, a line
        // break before 「以後」); a form whose token carries its round cannot be misattributed, and
        // a figure written any other way is the total.
        java.util.Map<Integer, Integer> sweptInRound = LedgerText.sweptInRound(read(CANON));
        HarnessBroken.unless(!(sweptInRound.isEmpty()), "the canon records no completed sweep");
        assertEquals(List.of(), LedgerText.conflictingSweeps(read(CANON)),
                "the canon records a sweep round twice with different numbers, so which one a "
                        + "figure is checked against depends on the order they are written in");
        int exits = 0;
        Matcher sweep = LedgerText.SWEEP_FIGURE.matcher(readiness);
        while (sweep.find()) {
            exits++;
            int round = Integer.parseInt(sweep.group(1));
            assertTrue(sweptInRound.containsKey(round), "the readiness document records a sweep "
                    + "for round " + round + ", which the canon does not: 「" + sweep.group() + "」");
            assertEquals(sweptInRound.get(round), Integer.parseInt(sweep.group(2)),
                    "the readiness document says " + sweep.group() + " and the canon's record of "
                            + "round " + round + " says " + sweptInRound.get(round));
        }
        for (LedgerText.Figure total : LedgerText.figuresOutsideSweeps(readiness)) {
            exits++;
            assertEquals(declared.size(), total.value(),
                    "the readiness document states " + total.value() + " controls and the runner "
                            + "declares " + declared.size() + ": 「" + total.context()
                            + "」. If this is what a sweep ran, write it as 「N 回目の通し（M 本）」");
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
        HarnessBroken.unless(excluded.find(), "core/pom.xml no longer declares the excluded groups, so "
                + "this test cannot mirror what the suite skips");
        List<String> excludedGroups = java.util.Arrays.stream(excluded.group(1).split(","))
                .map(String::trim).filter(group -> !group.isEmpty()).toList();
        HarnessBroken.unless(!(excludedGroups.isEmpty()), "the build excludes no group, so the skip below "
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

        // ANCHORED to the line that calls itself the latest. The pattern took the first dated
        // total in the document, and there is more than one (3.4.0's 7,881 is recorded further
        // down): if the order ever changed, this bound would read a figure that was never meant
        // to be the current one (round-25 review, subagent P3-5).
        // Any number of thousands groups and ANY year: `[0-9],[0-9]{3}` would stop matching at ten
        // thousand tests and this bound would then fail saying "the document no longer states it",
        // a FALSE reason (round-26 review, subagent P3-6); pinning the tail to 「（2026」 meant a
        // 2027 line could be ADDED beside the 2026 one with the uniqueness check below still
        // counting one, so the bound would go on reading last year's figure (round-27 review,
        // Codex P2).
        Matcher stated = Pattern.compile(
                "全ユニットの最新の実測は \\*\\*([0-9]{1,3}(?:,[0-9]{3})+) 本 green\\*\\*（20[0-9][0-9]")
                .matcher(read(READINESS));
        List<String> totals = new ArrayList<>();
        while (stated.find()) {
            totals.add(stated.group(1));
        }
        // Zero is this bound finding nothing to read, two is the document stating two figures: the
        // first is the harness, the second a document defect, and one assertion said both (R191,
        // closing batch 8).
        HarnessBroken.unless(!totals.isEmpty(),
                "the readiness document states no latest unit total this bound can read — the form it "
                        + "states it in changed (「全ユニットの最新の実測は **N 本 green**（20…）)");
        assertEquals(1, totals.size(),
                "the readiness document calls " + totals.size() + " figures its latest unit total "
                        + totals + "; this bound reads one, so there has to be exactly one — two "
                        + "means a stale line was left beside the new one (round-26 review, subagent "
                        + "P3-6; round-27 review, Codex P2)");
        // WHICH TREE it measured, not only how many tests. This line said "round 28" in the same
        // commit that recorded a round-29 run, and the same slip happened in rounds 20, 21 and 29:
        // the bound above reads the NUMBER, which does not move when a round adds no test, so
        // nothing noticed that the provenance was a round behind (round-30 review: subagent P2-2,
        // Codex P3 — "discipline did not stop it four times" is the reason this is mechanical).
        // THE GATE LINE of the newest round, read by a HELPER so that synthetic documents can
        // drive it. Three guards were written «unmeasurable» in round 33 and that was wrong: a
        // reader that takes the document as a String can be driven with a string, which is how
        // this class's sibling sweep measures its own forms (round-34 review: Codex P3, subagent
        // P3-③ and P3-④; the limit is withdrawn from the canon with them).
        // DRIVEN, both ways, before the real document's answer is used.
        assertEquals(1, readNewestGate("### 確認 9 巡目\n**ゲートと実測**: 全ユニット **1,000 本**\n")
                        .read(),
                "the canonical gate line is no longer read at all");
        assertEquals(1, readNewestGate("### 確認 9 巡目\n**ゲートと実測**: **全ユニット 1,000 本**\n")
                        .read(),
                "the other bold form this plan uses is no longer read");
        GateReading asAListItem = readNewestGate(
                "### 確認 9 巡目\n- **ゲートと実測**: 全ユニット **1,000 本**\n");
        assertEquals(1, asAListItem.claimed(),
                "a gate line written as a list item is not even counted as a claim, so the two "
                        + "counters agree by both missing it — the shape that left this reader "
                        + "blaming the readiness document (round-34 review, subagent P2-1)");
        assertEquals(0, asAListItem.read(), "the figure is read from a non-canonical line");
        GateReading wrapped = readNewestGate(
                "### 確認 9 巡目\n**ゲートと実測**: 失敗 0・skip 0 で\n全ユニット **1,000 本**\n");
        assertEquals(1, wrapped.claimed(),
                "a gate paragraph whose figure sits on its second line is not counted as a claim, "
                        + "so a wrapped line goes silent (round-34 review, subagent P2-4)");
        assertEquals(0, wrapped.read(), "the figure is read from the gate line itself");
        assertThrows(HarnessBroken.class,
                () -> readNewestGate("### 確認 9 巡目\n**ゲートと実測**: 全ユニット **1,000 本**\n"
                        + "### 確認 9 巡目\n**ゲートと実測**: 全ユニット **2,000 本**\n"),
                "two sections for the same round are read as one, so which of them records that "
                        + "round's run is decided by their order");
        assertThrows(HarnessBroken.class,
                () -> readNewestGate("### 確認 9 巡目\n```\n### 確認 99 巡目\n"),
                "a fence that never closes is read as prose, so a heading inside a code example "
                        + "becomes a section");
        assertThrows(HarnessBroken.class,
                () -> readNewestGate("### 確認 9 巡目\n<!--\n### 確認 99 巡目\n"),
                "an HTML comment that never closes is read as prose");
        // MID-LINE comments, measured by both reviewers as hiding a whole fake section
        // (round-35 review: Codex P2, subagent P2-3 — both got round=99 out of the round-34 reader).
        GateReading hidden = readNewestGate("### 確認 9 巡目\n"
                + "**ゲートと実測**: 全ユニット **1,000 本**\n\nprose <!--\n"
                + "### 確認 99 巡目\n**ゲートと実測**: 全ユニット **9,999 本**\n-->\n");
        assertEquals(List.of("確認 9 巡目"), hidden.sections(),
                "a section hidden inside a mid-line HTML comment is read as a real one, so a "
                        + "figure nobody recorded becomes «the newest run»");
        assertEquals("1,000", hidden.figure(), "the figure comes from the commented-out section");
        // A GATE LINE IN A TABLE CELL that is not the first cell (round-35 review, Codex P2).
        assertEquals(1, readNewestGate("### 確認 9 巡目\n"
                        + "| status | **ゲートと実測**: 全ユニット **1,000 本** |\n").claimed(),
                "a gate line in a table cell other than the first is not counted as a claim, so "
                        + "the round reads as one that never ran");
        // A FOUR-SPACE indented code block is a code block, not a gate line (round-35 review:
        // Codex P2, subagent P3-3 — counting it refused a correct document).
        GateReading indentedBlock = readNewestGate("### 確認 9 巡目\n"
                + "**ゲートと実測**: 全ユニット **1,000 本**\n\n"
                + "    **ゲートと実測**: 全ユニット **9,999 本**\n");
        assertEquals(indentedBlock.claimed(), indentedBlock.read(),
                "a four-space indented line is counted as a gate claim, so a code example makes "
                        + "this reader refuse a document that is correct");
        // A MENTION of a fence is not a fence: this plan talks about ``` inside backticks, and the
        // fence reader must not pair a mention with a real fence — the shape that made control JM5
        // a no-op (round-35 review, subagent P1-1, measured). Compared as a VALUE because a
        // refusal here is HarnessBroken, which the control runner excludes by name.
        String mentioned;
        try {
            mentioned = String.valueOf(readNewestGate("### 確認 9 巡目\nprose ``` inline\n"
                    + "**ゲートと実測**: 全ユニット **1,000 本**\n\n```\ncode\n```\n").read());
        } catch (HarnessBroken refused) {
            mentioned = "refused: " + refused.getMessage();
        }
        assertEquals("1", mentioned,
                "a ``` MENTIONED in prose was paired with a real fence, so the gate paragraph "
                        + "between them was deleted — and both counters dropped together, which is "
                        + "why nothing noticed (round-35 review, subagent P1-1)");
        assertEquals(0, readNewestGate("### 確認 9 巡目\n   ```\n**ゲートと実測**: 全ユニット "
                        + "**1,000 本**\n   ```\n").read(),
                "an indented fence — valid markdown — is not stripped, so a gate line inside a "
                        + "code example is read as a run (round-34 review, subagent P3-⑥)");

        // THE OTHER FENCE CHARACTER and an ORDERED list item: both arms existed with nothing
        // driving them (round-35 review, subagent P3-4).
        assertEquals(0, readNewestGate("### 確認 9 巡目\n~~~\n**ゲートと実測**: 全ユニット "
                        + "**1,000 本**\n~~~\n").read(),
                "a ~~~ fenced example is read as a run");
        assertEquals(1, readNewestGate("### 確認 9 巡目\n1. **ゲートと実測**: 全ユニット "
                        + "**1,000 本**\n").claimed(),
                "a gate line written as an ordered list item is not counted as a claim");

        // A SECTION THAT IS NOT A 確認 ROUND, and newest by document order. Both arms discriminate:
        // the round-35 reader returned 確認 9 巡目 / 1,000 for this fixture, which is the shape that
        // let R170's 8,246 go unread while the readiness document kept naming 8,197.
        GateReading batchSection = readNewestGate("### 確認 9 巡目\n"
                + "**ゲートと実測**: 全ユニット **1,000 本**\n\n### R170 — バッチ 19 を入れた\n"
                + "**ゲートと実測**: 全ユニット **1,049 本**\n");
        assertEquals(List.of("R170 — バッチ 19 を入れた"), batchSection.sections(),
                "a gate line recorded under a heading that is not a 確認 round is not read, so the "
                        + "newest run in the plan is invisible to this lock");
        assertEquals("1,049", batchSection.figure(),
                "the figure comes from a section that is not the newest one in the document");
        assertEquals(2, batchSection.read(), "one of the two gate lines was not read");

        // THE REAL DOCUMENT LAST. Reading it first meant a sabotage that makes this reader
        // refuse the real plan never reached the synthetic drives, so the control measuring the
        // fence anchor was scored «the harness broke» instead of «the guard fired» (round 35,
        // measured with control JM5).
        GateReading real = readNewestGate(read(BATCH_PLAN));
        HarnessBroken.unlessEqual(real.claimed(), real.read(),
                real.claimed() + " gate paragraphs claim a 全ユニット figure and this reader could "
                        + "read " + real.read() + ". A paragraph whose figure it cannot read is not "
                        + "a round without a run");

        List<String> newestSections = real.sections();
        String newestFigure = real.figure();
        if (newestFigure == null) {
            throw new HarnessBroken("the batch plan records no section with a gate line stating "
                    + "「全ユニット **N 本」 or 「**全ユニット N 本」, so this reader cannot tell "
                    + "which tree the readiness document should be naming as the latest");
        }
        // The provenance is whatever the readiness line puts in bold after the date, and it has to
        // NAME the plan's newest gate record — 「確認 35 巡目」 while that was the newest, 「バッチ
        // 20」 now. Asking for 「確認 N 巡目」 by shape was how a batch section slipped past: the
        // regex simply stopped matching the kind of record the plan had started writing.
        //
        // Compared with the heading's LABEL, equal — not contained in the heading. 「バッチ 2」 and
        // 「R186」 are both substrings of 「バッチ 20 — R186: …」, so a containment test passed for a
        // provenance that names a different record, or no record at all (batch-20 review, subagent
        // P3-2, measured).
        Matcher names = Pattern.compile(
                "全ユニットの最新の実測は \\*\\*([0-9]{1,3}(?:,[0-9]{3})+) 本 green\\*\\*（20[0-9][0-9]"
                        + "[^（]*?\\*\\*([^*（）]+)\\*\\*").matcher(read(READINESS));
        assertTrue(names.find(),
                "the readiness document's latest-measurement line no longer names the tree it "
                        + "measured in bold, so nobody can tell which one the figure belongs to");
        assertEquals(newestFigure, names.group(1),
                "the readiness document calls " + names.group(1) + " tests its latest measurement "
                        + "while the batch plan's newest recorded run is " + newestFigure + " "
                        + "(「" + newestSections + "」). The two are the same tree or one of them is "
                        + "wrong about which one it measured");
        List<String> labels = newestSections.stream().map(ReleaseReadinessIsMeasuredTest::labelOf)
                .toList();
        assertTrue(labels.contains(names.group(2)),
                "the readiness document says its latest measurement is 「" + names.group(2)
                        + "」, and the plan records that run under " + labels + " — so the figure "
                        + "above matches a run the two documents name differently, which is how a "
                        + "stale line stays green");

        int recorded = Integer.parseInt(totals.get(0).replace(",", ""));
        int floor = declared - possiblyExcluded;
        assertTrue(recorded >= floor,
                "the readiness document records " + recorded + " unit tests; the sources "
                        + "declare " + declared + " test annotations, of which at most "
                        + possiblyExcluded + " can be removed by the excluded groups, so a real "
                        + "run cannot be below " + floor + ". A recorded run can EXCEED the "
                        + "annotation count (parameterised tests) but never fall below this");
    }

    /** What a plan document says about the newest run it records. */
    private record GateReading(List<String> sections, String figure, int claimed, int read) { }

    /**
     * The newest round the plan records a unit run for, and how many gate paragraphs claim one.
     *
     * <p>Two counts, deliberately asymmetric. {@code claimed} is loose — a gate PARAGRAPH, however
     * its line begins and wherever the figure sits inside it — and {@code read} is the canonical
     * line this plan writes. Their difference is what tells «this reader could not read a run» from
     * «this round recorded none»: sharing one pattern between them made a list item, a quotation,
     * an indented line or a wrapped line vanish from both, and the reader then blamed the readiness
     * document for a run the plan does record (round-33 review, subagent P2-1; round-34 review,
     * subagent P2-4, each measured).
     *
     * <p>Takes the document as a String so that synthetic ones can drive it.
     */
    private static GateReading readNewestGate(String plan) {
        String prose = asProse(plan, "the batch plan");
        // EVERY level-3 section, not only the 確認 ones. A batch opened on the owner's instruction
        // is recorded under its own heading (「### R170 — …」), and a reader that only knew 確認
        // rounds skipped its gate line: the readiness document then agreed with 確認 35 巡目's
        // 8,197 while the tree the merge produced measured 8,246, and this lock was green through
        // it (R170's confirmation round, Codex P3-1 — the fifth time that line went stale).
        //
        // «Newest» is the LARGEST figure, not a position and not a round number. Document order
        // does not say it (this plan writes 確認 17〜24 ascending, then a batch section, then 35
        // down to 25 — measured: the first version of this reader took 確認 25 巡目 / 8,196 as the
        // newest) and a batch heading has no round number to compare. The unit total only grows
        // while a release is being built — every batch adds locks and retires none — so the
        // largest recorded run is the newest one. If a batch ever legitimately REMOVES tests this
        // lock fires and the ordering has to be made explicit in the document instead of inferred
        // here; that is the known limit of reading it this way.
        Matcher rounds = Pattern.compile("(?ms)^### (.+?)$(.*?)(?=^#{1,3} |\\z)")
                .matcher(prose);
        int claimed = 0;
        int read = 0;
        int largest = -1;
        List<String> newestSections = new ArrayList<>();
        String newestFigure = null;
        Set<Integer> roundsSeen = new java.util.LinkedHashSet<>();
        while (rounds.find()) {
            String heading = rounds.group(1).trim();
            Matcher asARound = Pattern.compile("^確認 (\\d+) 巡目").matcher(heading);
            if (asARound.find()) {
                int round = Integer.parseInt(asARound.group(1));
                if (!roundsSeen.add(round)) {
                    throw new HarnessBroken("the batch plan has more than one 確認 " + round
                            + " 巡目 section, so this reader cannot tell which one records that "
                            + "round's run");
                }
            }
            String section = rounds.group(2);
            // A PARAGRAPH, not a line: the figure may wrap onto the next one.
            for (String paragraph : section.split("\n[ \t]*\n")) {
                // THREE SPACES of indent at most — a four-space or tab indented line is a code
                // block, and counting it made this reader refuse a correct document (round-35
                // review: Codex P2, subagent P3-3). Table cells are reached by splitting the
                // line: the marker is not always in the first one (round-35 review, Codex P2).
                boolean claimsARun = false;
                for (String line : paragraph.split("\n")) {
                    for (String cell : line.split("\\|")) {
                        // The bullet or number must be THERE if extra spaces follow it, or three
                        // spaces plus a bullet's worth of padding reaches four — which is a code
                        // block (round-35 review: Codex P2, measured as claimed 2 / read 1).
                        if (Pattern.compile("^ {0,3}(?:[>*+\\-] *)?(?:\\d+[.)] *)?"
                                        + "\\*\\*ゲートと実測\\*\\*:").matcher(cell).find()) {
                            claimsARun = true;
                        }
                    }
                }
                if (claimsARun && paragraph.contains("全ユニット")) {
                    claimed++;
                }
            }
            Matcher gate = Pattern.compile(
                    "(?m)^\\*\\*ゲートと実測\\*\\*: \\*?\\*?全ユニット "
                            + "\\*?\\*?([0-9]{1,3}(?:,[0-9]{3})+) 本")
                    .matcher(section);
            List<String> figures = new ArrayList<>();
            while (gate.find()) {
                figures.add(gate.group(1));
            }
            if (figures.size() > 1) {
                throw new HarnessBroken(heading + " states " + figures.size()
                        + " gate figures " + figures + ", so this reader cannot tell which run the "
                        + "readiness document should be naming");
            }
            if (figures.size() == 1) {
                read++;
                int figure = Integer.parseInt(figures.get(0).replace(",", ""));
                if (figure > largest) {
                    largest = figure;
                    newestFigure = figures.get(0);
                    newestSections = new ArrayList<>(List.of(heading));
                } else if (figure == largest) {
                    // Several rounds can record the SAME run (確認 9・10・11 all state 8,182 —
                    // no lock was added), so the readiness document may name any of them.
                    newestSections.add(heading);
                }
            }
        }
        return new GateReading(List.copyOf(newestSections), newestFigure, claimed, read);
    }

    // WITHDRAWN (batch-30, 2026-10-20): a lock that compared the batch plan's newest control total
    // with the runner. Codex asked for it (batch-27 review, P3-2) because no lock reads the PLAN's
    // copies of that figure. Three rounds produced three ways for it to refuse a CORRECT document,
    // each measured by a reviewer:
    //
    //   1. 「every section with the largest gate figure」 — measured at ed56b8484: 22 of this
    //      plan's 48 sections shared a figure with another, because a batch that adds no lock
    //      records the same unit total, and each states its own control count. Two totals read →
    //      HarnessBroken → the whole gate red. (DATED, because the figure moves with every batch
    //      and a 「current」 one went stale in the commit that wrote it: batch-30 review, subagent
    //      P3-1.)
    //   2. 「the section the readiness document names」 — the provenance is NOT unambiguous under a
    //      tie: with batch 28 and 29 both at 8,299 and both at 2,312, pointing the readiness line at
    //      either one left every lock green (measured).
    //   3. 「the line-start phrasing」 — measured at ed56b8484: of the 31 sections with a gate
    //      paragraph, 19 write the total at the start of a line, 10 write it mid-line (batch 24 and
    //      batch 27 among them) and 2 split it across lines. So the form the lock required covered
    //      19 of 31, and the 12 it refused include batch 27. (NOT 「the batch immediately
    //      before」 — that was one batch off: the line-start requirement arrived in batch 29,
    //      and batch 28 is line-start. Batch-31 review, subagent P3-1.)
    //      (This line first said 「17 … and 2 state no total at all」. That was the 5f42623bd
    //      distribution with the denominator updated to 31 and the difference parked in a category
    //      that does not exist: NO gate paragraph lacks a total, in any revision. My own reader had
    //      taken a TABLE paragraph as the gate paragraph for 確認 31・32 巡目 — batch-30 review,
    //      subagent P2-2, and I reproduced both numbers.)
    //
    // Reading this document's numbers needs a model of markdown conventions that vary across 48
    // sections written over three weeks, and every version of that model has been a trap with the
    // whole gate behind it. The figure IS locked where it is load-bearing — the canon's copy
    // (theSweepsOwnControlCountIsTheRunners) and the readiness document's
    // (theReadinessControlCountIsTheRunners). The plan's per-batch copies are a record of what was
    // true at that batch; the gap is residual R212, not a lock.

    @Test
    @DisplayName("the withdrawn plan-figure lock stays withdrawn, with its three measured traps on the record")
    void thePlanFigureLockStaysWithdrawn() throws IOException {
        // THE WITHDRAWN CLAIM, not the fixed one. Three rounds produced three ways for a lock over
        // the batch plan's numbers to refuse a CORRECT document, each measured by a reviewer, and
        // each version looked right until someone counted this document's shapes. What stops the
        // fourth version is a lock on the WITHDRAWAL: the note has to be here, it has to name the
        // residual, and the method must not come back without someone deleting this first.
        //
        // It also keeps the unit total monotone. 「Newest is the largest figure」 — the inference
        // readNewestGate makes — breaks for a batch that REMOVES a test, which is recorded as R204
        // and which withdrawing a lock would have triggered.
        String own = read(Path.of(
                "src/test/java/jp/aegif/nemaki/evidence/ReleaseReadinessIsMeasuredTest.java"));
        // SPLIT, so this file contains the marker ONLY in the note. Written whole it appeared three
        // times — the note and this method's own two literals — and deleting the note left the lock
        // green while the slice below swallowed 41 KB of this method, whose own array literal then
        // satisfied every assertion (batch-30 review, subagent P1-1, measured: the THIRD
        // self-reference in this one method).
        String marker = "WITHDRAWN (batch-" + "30";
        assertTrue(own.contains(marker),
                "the withdrawal note is gone. If the plan-figure lock is being reinstated, read the "
                        + "three traps it names first and say in the note which one the new form "
                        + "answers — the gate is behind this reader");
        // SCOPED TO THE NOTE, not to the file: the trap names appear in THIS method's own array
        // literal, so a file-wide contains() was satisfied by the check itself and the control
        // measured nothing (NH5, DID NOT FIRE — the second self-reference in this method).
        String note = slice(own, marker, "\n\n    @Test", "the withdrawal note");
        // The DATED MEASUREMENTS too: putting them there was this batch's declared purpose, and
        // nothing held them (batch-31 review, subagent P3-2 — the note reduced to a stub that kept
        // only the three names and R212 stayed green).
        for (String trap : new String[] { "largest gate figure", "the readiness document names",
                "line-start phrasing", "ed56b8484", "19 write the total", "22 of this" }) {
            assertTrue(note.contains(trap), "the withdrawal note no longer names the trap 「" + trap
                    + "」, so the next version of this lock will rediscover it with the whole gate "
                    + "behind it: " + note);
        }
        assertTrue(note.contains("R212"),
                "the withdrawal note no longer names the residual that records the gap, so the gap "
                        + "reads as 「nobody thought of it」 rather than 「measured three times」");
        // THE WHOLE FILE for the forbid half, the NOTE for the require half. A declaration cannot
        // appear inside a comment, so reading the note made this arm unfirable — measured: the
        // method resurrected after the note, at the end of the class, or above it all passed
        // (batch-30 review: Codex P2-1, subagent P2-1). The literals are SPLIT so this check is not
        // satisfied by itself, which is the other half of the same trap.
        // A DECLARATION, read the way the runner's own reader reads one: 「void」 then whitespace
        // then the name. An exact-string match let a line-broken signature past and tripped on a
        // bare comment mention (batch-31 review: Codex P2, subagent P3-3 — the two readers of 「is
        // this method declared」 disagreed). Comments out, so prose about the method is not a
        // declaration.
        //
        // KNOWN LIMITS, measured and not closed. This arm forbids the two names the lock had, by
        // their declaration, in THIS file, returning void:
        //   - a THIRD name is green (batch-31 review, subagent P2-3 — the record said that escape
        //     was handled and it was not). An unbounded name cannot be forbidden by enumeration.
        //   - a unicode escape inside the name (「the\u004eamed…」) compiles to the forbidden name
        //     and does not match the pattern (batch-32 review, subagent P3-1). A regex cannot decode
        //     what javac decodes before lexing.
        //   - the same method declared in ANOTHER test class is green: this reads one file.
        // WHAT THIS ARM CATCHES, and only this: the two names that lock had, declared in THIS
        // file, returning void. The sentence that used to sit here — 「what the require half still
        // catches is the canon being changed to say the lock is back」 — became false in batch 38,
        // when the canon side was reduced to a mark and the prose around it was deliberately left
        // unread. It stayed here for a batch while the record said the opposite (batch-37 review,
        // subagent P2-2).
        // LITERAL TEXT OUT TOO, not only comments: withoutComments keeps the contents of strings
        // (its own javadoc says so, because an assertion about a query string needs them), so a
        // QUOTED mention of the declaration — in an assertion message, a fixture, a text block —
        // refused a file with no such method. HarnessBreakageIsNotAFiringTest records the same
        // reason for the same choice (batch-32 review: both reviewers, each measured it).
        String code = jp.aegif.nemaki.util.test.JavaSource.withoutLiteralText(own);
        String name = "the" + "NamedBatchSectionsControlCountIsTheRunners";
        String nameToo = "the" + "NewestBatchSectionsControlCountIsTheRunners";
        assertFalse(Pattern.compile("void\\s+(?:" + name + "|" + nameToo + ")\\s*\\(")
                        .matcher(code).find(),
                "a lock over the batch plan's control total is back while the withdrawal note is "
                        + "still here. One of the two is wrong — decide, do not keep both");

        // AND the residual says so, in the canon — on ONE LINE, compared RAW and WHOLE.
        //
        // ROUND AFTER ROUND over this one statement, and what failed every time was reading it as
        // prose. Batch 39 moved it out of the residual table onto a line of its own, which closed
        // eight measured escapes; what batch 39 got wrong is that it compared the line AFTER
        // asProse had taken the inline code out of it, so 「whole line」 was not whole. Measured
        // on this reader (batch-39 review: Codex P2 ×2, subagent P2-1 and P2-2 — one of the
        // subagent's eight rows did not reproduce, see the record):
        //   - 「`例:` + the line」 refused a correct canon — the span came out and what was left
        //     equalled the statement, so the canon 「said it twice」.
        //   - the counter-line written inside backticks did NOT refuse, while the canon's own §5
        //     promises that adding that line turns the lock red. The canon writes it in backticks
        //     there, which is why its prose does not trip the lock — and why the promise was
        //     stronger than the code.
        //   - an example inside a fence indented four spaces — which is where an example under a
        //     bullet lands, and §5 IS a bulleted section — satisfied the lock ON ITS OWN. So did
        //     an example merely indented four spaces, and a statement inside a code span that
        //     opens on one line and closes on another.
        //
        // SO THERE IS NO CONTEXT LEFT TO GET WRONG. This reader skips nothing — not a fence, not
        // an indented block, not a code span, not an HTML comment, not a section. Every one of
        // those rules was an escape of its own, and none is needed for the STATEMENT: a quotation,
        // a prefix, an emphasis, a strike-through, a trailing space and an indent all make a line
        // that is not equal to it. The constant's javadoc lists the rounds.
        //
        // AND THE TWO ARMS NO LONGER SHARE ONE COMPARISON. 「exactly one line says it」 and 「no
        // line takes it back」 cannot both be fail-closed with the same equality: for the
        // statement a near miss must NOT satisfy the lock, and for the retraction a near miss must
        // NOT slip past it. Shapes whose RENDERING is identical — leading spaces, a trailing space
        // or tab, a NO-BREAK SPACE, a lone CR line ending, a decomposed 「げ」 — were measured
        // green on the retraction side (batch-41 and batch-43 reviews, both reviewers, against the
        // real canon). So the retraction is compared as a NEAR-COPY: whitespace, controls and
        // formatting characters taken out, anywhere in the line. The statement stays strict, and
        // is counted as a near-copy a SECOND time, which only ever refuses.
        //
        // WHAT THIS LOCK NO LONGER CLAIMS, said plainly, because a withdrawal is a claim too:
        //   - WHERE the line sits. A section reader was tried and was defeated by one sentence
        //     mentioning 「## 5. 測定」 elsewhere (batch-40 review, subagent P2-2). A second copy
        //     anywhere still refuses; a single copy that MOVED does not.
        //   - the canon contradicting this line IN PROSE. It may, and this lock stays green.
        //   - a retraction written behind a character this reader does not remove — a 「* 」
        //     bullet, a quotation mark, a Hangul filler, a blank braille pattern, or one of the
        //     SEVEN that advance 0.00px in all five stacks (U+034F, U+17B4, U+17B5, U+180B to
        //     U+180D, U+FFFC) — is
        //     still not read. The formatting characters were in this list until batch 51 and the
        //     VARIATION SELECTORS until batch 53, each removed when a measurement showed the
        //     canon was sabotageable with one invisible character (batch-50 and batch-52 reviews,
        //     subagent P2-1 both times). The seven stay because no JDK predicate or block gathers
        //     them, which is a narrower claim than 「the JDK cannot say invisible」 — that one was
        //     wrong, and the two blocks are how 256 of them were closed. SEVEN hand-written sets
        //     drifted before this rule (batches 42 to 53, both reviewers).
        // What catches an actual resurrection is the arm above, which reads this file's own code —
        // and that arm carries its own limits, listed with it: a third name is green, a unicode
        // escape inside either name is green, and the same method declared in another test class
        // is green.
        String canon = read(CANON);
        theCanonSaysTheWithdrawalStands(canon);
    }

    /** How many lines of a document state the withdrawal, and how many take it back. */
    private record Declared(long says, long looksLikeIt, long takesBack) { }

    /**
     * BOTH arms of the withdrawal check, over a whole document, so synthetic ones can drive them.
     *
     * <p>Counted over the WHOLE canon, which is what makes a second copy anywhere loud. The
     * section the statement sits in is no longer asserted — see the constant's javadoc for why,
     * and for what that gives up.
     */
    private static void theCanonSaysTheWithdrawalStands(String canon) {
        Declared found = declarationsIn(canon);
        assertEquals(1, found.says(),
                "the canon has " + found.says() + " lines whose text, normalised, equals 「"
                        + WITHDRAWN_LINE + "」, and this lock needs exactly one"
                        + (found.says() == 0 && found.looksLikeIt() > 0
                                ? " — but " + found.looksLikeIt() + " line(s) differ from it by "
                                        + "NOTHING THAT CARRIES MEANING HERE (whitespace, a "
                                        + "control, or a formatting character such as a "
                                        + "zero-width space, a BOM or a soft hyphen). That is "
                                        + "almost certainly the line you meant: compare the bytes"
                                : "") + ". If the plan-figure "
                        + "lock was reinstated, delete the withdrawal note and this method. "
                        + "Otherwise the line is compared WHOLE and WITHOUT TRIMMING: no leading "
                        + "or trailing space, no emphasis, no backticks, nothing else on the "
                        + "line. Prose saying it another way is not read, deliberately: every "
                        + "round of findings came out of reading this statement any other way");
        assertEquals(1, found.looksLikeIt(),
                "the canon has " + found.looksLikeIt() + " lines that differ from 「"
                        + WITHDRAWN_LINE + "」 by nothing that carries meaning here — whitespace, "
                        + "controls, formatting characters — and this lock needs exactly one, "
                        + "the declaration itself. More than one means a line nothing in this "
                        + "reader can tell from the declaration, which is a canon it must not "
                        + "vouch for: a second copy behind a zero-width space measured 0.00px of "
                        + "difference on the page. This count never makes a line COUNT as the "
                        + "declaration — the arm above does that, and does it raw, because "
                        + "trimming the declaration let 「the declaration with three spaces after "
                        + "it」 be the declaration. To document the format, put something VISIBLE "
                        + "on the line: 「例: 」, backticks, a sentence around it");
        assertEquals(0, found.takesBack(),
                "the canon has " + found.takesBack() + " lines which differ from 「"
                        + REINSTATED_LINE + "」 by nothing that carries meaning here, while this "
                        + "lock asserts the withdrawal stands. One of the two is wrong — decide, "
                        + "do not keep both. A quotation inside a sentence is not it, and neither "
                        + "is a line with a VISIBLE prefix, which is how §5 explains the format; "
                        + "a copy on a line of its own IS it, fenced or not, and so is one behind "
                        + "a zero-width space or inside the line");
    }

    /**
     * The two statements counted in a document's lines — the declaration raw, and both of them
     * again as NEAR-COPIES.
     *
     * <p>NOTHING IS SKIPPED, AND NOTHING IS STRIPPED ON THE STATEMENT SIDE. Not a fenced
     * block, not an indented one, not a code span, not an
     * HTML comment, not a section. Every one of those rules was an escape of its own (see the
     * constant's javadoc), and none of them is needed: a quotation, a prefix, an emphasis, a
     * strike-through and a trailing space all make a line that is not equal to the statement.
     *
     * <p>Lines are split on every ending CommonMark knows, and compared after Unicode
     * normalisation, so CRLF, a lone CR and a decomposed character all read as what they render
     * as. Those three were each measured as a silent 「the statement is not there」 (batch-40
     * review, Codex P2; batch-41 review: Codex P2, subagent P2-3 and P3-5).
     *
     * <p>THE DECLARATION IS COUNTED TWICE, AND THE ASYMMETRY IS THE POINT. Raw, it must be
     * there exactly once: {@code strip()} here made 「the statement with three spaces after it」
     * count as the statement (batch-40 review, Codex P2), so nothing is trimmed. As a near-copy —
     * with every character that carries no meaning here taken out, anywhere in the line — it must
     * ALSO be there exactly once, and that count only ever REFUSES. A line nothing in this reader
     * can tell from the declaration is a line it must not vouch for, and a second copy behind a
     * zero-width space measured 0.00px of difference (batch-50 review, subagent P2-1). The
     * retraction is asked as a near-copy only, which is the wider side of the same asymmetry
     * (batch-41 review, subagent P2-3).
     *
     * <p>NOTHING HERE MODELS WHAT A READER SEES, and three batches tried. 「Invisible」 was the
     * premise each time, and it was measured false from both ends: CSS Text §5.5 makes every BK
     * and NL character a forced line break 「regardless of the white-space value」 and §4 makes a
     * control character a visible glyph, while in Chromium NONE of them breaks a line — that part
     * reproduces against a U+000A control — and their WIDTHS differ by character AND by font:
     * U+000C 0.00px in five stacks, U+000B 5.33px in five, U+0085 16.00px in five, U+2028 and
     * U+2029 up to 20.00px in an emoji-first stack. Ranges were written here twice and both
     * disagreed with the canon's §5; the rule does not depend on any of these numbers (batch-52
     * review, subagent P2-3; batch-53 review, subagent P3-3). What this reader claims is
     * only that a character is or is not one that carries meaning here: {@link #isPadding}'s
     * class plus the formatting characters.
     */
    private static Declared declarationsIn(String text) {
        long says = 0;
        long looksLikeIt = 0;
        long takesBack = 0;
        // THE EXPECTED SIDE GOES THROUGH THE SAME TWO STEPS. It did not for two batches, and
        // the reason it mattered was structural rather than visible: if a constant were ever
        // saved in a decomposed form, the retraction count could not reach one at all and a
        // canon that takes the withdrawal back would read green. The fixtures could not catch
        // that, because they build their lines FROM the same constants (batch-52 review,
        // subagent P3-3).
        String declarationItself = asNearCopy(WITHDRAWN_LINE);
        String retractionItself = asNearCopy(REINSTATED_LINE);
        // EVERY LINE ENDING COMMONMARK HAS, AND ONLY THOSE: LF, CR, CRLF (CommonMark §2.1).
        // Splitting on \n alone read a document whose lines end in a lone CR as ONE line, so its
        // statement counted as absent — 「I could not read this」 reported with the value of
        // 「it is not there」 (batch-41 review: Codex P2, subagent P2-3).
        //
        // NOT \R, which batch 47 used for one batch. \R also breaks at U+000B, U+000C, U+0085,
        // U+2028 and U+2029, and A READER NEVER SEES A BREAK AT THOSE. Measured in a browser, in
        // a paragraph with white-space:normal and again with pre-wrap: all five render as ONE
        // line — the same height as a control with no such character — while a <br> renders as
        // two. So \R was wrong in both directions at once (batch-47 review, Codex P2): a line
        // reading 「prefix + U+000C + the retraction」 became a retraction line and turned a
        // CORRECT canon red (over-refusal, which this tree weighs the same as fail-open), and
        // 「prefix + U+000C + the statement」 satisfied 「exactly one line says it」 when no line
        // said it (fail-open, on the strict arm).
        //
        // AND NOTHING HERE MODELS WHAT A READER SEES. Three batches tried: \R split on the
        // other line separators (batch 47), CommonMark's endings with those five called a limit
        // (batch 48), and removing the five before comparing because they were 「invisible」
        // (batch 50). The last premise was false in both directions at once — CSS Text §5.5
        // makes every BK and NL character a forced line break 「regardless of the white-space
        // value」 and §4 makes a control character a visible glyph, while in Chromium none of
        // them breaks a line and their widths differ by character and by font — U+000C at
        // 0.00px, the others between 4.45 and 16.00px (batch-50 review: Codex P2 from the specs,
        // subagent P2-1 from the widths; batch-52 review, subagent P2-3, who measured U+000C and
        // five stacks). What is invisible is something else — U+200B measured 0.00px — and that
        // is where the hole was.
        //
        // So the line is split where CommonMark splits it, compared as written, and a line that
        // differs from the declaration only by characters carrying no meaning here is counted as
        // a NEAR-COPY and refuses the canon. No claim is made about rendering.
        for (String line : text.split("\r\n|\n|\r", -1)) {
            // NORMALISED, so a decomposed 「げ」 is the same character it renders as. A canon saved
            // in NFD counted zero statements and the refusal named causes none of which applied
            // (batch-41 review, subagent P3-5).
            String rendered = Normalizer.normalize(line, Normalizer.Form.NFC);
            if (WITHDRAWN_LINE.equals(rendered)) {
                says++;
            }
            // NEAR-COPIES, COUNTED SEPARATELY AND ONLY EVER TO REFUSE. The statement itself stays
            // RAW: trimming it is batch 40's measured defect, where strip() let 「the statement
            // with three spaces after it」 count AS the statement. So this counter never makes a
            // line into the declaration — it only refuses a canon that contains a line differing
            // from it by nothing that carries meaning here. A second copy behind a zero-width
            // space was measured at 0.00px of difference on the page while the reader answered
            // 「exactly one」 (batch-50 review, subagent P2-1), and a second copy behind one of
            // the JDK's other line separators did the same (batch-49 review, Codex P2).
            String nearCopy = asNearCopy(rendered);
            if (declarationItself.equals(nearCopy)) {
                looksLikeIt++;
            }
            // THE RETRACTION IS ASKED WIDER, on purpose: see the arms' comment. It is asked on
            // the SAME near-copy, so a retraction behind a zero-width space, a variation
            // selector, or inside its own line is read. What is NOT read is a line carrying
            // something this reader does not remove — a visible prefix, or one of the seven
            // invisible characters no JDK predicate gathers — and a line that SUBSTITUTES a
            // look-alike character rather than adding one. Both are listed with the fixtures and
            // in the canon's §5. 「The one limit left」 stood here for a batch and was wrong in
            // three ways (batch-52 review, subagent P2-4).
            if (retractionItself.equals(nearCopy)) {
                takesBack++;
            }
        }
        return new Declared(says, looksLikeIt, takesBack);
    }

    /**
     * Whether a character is whitespace or a control — part of what carries no meaning here.
     * {@link #carriesNoMeaningHere} is the whole of it: this, plus the formatting characters,
     * plus the two variation-selector blocks.
     *
     * <p>SEVEN DESIGNS, AND EVERY HAND-WRITTEN SET FAILED. Four are listed here by batch; the
     * fifth was the five line separators of batch 50, the sixth their removal in batch 51, and
     * the seventh the two characters batch 52 recorded as the whole of the remaining fail-open
     * when a sweep found 263. 「FOUR DESIGNS」 stood here while the prose elsewhere said six
     * (batch-52 review, subagent P3-4). 「whitespace」 left a NO-BREAK SPACE —
     * what a paste out of a rendered page produces — hiding a retraction (batch-42 review). The
     * categories including {@code Cf} were too wide: the prepended concatenation marks ARE
     * formatting characters and they render, so taking them off refused a correct canon (batch-43
     * review). A closed list of 36 was too narrow by scores of invisible characters, reopening the
     * first hole (batch-44 review). The categories MINUS a hand-written exception set was both:
     * the set was missing four of the thirteen marks Unicode actually has, and — the finding that
     * ended the line of designs — ADDING THE SAME CHARACTER TO BOTH COPIES of that set passed
     * every test while reopening the first hole again (batch-45 review: Codex P2, subagent P2-1
     * and P2-4, each measured). A lock over two copies of a hand-written set measures that they
     * agree, never that either is right.
     *
     * <p>THE RULE IS TWO TERMS, because the third was dead. {@code Character.isWhitespace} is
     * entirely subsumed by {@code isSpaceChar} and {@code Cc}: measured over every code point,
     * there are ZERO where only {@code isWhitespace} is true (and 59 the other way). A control
     * that removed it measured DID NOT FIRE, which is how the redundancy showed up (batch 46).
     *
     * <p>So there is no hand-written set. {@code Cf} was not taken off AT ALL for five batches —
     * the format category holds both the marks that render and the ones that do not, and nothing
     * in the JDK tells them apart — and batch 51 moved that decision: the ones that do not render
     * measured 0.00px, which left the canon sabotageable with one invisible character, so
     * {@link #withoutWhatCarriesNoMeaningHere} removes the whole category and the canon's §5
     * records what that costs.
     * {@link #thePaddingClassIsTheCategoriesItNames} counts what this method takes off, per
     * category, over every code point. It does NOT re-express the rule: a second copy of the
     * expression measures that the two agree, and batch 46's did exactly that — adding one term
     * to both copies passed every test (batch-46 review, subagent P2-1, measured). 「so neither
     * side can drift」 stood here for a batch and was true only of one-sided drift.
     *
     * <p>KNOWN LIMITS, both directions, and the canon's §5 carries them:
     *   <ul>
     *   <li>A retraction behind a character this reader does not remove — a combining grapheme
     *   joiner, a Khmer vowel inherent, a Mongolian free variation selector, an object
     *   replacement character, a Hangul filler, a blank braille pattern — is NOT read. SEVEN of
     *   them advance 0.00px in five font stacks, so that part is a fail-open a reader cannot see;
     *   the formatting characters were here until batch 51 and the variation selectors until
     *   batch 53. That is the fail-open
     *   side, and it is the price of never refusing a correct canon.
     *   <li>A character that renders is still taken off, and there are TWO: {@code U+1680} OGHAM
     *   SPACE MARK draws a line in an Ogham font (batch-45 review, subagent P3-7), and
     *   {@code U+0000} becomes a visible {@code U+FFFD} in CommonMark's input processing
     *   (batch-46 review, Codex P2). Both are over-refusal — a canon carrying one in front of a
     *   line that is not the retraction has that line read as one. 「one character」 stood here
     *   for a batch; excluding either by hand is the exception-set path four batches measured
     *   wrong, so they are recorded instead.
     *   <li>A character in none of these categories is not taken off: a Hangul filler and a
     *   blank braille pattern, which ADVANCE 13.55px and 10.94px at 16px. The braille blank draws
     *   NOTHING in all five stacks; the filler draws nothing in four of five and 1,616 pixels in
     *   serif. So 「not removed」 does not mean 「visible」, and it does not mean 「invisible」
     *   either — the two questions are separate and both are measured (batch-54 review, subagent
     *   P2-5; batch-56 review, subagent P3-4, who caught the unqualified half of this sentence
     *   twice). And the seven that advance 0.00px as well, in all five stacks (U+034F, U+17B4,
     *   U+17B5, U+180B to U+180D, U+FFFC) — narrowed to system-ui alone the set is nine, because
     *   U+F860 and U+F861 advance 0.00px there and leave no ink anywhere (batch-58 review,
     *   subagent P2-4). The variation selectors were in this list until batch 53.
     *   </ul>
     */
    private static boolean isPadding(int codePoint) {
        return Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.CONTROL;
    }

    /**
     * A line with everything that carries no meaning here taken out, anywhere in it — what
     * both near-copy counts are taken over.
     *
     * <p>THE STATEMENT IS NOT TRIMMED and this is, because the two arms ask opposite questions: a
     * near miss must not SATISFY 「exactly one says it」, and must not SLIP PAST 「none takes it
     * back」. BY CODE POINT, not by {@code char}: a {@code char} scan cannot see a character
     * outside the BMP, because both halves of one are unpaired surrogates (batch-43 review: Codex
     * P2, subagent P2-1).
     *
     * <p>THE SCAN IS LOAD-BEARING NOW, and that paragraph said the opposite. It said the scan
     * was inert and that no control measured it, with the instruction 「if a category with a
     * supplementary member is ever added, this paragraph has to go」 — and batch 51 added one:
     * {@code Cf} has 120 to 127 supplementary members depending on the JDK. A {@code charAt} scan
     * would leave a tag character or a supplementary variation selector in place as two unpaired
     * surrogates. {@link #whatCarriesNoMeaningHereIsTheFiveCategoriesItNames}'s per-category arm
     * is what measures it: the FORMAT count would fall by every supplementary member — 170 to
     * 43 on the JDK 21 this was measured on, 161 to 41 on the JDK the earlier reviewer used
     * (batch-51 review, subagent P2-3, who carried out the instruction the paragraph gave
     * itself; batch-55 review, subagent P3-4, who measured that these are the JDK's numbers
     * and not this tree's).
     */
    private static String withoutWhatCarriesNoMeaningHere(String line) {
        StringBuilder kept = new StringBuilder(line.length());
        line.codePoints().forEach(codePoint -> {
            if (!carriesNoMeaningHere(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
        });
        return kept.toString();
    }

    /**
     * A line reduced to what carries meaning here: COMPATIBILITY-normalised, with the characters
     * that carry no meaning taken out, and normalised again afterwards.
     *
     * <p>TWICE, BECAUSE COMPOSITION DOES NOT CROSS A REMOVED CHARACTER. 「げ」 written as け plus
     * a zero-width space plus the combining dakuten stayed け + mark through one pass, so the line
     * was not a near-copy and a second copy of the declaration went uncounted — and the same
     * shape on the retraction side read as no retraction (batch-51 review, Codex P2, measured
     * both ways).
     *
     * <p>COMPATIBILITY, not canonical: NFC leaves U+FB01 LATIN SMALL LIGATURE FI alone, so
     * 「plan-ﬁgure-lock」 read as no retraction while a reader sees the word (batch-52 review,
     * Codex P2). NFKC folds it. What that costs is measured and recorded in the canon's §5: a
     * full-width colon now makes a NEAR-COPY, so 「write it with a full-width colon」 is no longer
     * one of the ways to quote the line — 「例: 」 and backticks still are.
     */
    private static String asNearCopy(String line) {
        return Normalizer.normalize(
                withoutWhatCarriesNoMeaningHere(Normalizer.normalize(line, Normalizer.Form.NFKC)),
                Normalizer.Form.NFKC);
    }

    /**
     * Whether one code point carries no meaning here: whitespace, a control, a formatting
     * character, or a VARIATION SELECTOR.
     *
     * <p>THE TWO BLOCKS ARE THE JDK'S OWN NAMES, not a hand-written set — which is why they could
     * be added at all. 「The JDK cannot say invisible」 stood in this class for a batch and was
     * too strong: a sweep of every assigned code point outside the three classes found 263 that
     * BOTH advance 0.00px AND leave no ink in five font stacks — neither condition alone gives
     * that number (batch-54 review, subagent P2-3) — and 256 of those are exactly
     * {@code VARIATION_SELECTORS} and {@code VARIATION_SELECTORS_SUPPLEMENT} (batch-52 review,
     * subagent P2-1 and P2-2, measured; the blocks are 16 + 240 = 256 code points on this JDK,
     * all {@code Mn}).
     *
     * <p>WHAT IT COSTS, measured: a variation selector AFTER a base changes which glyph is drawn —
     * 葛 with U+E0100 against U+E0101 differ by 499 pixels — so a line carrying one becomes a
     * near-copy of the line without it, and the canon is refused. That is an over-refusal of a
     * difference a reader CAN see. It is accepted here because the other direction is a retraction
     * hidden behind an invisible character, and because no line of this canon carries one.
     *
     * <p>SEVEN ARE LEFT — measured as 「advance 0.00px and no ink in ALL FIVE named stacks」 —
     * recorded with the fixtures that fix them: U+034F, U+17B4, U+17B5, U+180B, U+180C, U+180D
     * and U+FFFC. The first six are {@code Mn} outside those blocks and the last is {@code So},
     * so no JDK predicate or block gathers them without a hand-written set — and seven of those
     * drifted over seven batches. ON ONE STACK THE SET IS LARGER: U+F860 and U+F861 meet the
     * condition in system-ui (batch-58 review, subagent P2-4).
     */
    private static boolean carriesNoMeaningHere(int codePoint) {
        if (isPadding(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
            return true;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.VARIATION_SELECTORS
                || block == Character.UnicodeBlock.VARIATION_SELECTORS_SUPPLEMENT;
    }

    /**
     * Every composed character of a line, split by the given string — NFD on each character, with
     * the separator put between the base and its marks.
     *
     * <p>Used to build the shape that showed normalising only ONCE was not enough: composition
     * does not cross a removed character.
     */
    private static String splitEveryComposedCharacterWith(String separator, String line) {
        StringBuilder split = new StringBuilder();
        line.codePoints().forEach(codePoint -> {
            String decomposed = Normalizer.normalize(new String(Character.toChars(codePoint)),
                    Normalizer.Form.NFD);
            if (decomposed.codePointCount(0, decomposed.length()) == 2) {
                int base = decomposed.codePointAt(0);
                split.appendCodePoint(base).append(separator)
                        .append(decomposed.substring(Character.charCount(base)));
            } else {
                split.appendCodePoint(codePoint);
            }
        });
        return split.toString();
    }

    /** A synthetic canon: the given lines, one per line. */
    private static String canonWith(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /**
     * A line IS the statement or it is not — driven with every shape these rounds produced.
     *
     * <p>ATTRIBUTION IS WHAT THE RUNNER DECLARES, and nothing finer. It was written finer than
     * that four rounds running and was wrong every time, including in the version that said it
     * would not be (batch-38 review, subagent P2-1; batch-39 review, Codex P3; batch-40 review:
     * Codex P3, subagent P2-4). The runner's own expect_fail lists are the record; this javadoc
     * does not restate them.
     */
    @Test
    @DisplayName("the canon's withdrawal statement is one raw line, counted once in the document")
    void theWithdrawalStatementIsReadAsAWholeLine() {
        assertDoesNotThrow(() -> theCanonSaysTheWithdrawalStands(canonWith("前置き", WITHDRAWN_LINE)),
                "the statement on a line of its own was not read");
        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands("前置き\r\n" + WITHDRAWN_LINE + "\r\n"),
                "a document saved with CRLF did not read the same");
        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands("前置き\r" + WITHDRAWN_LINE + "\r"),
                "a document whose lines end in a lone CR read as one line, so its statement "
                        + "counted as absent — a read failure reported as an absence");
        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands(
                        java.text.Normalizer.normalize("前置き\n" + WITHDRAWN_LINE + "\n",
                                java.text.Normalizer.Form.NFD)),
                "a document saved in NFD counted no statement, and the refusal named causes none "
                        + "of which applied");

        // NOT THE STATEMENT, and none of these needs a rule of its own: each is simply a different
        // line. The first six broke substring and token designs (batches 37-38); the rest broke
        // the skipping machinery (batches 39-40), and here they are all the same answer.
        for (String written : new String[] {
                "「" + WITHDRAWN_LINE + "」は誤り",
                "~~" + WITHDRAWN_LINE + "~~",
                "**" + WITHDRAWN_LINE + "**",
                WITHDRAWN_LINE + "ではない",
                "- 取り下げ:  plan-figure-lock（R212）",
                "- 取り下げ： plan-figure-lock（R212）",
                "`例:` " + WITHDRAWN_LINE,
                "`" + WITHDRAWN_LINE + "`は誤り。錠は現役",
                WITHDRAWN_LINE + "   ",
                " " + WITHDRAWN_LINE,
                "   " + WITHDRAWN_LINE,
                "\t" + WITHDRAWN_LINE,
                " \t" + WITHDRAWN_LINE,
                "    " + WITHDRAWN_LINE }) {
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(canonWith("前置き", written)),
                    "a line reading 「" + written.replace("\t", "⇥") + "」 was taken for the "
                            + "statement, which is compared raw and whole");
        }

        // AND A PREFIX THAT CARRIES NO MEANING HERE IS STILL NOT THE STATEMENT, raw being raw:
        // batch 47 split on \R, so 「prefix + U+2028 + the statement」 counted as the statement
        // while NO line was it — fail-open on the strict arm (batch-47 review, Codex P2). What
        // catches a shape like this is the NEAR-COPY count, not the raw one, and the arms below
        // assert both: nothing here counts as the declaration, and a canon carrying such a line
        // beside the real one is refused.
        for (String separator : new String[] { "\u2028", "\u2029", "\u0085", "\u000b", "\u000c" }) {
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith("前置き" + separator + WITHDRAWN_LINE)),
                    "a line reading 「prefix + U+"
                            + String.format("%04X", separator.codePointAt(0))
                            + " + the statement」 was taken for the statement. It is one line to "
                            + "CommonMark and one line to a reader, so the statement is not on a "
                            + "line of its own and this canon must refuse");
        }

        // A SECOND COPY ANYWHERE, including in a fenced example, is the price of having no context
        // rules — and the canon's §5 says so instead of promising an exclusion this reader cannot
        // make safely. Each of these shapes satisfied or refused the lock wrongly under some
        // earlier design (batches 39-40, both reviewers): a fence at indent 0, 4 and 6, a nested
        // fence, a tilde fence, an indented block, a multi-line code span, an HTML comment.
        for (String[] example : new String[][] {
                { "```", WITHDRAWN_LINE, "```" },
                // A fence indented four spaces, with the copy itself at the line start: the copy
                // IS a copy, so this refuses. An indented COPY would not be one — the leading
                // spaces make it a different line — and that shape is in the list above. The note
                // sat on this row for a batch while describing that one (batch-41 review,
                // subagent P3-6).
                { "    ```", WITHDRAWN_LINE, "    ```" },
                { "````", "```", WITHDRAWN_LINE, "```", "````" },
                { "~~~", WITHDRAWN_LINE, "~~~" },
                { "`例:", WITHDRAWN_LINE, "`" },
                { "<!--", WITHDRAWN_LINE, "-->" } }) {
            String[] canon = new String[example.length + 1];
            canon[0] = WITHDRAWN_LINE;
            System.arraycopy(example, 0, canon, 1, example.length);
            assertThrows(AssertionError.class, () -> theCanonSaysTheWithdrawalStands(canonWith(canon)),
                    "a second copy of the statement was accepted, so 「exactly one」 is not what "
                            + "this reader counts");
        }

        // EVERY CHARACTER THAT CARRIES NO MEANING HERE, AND THE SAME TWO ANSWERS FOR ALL OF THEM.
        // Whitespace, controls and formatting characters: the four BK separators and U+0085 that
        // three batches modelled as 「invisible」 (most of them are not: their widths run from
        // 0.00px for U+000C to 16.00px for U+0085 and depend on the font — measured in five
        // stacks), a space, a tab, a NO-BREAK SPACE, and the ones that really
        // are invisible (U+200B measured 0.00px, U+FEFF, U+00AD, U+200E, and a prepended
        // concatenation mark, which is FORMAT and renders).
        //
        // A SECOND LINE differing from the declaration by only these is a NEAR-COPY and refuses
        // the canon: it was accepted for two batches, and for the invisible ones since the class
        // was first written (batch-49 review, Codex P2; batch-50 review, subagent P2-1, who
        // measured six of them). THE SAME LINE AS THE ONLY DECLARATION also refuses, because the
        // declaration itself is compared RAW — trimming it is batch 40's defect — and the
        // near-copy count is what makes that refusal say why.
        for (String carriesNoMeaning : new String[] { "\u2028", "\u2029", "\u0085", "\u000b", "\u000c", "\u0020", "\u0009",
                "\u00a0", "\u200b", "\ufeff", "\u00ad", "\u200e", "\u0600",
                // AND THE VARIATION SELECTORS, taken by block since batch 53: a
                // plain one and one from the supplement, where the 240 ideographic
                // selectors live.
                "\ufe0f", "\udb40\udd00" }) {
            String name = "U+" + String.format("%04X", carriesNoMeaning.codePointAt(0));
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, carriesNoMeaning + WITHDRAWN_LINE)),
                    "a second line differing from the declaration by only " + name + " was not "
                            + "counted. Nothing about this reader can tell that line from the "
                            + "declaration, so a canon it cannot vouch for was vouched for");
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(canonWith("前置き",
                            WITHDRAWN_LINE.substring(0, 6) + carriesNoMeaning
                                    + WITHDRAWN_LINE.substring(6))),
                    "a line that is the declaration with " + name + " inside it was accepted as "
                            + "the declaration. The declaration is compared raw, so this canon "
                            + "does not state it — and the refusal has to say that rather than "
                            + "listing causes that do not apply");
        }

        // AND COMPOSITION DOES NOT CROSS A REMOVED CHARACTER, so the removal is normalised again
        // afterwards: 「げ」 written as け + a zero-width space + the combining dakuten stayed
        // け + mark through one pass, and the second copy went uncounted (batch-51 review, Codex
        // P2). The shape arrives from an editor that decomposes, or from a paste.
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        splitEveryComposedCharacterWith("\u200b", WITHDRAWN_LINE))),
                "a second copy whose composed characters were split by a zero-width space was not "
                        + "counted. Normalisation does not compose across a removed character, so "
                        + "the removal has to be normalised again afterwards");

        // AND AN EXAMPLE THAT IS NOT THE LINE ITSELF is how the format gets documented: a prefix,
        // a sentence around it, or a line with anything else on it.
        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        "```", "例: " + WITHDRAWN_LINE, "```", "本文では `" + WITHDRAWN_LINE + "` と書きます")),
                "a canon that states it once and describes the format without reproducing the line "
                        + "was refused");

        // NONE AT ALL, and TWO, are both documents this reader will not vouch for.
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith("前置き", "後書き")),
                "a canon that never states the withdrawal passed");
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE, WITHDRAWN_LINE)),
                "two lines stating the withdrawal were accepted as one");
    }

    /**
     * The near-copy is the per-character answer applied everywhere — and the removal only deletes.
     *
     * <p>THIS DRIVES THE FUNCTIONS THE READER CALLS. The arm that probes one code point at a time
     * guards {@link #carriesNoMeaningHere}; batch 53 moved the near-copy up into {@link
     * #asNearCopy} and batch 54 found that nothing touched it. Measured on a copy of this reader:
     * a single line inserted there — {@code toUpperCase(Locale.ROOT)}, {@code toLowerCase},
     * {@code replace("-", "")}, or NFKD in place of NFKC — left 0 OF 5 LOCKS RED, and each
     * refuses a correct canon (batch-53 review: Codex P2-1, subagent P2-1).
     *
     * <p>TWO ARMS, AND EACH ONE IS TRUE OF A DIFFERENT FUNCTION:
     *   <ul>
     *   <li>THE REMOVAL ONLY DELETES. {@link #withoutWhatCarriesNoMeaningHere} returns a
     *   subsequence of what it is given, always. 「The near-copy is a deletion of the normalised
     *   line」 was the claim here for a batch and it is FALSE of the pipeline: the outer
     *   normalisation COMPOSES, so 「け + U+200B + the combining dakuten」 comes back as げ, which
     *   the normalised line does not contain. The arm was green only because no corpus line had a
     *   base, a removable character and a combining mark in that order — a shape this class
     *   already uses as a fixture (batch-54 review, subagent P2-1, who measured it against
     *   unmodified code). It is in the corpus now, and the arm is one level down where the
     *   property holds.
     *   <li>THE LINE'S ANSWER IS THE CHARACTERS' ANSWER. Whatever the removal does to a line, it
     *   does to that line's code points, one at a time, and then the result is normalised. This
     *   is the exact contract, so a rule that depends on WHERE a character sits, on HOW MANY
     *   there are, or on its NEIGHBOURS shows here — AS FAR AS THE CORPUS REACHES. A cap at the
     *   40th removable character and a rule keyed on a two-character prefix were both measured
     *   green before this arm, and each opens a hole (batch-53 review: subagent P2-2, Codex
     *   P2-1). 「CANNOT HIDE」 STOOD HERE WITHOUT A BOUND while the arm below carried one for the
     *   count, and two one-line rules were measured ALL-GREEN against it: 「keep it when it
     *   occurs exactly nine times」, and 「keep it when its first occurrence is at index five」 —
     *   the second because every line in this class puts a removable character at one of
     *   {0, 1, 2, 3} by its FIRST removable character IF ONLY THE NAMED LINES ARE COUNTED, and
     *   {0, 1, 2, 3, 4, 5, 6, 7} once the swept ones are (U+2057 normalises to four primes and
     *   puts the line's own space at 5; U+00BC reaches 4, U+3307 reaches 6, U+3316 reaches 7).
     *   THE FIGURE THAT EXPLAINED THE ALL-GREEN was neither of those: the sabotage was keyed on
     *   U+200B alone, and the indices at which THAT code point first appeared were
     *   {0, 1, 6, 9, 30, 32} — none of them 5 — before the dense tier started putting one at
     *   every index of the reinstatement line. Three batches recorded a different wrong figure
     *   here: one copied from a review without measuring, one measured on the wrong class of
     *   character, one correct but deleted as stale (batch-59 review, subagent P3-1; batch-60
     *   review, subagent P3-1; batch-61 review, subagent P2-1, who measured all three)
     *   (batch-58 review: Codex P2-1, subagent P2-1, who measured the
     *   count and the position axis respectively). Both axes are now swept DENSELY, for the code
     *   points the removal acts on; what is left of them is written where that sweep is.
     *   </ul>
     *
     * <p>THE CORPUS IS EVERY CODE POINT, with no filter: undefined, surrogate and supplementary
     * included. Two one-line edits were measured to pass every lock in this class while refusing
     * a correct canon — one keyed on an UNDEFINED code point (U+0378, which an 「assigned only」
     * sweep never shows) and one stripping the WHOLE supplementary range, which is where the 240
     * ideographic variation selectors this reader removes live (batch-54 review: Codex P2,
     * subagent P2-2). It is streamed rather than collected: the 3,342,358 lines it visits
     * would be hundreds of megabytes as a list (「1.25M lines is 100MB」 stood here and was
     * neither this corpus's size nor the figure two lines below it — batch-55 review,
     * subagent P3-3).
     *
     * <p>NO CONTROL FIRES THE FIRST ARM, and that is recorded rather than fixed. Every control
     * that names this lock fails on one of the OTHER assertions in this method — which is the
     * list, and it is deliberately not written out here: the enumeration that stood in its place
     * went stale FOUR TIMES, each time in the batch that had just added an arm to it — the
     * batch-57, batch-58, batch-60 and batch-62 reviews, which are not consecutive: the
     * batch-59 and batch-61 reviews did not raise it. Three of the four citations that stood
     * here pointed at other findings of the same rounds, copied from the paragraph above and
     * shifted by one, and they were a claim this batch made without checking the record
     * (batch-63 review, subagent P2-3, who traced all four). Each
     * control's own entry in the runner names what it breaks, and the assertions below carry
     * their own reasons. The first
     * arm's own shape, a normalisation inside the
     * removal, does not
     * change any answer, because {@code NFKC(NFC(x))} equals {@code NFKC(x)} (batch-55 review,
     * subagent P3-2, who measured both; 「all five controls」 stood here and was six by the next
     * batch, one of which fails on the extent rather than the identity — batch-56 review,
     * subagent P3-2). The identity arm is an identity between the pipeline and the per-character
     * answer, so an edit that changes what the reader ANSWERS shows there unless it is keyed on a
     * count or a position, which the two arms after it cover. The first arm is the narrower claim
     * — nothing is transformed or added — and it is kept because it names the defect in one line
     * when it does fire.
     *
     * <p>WHAT A SWEEP STILL CANNOT SHOW: a rule keyed on a context no corpus line contains. The
     * named lines below are the shapes the reviewers' sabotages used — long runs of removables, a
     * repeated prefix, the compatibility forms whose expansion contains a removable character,
     * and the composing shape above.
     */
    @Test
    @DisplayName("the near-copy is the per-character answer, and the removal only deletes")
    void theNearCopyIsThePerCharacterAnswerEverywhere() {
        List<String> named = List.of(
                WITHDRAWN_LINE, REINSTATED_LINE, "", "x", "xx",
                "xx" + REINSTATED_LINE, "xx" + WITHDRAWN_LINE,
                "\u200b".repeat(60) + REINSTATED_LINE,
                " ".repeat(60) + WITHDRAWN_LINE,
                REINSTATED_LINE + "\u200b".repeat(60),
                WITHDRAWN_LINE + WITHDRAWN_LINE,
                "\u00a8" + REINSTATED_LINE, REINSTATED_LINE + "\u00a8",
                "\ufdfa" + REINSTATED_LINE,
                // THE COMPOSING SHAPE, which is what made the old arm ① false: a base, a
                // removable character, and a combining mark that the outer pass joins to the base
                splitEveryComposedCharacterWith("\u200b", REINSTATED_LINE),
                splitEveryComposedCharacterWith("\u200b", WITHDRAWN_LINE),
                splitEveryComposedCharacterWith(" ", REINSTATED_LINE),
                "け\u200b\u3099", "\u200b\u3099",
                // and an astral character, which the old corpus never carried
                "\ud840\udc0b" + REINSTATED_LINE, REINSTATED_LINE + "\ud840\udc0b",
                "\u0378" + REINSTATED_LINE);
        long notADeletion = 0;
        long notThePerCharacterAnswer = 0;
        long lines = 0;
        long codePointsSwept = 0;
        long countDependent = 0;
        long linesWithoutTheirCodePoint = 0;
        long positionsNotCovered = 0;
        long edgesNotSeparated = 0;
        long insertedMoreThanOnce = 0;
        long positionsNotAsked = 0;
        long denseNotWhereItSaid = 0;
        long denseNotTheCountItSaid = 0;
        long denseCodePoints = 0;
        BitSet firstIndicesOfARemovable = new BitSet();
        BitSet firstIndicesOfAKept = new BitSet();
        long linesNotBuiltFromACanonLine = 0;
        int firstNotBuiltFrom = -1;
        long denseLines = 0;
        long denseAnswersDiffer = 0;
        int firstThatIsCountDependent = -1;
        int firstWithoutPositions = -1;
        int firstWithoutEdges = -1;
        int firstInsertedTwice = -1;
        int firstNotWhere = -1;
        int firstNotCount = -1;
        int firstDense = -1;
        String firstThatIsNotADeletion = null;
        String firstThatDiffers = null;
        for (int codePoint = -1; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (codePoint >= 0) {
                codePointsSwept++;
            }
            if (codePoint >= 0) {
                // COUNT-INDEPENDENT, which POSITION cannot show. Three lines each carry the swept
                // code point ONCE, which is the same multiplicity as the single-character probe,
                // so a rule keyed on 「more than once」 is invisible to the arms below; one line
                // carrying it three times hides 「exactly once」 instead. Position and count are
                // different axes, and batch 55 traded one for the other twice (batch-56 review:
                // Codex P2, subagent P2-4, each measuring the opposite direction of the trade).
                // So the count is asserted directly, on short strings: what the removal does to a
                // character repeated k times is what it does to that character, k times.
                String one = new String(Character.toChars(codePoint));
                String once = withoutWhatCarriesNoMeaningHere(one);
                // k UP TO 8, AND 64. The bound is the claim: 「keep it when it occurs more than
                // FOUR times」 and 「exactly five」 were ALL-GREEN while this said 2..4, and the
                // javadoc claimed the count was covered without qualification (batch-57 review:
                // Codex P2-1, subagent P2-1, each measured). A test can only cover a bound; what
                // is left for the code points the removal KEEPS is a rule keyed on a count
                // in 9..63 or above 64; for the ones it empties, the dense tier below covers
                // 1..64 and above 64 is still open. The most removable characters any line in
                // this class carries is 66 — a dense line of sixty-four plus the two spaces of
                // the reinstatement line — so a cap ABOVE sixty-six touches nothing here, on
                // either side (batch-59 review, subagent P2-2, who measured the maximum;
                // re-measured here: 62 among the named lines, 66 among the dense ones).
                for (int k : new int[] { 2, 3, 4, 5, 6, 7, 8, 64 }) {
                    if (!withoutWhatCarriesNoMeaningHere(one.repeat(k)).equals(once.repeat(k))) {
                        countDependent++;
                        if (firstThatIsCountDependent < 0) {
                            firstThatIsCountDependent = codePoint;
                        }
                        break;
                    }
                }
            }
            if (codePoint >= 0) {
                // AND THE THREE POSITIONS ARE THE THREE POSITIONS. Returning the head line three
                // times keeps every count right and every line carrying its code point, with each
                // arm green — so a rule keyed on WHERE the character sits would be invisible
                // (batch-56 review, subagent P2-1, measured as ALL-GREEN).
                List<String> three = linesFor(codePoint, named);
                String one = new String(Character.toChars(codePoint));
                // NOT a second copy of the construction — that would pass when both copies are
                // edited. Three DISTINCT lines, each carrying the code point, one of them opening
                // with it and one closing with it: the head line cannot be the tail line, and the
                // remaining one has it somewhere else.
                boolean threeDistinct = three.size() == 3
                        && new TreeSet<>(three).size() == 3;
                boolean everyOneCarriesIt = three.stream().allMatch(line -> line.contains(one));
                boolean oneOpensWithIt = three.stream().anyMatch(line -> line.startsWith(one));
                boolean oneClosesWithIt = three.stream().anyMatch(line -> line.endsWith(one));
                // AND ONE CARRIES IT STRICTLY INSIDE. Asking only for a head and a tail let
                // head/tail/tail pass with the inside position gone, while the failure message
                // named three positions (batch-57 review: Codex P2-2, subagent P3-1, measured).
                // 「Strictly inside」 is an occurrence that neither starts the line nor ends it —
                // which is why it is asked as an occurrence and not as a position of the FIRST
                // one: the declaration's own text contains 「-」 and 「）」 at its edges.
                boolean oneCarriesItInside = three.stream().anyMatch(line -> {
                    for (int at = line.indexOf(one); at >= 0; at = line.indexOf(one, at + 1)) {
                        if (at > 0 && at + one.length() < line.length()) {
                            return true;
                        }
                    }
                    return false;
                });
                if (!threeDistinct || !everyOneCarriesIt || !oneOpensWithIt || !oneClosesWithIt
                        || !oneCarriesItInside) {
                    positionsNotCovered++;
                    if (firstWithoutPositions < 0) {
                        firstWithoutPositions = codePoint;
                    }
                }
                // AND THEY ARE THREE DIFFERENT LINES. 「Some line carries it inside」 is satisfied
                // by a line that OPENS with it and carries a second occurrence inside, so one
                // edit to linesFor — making the middle line open with the code point as well —
                // left every arm green with the only purely-inside sample gone, and nothing
                // asserted the 「ONE OCCURRENCE EACH」 that linesFor's own javadoc claims
                // (batch-58 review, Codex P2-3, measured ALL-GREEN; measured again here).
                // ASKED ONLY WHERE THE TWO LINES DO NOT ALREADY CARRY THE CODE POINT: for the 27
                // that they do, an occurrence the line brought itself cannot be told from the one
                // inserted, and the weaker arm above is all that holds for those.
                // AND EXACTLY ONE OCCURRENCE WAS INSERTED INTO EACH — asked of EVERY code
                // point, with no exclusion. The separation below cannot be asked of the 27 the
                // two lines carry themselves, and 「ONE OCCURRENCE EACH」 was claimed in the
                // javadoc and in two failure messages while nothing asserted it: an OY5-shaped
                // edit applied only to those 27
                // was measured ALL-GREEN (batch-59 review, Codex P2-2). The count CAN be asked
                // universally, because it is relative to the line each one is built from — one
                // more than the reinstatement line, or one more than the withdrawal line.
                final int sweptHere = codePoint;
                long inReinstated = REINSTATED_LINE.codePoints()
                        .filter(each -> each == sweptHere).count();
                long inWithdrawn = WITHDRAWN_LINE.codePoints()
                        .filter(each -> each == sweptHere).count();
                // AS A MULTISET, NOT IN ORDER. 「Either base will do」 was loose for
                // exactly two code points — 撤 and 回, the only ones the withdrawal line does
                // not carry as often as the reinstatement line — and that was enough for a tail
                // line built from the wrong base to pass (batch-60 review: Codex P2-2, who
                // wrote the edit; subagent P3-3, who measured that those two are the only loose
                // ones). Pinning line 0 and 1 to one base and line 2 to the other fixed that and
                // bought an over-refusal: REORDERING the list, which changes no line, reddened
                // those same two code points and the message named the wrong cause (batch-61
                // review, subagent P3-5). The multiset is what the claim actually is.
                // 「And the total as well」 stood here and was DEAD — it is the sum of the three
                // expectations, so it cannot fire while each line matches one of them, and the
                // redistribution its message promised to catch does not exist. The same batch
                // removed another condition for being dead and added this one (batch-61 review,
                // subagent P2-2).
                List<Long> wantedCounts = new ArrayList<>(
                        List.of(inReinstated + 1, inReinstated + 1, inWithdrawn + 1));
                List<Long> carriedCounts = new ArrayList<>(three.stream()
                        .map(line -> countIn(line, sweptHere)).toList());
                Collections.sort(wantedCounts);
                Collections.sort(carriedCounts);
                if (!wantedCounts.equals(carriedCounts)) {
                    insertedMoreThanOnce++;
                    if (firstInsertedTwice < 0) {
                        firstInsertedTwice = codePoint;
                    }
                }
                // AND EACH LINE IS A CANON LINE WITH ONE INSERTION. The counts above say how
                // many times the swept code point appears; they say nothing about the rest of
                // the line. 「one + the reversed reinstatement line」 keeps every count, every
                // position and every answer right while the corpus stops being 「the canon line
                // with one character inserted」, which is what the neighbour-dependent rules are
                // measured against (batch-61 review, Codex P2-2). Two of the three must be the
                // reinstatement line with one insertion and one must be the withdrawal line
                // with one — counted, so the order of the list does not matter. The length
                // tells the two bases apart: the withdrawal line is two characters shorter, and
                // it is itself a subsequence of the reinstatement line.
                // AND WHICH LINE CARRIES IT WHERE. Counting two from one base and one from
                // the other leaves the PAIRING free: moving the head to the withdrawal line and
                // the tail to the reinstatement line keeps both totals, every count, every
                // position and both index sets, and the input 「the withdrawal line with the
                // code point at its tail」 disappears (batch-62 review, Codex P2-1, measured).
                // Each line is classified by its base and by where the insertion sits — which
                // end of the base, if either, the line is that base plus one — and the MULTISET
                // of those three classes is what is asked, so the order of the list still does
                // not matter.
                List<String> wantedShapes = new ArrayList<>(
                        List.of("reinstated:head", "reinstated:inside", "withdrawn:tail"));
                List<String> carriedShapes = new ArrayList<>(
                        three.stream().map(line -> shapeOf(line, one)).toList());
                Collections.sort(wantedShapes);
                Collections.sort(carriedShapes);
                if (!wantedShapes.equals(carriedShapes)) {
                    linesNotBuiltFromACanonLine++;
                    if (firstNotBuiltFrom < 0) {
                        firstNotBuiltFrom = codePoint;
                    }
                }
                if (!REINSTATED_LINE.contains(one) && !WITHDRAWN_LINE.contains(one)) {
                    long opening = three.stream().filter(line -> line.startsWith(one)).count();
                    long closing = three.stream().filter(line -> line.endsWith(one)).count();
                    long neither = three.stream()
                            .filter(line -> !line.startsWith(one) && !line.endsWith(one)).count();
                    // 「each carries it once」 stood here and is now DEAD: on this gated set
                    // both canon lines carry the code point zero times, so the arm above —
                    // which asks every code point for the inserted count — subsumes it exactly.
                    // A condition that can never fire on its own produces a failure message
                    // that names the wrong cause (batch-60 review, subagent P3-4).
                    if (opening != 1 || closing != 1 || neither != 1) {
                        edgesNotSeparated++;
                        if (firstWithoutEdges < 0) {
                            firstWithoutEdges = codePoint;
                        }
                    }
                } else {
                    // COUNTED BY THE GATE ITSELF. A second copy of the condition stood here, so
                    // narrowing the gate — 「and the code point is not ASCII」 — shrank what the
                    // separation covers from 1,114,085 to a fraction while this count stayed at
                    // 27 and every arm was green (batch-60 review, subagent P2-3, measured).
                    positionsNotAsked++;
                }
            }
            if (codePoint >= 0) {
                // DENSE WHERE THE REMOVAL ACTS. The two axes this lock bounds — HOW MANY and
                // WHERE — are swept exhaustively for every code point the removal EMPTIES: that
                // character at every index of the reinstatement line, and repeated once through
                // sixty-four times. Each was ONE line and each was ALL-GREEN before this
                // (batch-58 review: Codex P2-1, subagent P2-1).
                // WHAT IS LEFT, ON BOTH AXES AND BOTH SIDES:
                //   - COUNTS ABOVE 64, for every code point. Nothing in this class carries one
                //     more than 66 times. A rule keyed on 65 was measured ALL-GREEN for the code
                //     points the removal keeps; for the ones it empties it reddened only 16 of
                //     510, and only because the reinstatement line's own two spaces push those
                //     totals past 64 — which is an accident, not coverage.
                //   - COUNTS 9..63 for the code points the removal KEEPS (the sparse arm samples
                //     {2..8, 64}). 「Drop a kept character at exactly nine」 was measured
                //     ALL-GREEN (batch-59 review, Codex P2-1, who found 「above 64」 missing from
                //     this list).
                //   - POSITIONS PAST WHERE ANY LINE REACHES. A rule keyed on 「the first
                //     occurrence sits at index N」 needs a line that puts one there. The SETS of
                //     such N are asserted below — they were the two farthest N for a batch, and
                //     a maximum is not a set; what the corpus covers is 0..65
                //     and the ODD 67..129 for a removable code point (the even 66..128 are
                //     open, because a supplementary selector repeated k times shifts the line's
                //     own first space by 2k), and 0..159 except 158 for a kept one. Everything
                //     past 129 and 159 respectively is open, and the real canon has lines far
                //     longer than either.
                //     「EVERY INDEX BUT 0, 9 AND THE END, for the code points the removal
                //     KEEPS」 stood here and was wrong by 155 indices: the COUNT tier carries
                //     the line's own kept characters out to index 159 even though it is gated
                //     on removable ones. And the evidence cited for it — 「keep a kept
                //     character when its first occurrence is at index five」 — changes no
                //     verdict about either canon line, because a line carrying that character
                //     is not a near-copy of either one with or without the rule; index five is
                //     in fact CLOSED, since NFKC of the reinstatement line has げ there and
                //     DROPPING a kept character at its first index reddens this arm (batch-60
                //     review: Codex P3, subagent P2-2, who measured both halves; re-measured
                //     here).
                // WHAT CLOSING THE KEPT SIDE OF THE COUNT AXIS COSTS: 108,019,394 lines,
                // measured at 260,000 lines a second in a copy of this reader — about 415
                // SECONDS on every run of this class, against the 0.29 seconds this tier
                // costs. 「9.3s to 60.4s」 stood here and was a DIFFERENT experiment (counts
                // only, no index sweep), so it understated the price by eight times (batch-59
                // review, subagent P3-5, who could not reproduce it; re-measured here).
                String one = new String(Character.toChars(codePoint));
                if (withoutWhatCarriesNoMeaningHere(one).isEmpty()) {
                    denseCodePoints++;
                    final int sweptDense = codePoint;
                    long inTheLine = REINSTATED_LINE.codePoints()
                            .filter(each -> each == sweptDense).count();
                    // AND WHAT IT SWEPT IS ASSERTED, NOT ONLY HOW MUCH. The three totals below
                    // stay right when either construction is collapsed to 「one + the line」: the
                    // loops still turn, so 510, 49,470 and 0 are unchanged and every arm is
                    // green — and then BOTH holes this tier closed reopen (measured: OV5 and OU5
                    // each went ALL-GREEN again). It is the same defect the swept corpus fixed
                    // with its own content arm, and the failure message there says so
                    // (batch-59 review, subagent P2-1, who found both collapses).
                    for (int at = 0; at <= REINSTATED_LINE.length(); at++) {
                        denseLines++;
                        String atThatIndex = REINSTATED_LINE.substring(0, at) + one
                                + REINSTATED_LINE.substring(at);
                        // FROM THE LINE THE SWEEP USES, not from a second copy of how it is
                        // built. Written out again here, what stood below at the time — two
                        // MAXIMA, which batch 62 replaced with the two sets — measured the
                        // construction in THIS statement rather than the one above it, so
                        // reordering the repetitions left them at 129 and 159 and the control
                        // for them DID NOT FIRE (measured in batch 61).
                        addFirstIndices(atThatIndex, firstIndicesOfARemovable,
                                firstIndicesOfAKept);
                        // AND THE WHOLE LINE, not the prefix and the count. Nothing here
                        // looked at what came AFTER the insertion, so reversing that part left
                        // every arm green — including both index sets, which the reversal does
                        // not move — while the lines that put a removable character before an
                        // 「f」 fell from 510 to none, and a rule keyed on that neighbour had
                        // nothing left to catch it. It is the same defect PJ5 closed one tier
                        // up (batch-62 review, subagent P2-1, who wrote the edit and found the
                        // discriminating rule). 15,810 of the 16,830 index lines redden: at the
                        // last two positions the suffix is one character or empty, so reversing
                        // it changes nothing.
                        if (!atThatIndex.startsWith(REINSTATED_LINE.substring(0, at))
                                || !atThatIndex.startsWith(one, at)
                                || !isTheLineWithOneInsertion(REINSTATED_LINE, atThatIndex, one)
                                || atThatIndex.codePoints().filter(each -> each == sweptDense)
                                        .count() != inTheLine + 1) {
                            denseNotWhereItSaid++;
                            if (firstNotWhere < 0) {
                                firstNotWhere = codePoint;
                            }
                        }
                        if (!isThePerCharacterAnswer(atThatIndex,
                                Normalizer.normalize(atThatIndex, Normalizer.Form.NFKC))) {
                            denseAnswersDiffer++;
                            if (firstDense < 0) {
                                firstDense = codePoint;
                            }
                        }
                    }
                    for (int k = 1; k <= 64; k++) {
                        denseLines++;
                        String thatManyTimes = one.repeat(k) + REINSTATED_LINE;
                        addFirstIndices(thatManyTimes, firstIndicesOfARemovable,
                                firstIndicesOfAKept);
                        // AND THE CANON LINE BEHIND THEM. Only the count of the swept code
                        // point was asked here, so rewriting the canon line itself left every
                        // arm green — including both index sets, because those record WHERE a
                        // first occurrence sits and not WHICH code point sits there, and the six
                        // characters of 「取り下げ撤回」 are distinct, so reordering them keeps
                        // {2,3,4,5,6,7} (batch-63 review: Codex P2, subagent P2-1, who each
                        // wrote an edit; both measured here).
                        // WHERE THE COPIES SIT is held by the index SETS, not by the index tier:
                        // 「canon[0..j] + k copies + canon[j..]」 passes every arm for j = 0 and
                        // j = 1 and reddens the removable set from j = 2, because the canon
                        // line's own first space stops being pushed along. 「Position is the
                        // other tier's claim」 stood here and was wrong about which arm holds it
                        // (batch-64 review, subagent P3-2, measured; re-measured here).
                        if (!isTheLineWithThatManyInsertions(REINSTATED_LINE, thatManyTimes, one, k)
                                || thatManyTimes.codePoints().filter(each -> each == sweptDense)
                                        .count() != k + inTheLine) {
                            denseNotTheCountItSaid++;
                            if (firstNotCount < 0) {
                                firstNotCount = codePoint;
                            }
                        }
                        if (!isThePerCharacterAnswer(thatManyTimes,
                                Normalizer.normalize(thatManyTimes, Normalizer.Form.NFKC))) {
                            denseAnswersDiffer++;
                            if (firstDense < 0) {
                                firstDense = codePoint;
                            }
                        }
                    }
                }
            }
            for (String line : linesFor(codePoint, named)) {
                lines++;
                // EVERY SOURCE, not the dense lines alone. The reach was accumulated only
                // inside the dense tier, so lengthening one named line to six hundred
                // zero-width spaces took the corpus to 601 and 631 while both figures stayed
                // at 129 and 159 and every arm was green — and the bound beside the sweep then
                // claims open what is covered, which buys the next batch work it does not need
                // (batch-61 review, subagent P2-3, who wrote that edit). This costs 2.7s.
                addFirstIndices(line, firstIndicesOfARemovable, firstIndicesOfAKept);
                if (codePoint >= 0 && !line.contains(new String(Character.toChars(codePoint)))) {
                    linesWithoutTheirCodePoint++;
                }
                String normalised = Normalizer.normalize(line, Normalizer.Form.NFKC);
                String removed = withoutWhatCarriesNoMeaningHere(normalised);
                if (!isADeletionOf(removed, normalised)) {
                    notADeletion++;
                    if (firstThatIsNotADeletion == null) {
                        firstThatIsNotADeletion = line;
                    }
                }
                // THROUGH THE SAME HELPER the dense tier uses. A second copy of this
                // calculation sat here, so an edit that weakened one left the other green — and
                // the helper's own javadoc claimed both asked it the same way (batch-59 review,
                // subagent P3-2).
                if (!isThePerCharacterAnswer(line, normalised)) {
                    notThePerCharacterAnswer++;
                    if (firstThatDiffers == null) {
                        firstThatDiffers = line;
                    }
                }
            }
        }
        // THE EXTENT IS PART OF THE CLAIM. It was described as 「every code point」 and counted
        // into a variable only the failure messages printed, so a filter put back on the loop —
        // 「assigned only」, or the BMP — narrowed the sweep with every arm still green (batch-55
        // review, user P2-1). Both numbers are asserted: the code points visited, and the lines
        // they produced with the named ones.
        // AND THE NAMED CORPUS IS WHAT THE SWEEP CANNOT BUILD — ASSERTED THROUGH THE SEAM, BY
        // WHAT IT SEPARATES. Its size was asserted against its own size, so emptying it was
        // ALL-GREEN (batch-57 review: Codex P2-3, subagent P2-2); batch 58 replaced that with
        // five SHAPES, and shapes on the list measure neither that a line reaches the reader nor
        // that it separates anything. Two one-line edits were ALL-GREEN again: returning 「x」 for
        // every named line at linesFor's seam, and dropping the two lines that carry the repeated
        // prefix AND something removable after it, which leaves the bare 「xx」 — where nothing is
        // removable — satisfying 「a line starts with xx」 (batch-58 review: subagent P2-2, Codex
        // P2-2, each measured; measured again here).
        // SO WHAT IS ASSERTED IS SEPARATION: for each near-copy a reader could compute instead,
        // some line that comes back THROUGH the seam has to answer differently. A named line no
        // alternative needs can be dropped without this reddening — the list below is what the
        // corpus is for, and the list being finite is the bound.
        List<String> received = linesFor(-1, named);
        for (Map.Entry<String, UnaryOperator<String>> alternative : alternativeNearCopies()) {
            assertTrue(received.stream().anyMatch(
                            line -> !alternative.getValue().apply(line).equals(asNearCopy(line))),
                    "no line the reader receives tells the real near-copy apart from "
                            + alternative.getKey() + ", so that alternative — which either "
                            + "refuses a correct canon or reads two different lines as one — "
                            + "would pass. The sweep cannot replace these: all it can build is "
                            + "one code point inserted into one of two lines");
        }
        assertEquals(0, countDependent,
                "the removal depends on HOW MANY times a character appears: " + countDependent
                        + " code points answer differently when repeated, the first being U+"
                        + (firstThatIsCountDependent < 0 ? "?"
                                : String.format("%04X", firstThatIsCountDependent))
                        + ". A rule keyed on a count passes the sweep below, because every line "
                        + "there carries the swept code point exactly once — the same multiplicity "
                        + "as the single-character probe the other arm compares against. Measured "
                        + "both ways: 「keep it when it occurs exactly once」 and 「keep it when it "
                        + "occurs more than once」 each left every other lock green under one of "
                        + "the two corpora");
        assertEquals(0, linesWithoutTheirCodePoint,
                linesWithoutTheirCodePoint + " of the swept lines do not CONTAIN the code point "
                        + "they were built for, so the sweep visits numbers without feeding them "
                        + "to the reader. Counting the loop is not enough: a linesFor that returns "
                        + "empty strings, or the same line three times, keeps both totals right "
                        + "while never exercising what it claims to cover");
        assertEquals(0, denseAnswersDiffer,
                denseAnswersDiffer + " of " + denseLines + " densely swept lines answer "
                        + "differently from their own characters — the first is for U+"
                        + (firstDense < 0 ? "?" : String.format("%04X", firstDense)) + ". Every "
                        + "code point the removal empties is put at EVERY index of the "
                        + "reinstatement line and repeated 1..64 times, which is how a rule keyed "
                        + "on a count or on a position shows: two such rules were ALL-GREEN "
                        + "against the sparse arms");
        assertEquals(510, denseCodePoints,
                "the dense sweep covered " + denseCodePoints + " code points, not the 510 the "
                        + "removal acts on (JDK 21.0.10: Cc 65 + Cf 170 + Zs 17 + Zl 1 + Zp 1 + "
                        + "256 variation selectors). Narrowing it leaves the count and the "
                        + "position axes sparse again, which is where the two ALL-GREEN rules "
                        + "lived. THIS IS THE ONLY HAND-WRITTEN COPY of the removal set's size "
                        + "in this class: the per-category arm takes both sides of its comparison "
                        + "from the same JDK, so a Unicode version that moves these counts cannot "
                        + "redden it — by design, and its javadoc says so. On a JDK where the "
                        + "supplementary FORMAT count is 120 rather than 127 this arm reddens "
                        + "ALONE, at 503, and the figure here is what gets re-measured");
        assertEquals(denseCodePoints * (REINSTATED_LINE.length() + 1L + 64L), denseLines,
                "the dense sweep built " + denseLines + " lines, not every index of the "
                        + "reinstatement line plus every count to sixty-four, for each of the "
                        + denseCodePoints + " code points it covers. Shortening either loop "
                        + "narrows an axis without moving the total above it");
        assertEquals(0, insertedMoreThanOnce,
                insertedMoreThanOnce + " code points are carried more than ONCE by one of their "
                        + "three lines — the first is U+" + (firstInsertedTwice < 0 ? "?"
                                : String.format("%04X", firstInsertedTwice)) + ". Each line is "
                        + "one of the two canon lines with the code point inserted once, so its "
                        + "count must be one more than that line's own. Asked of every code "
                        + "point: the separation below cannot be asked of the ones the two lines "
                        + "carry themselves, and an edit scoped to exactly those was measured "
                        + "passing every arm");
        assertEquals(27, positionsNotAsked,
                positionsNotAsked + " code points were skipped by the position separation, not "
                        + "the 27 the two canon lines carry between them. The skip is derived "
                        + "from those lines, so editing either moves it — and then the arm below "
                        + "quietly covers more or less than this says. A hand-written figure "
                        + "with no lock on it is the shape this family has had to correct "
                        + "in this file again and again");
        assertEquals(0, denseNotWhereItSaid,
                denseNotWhereItSaid + " of the dense INDEX lines do not carry the code point "
                        + "where the sweep says, or carry it more than once — the first is for U+"
                        + (firstNotWhere < 0 ? "?" : String.format("%04X", firstNotWhere))
                        + ". Collapsing the construction to 「the code point then the line」 "
                        + "keeps the count of lines, the count of code points and the answers "
                        + "all correct, and reopens the whole position axis. Index zero is the "
                        + "one position this cannot tell from that collapse, so 32 of the 33 "
                        + "fire");
        assertEquals(0, denseNotTheCountItSaid,
                denseNotTheCountItSaid + " of the dense COUNT lines are not the canon line with "
                        + "that many copies of the code point inserted — the count is wrong, or "
                        + "the line is not that length, or the canon line is no longer a "
                        + "subsequence of it. The first is for U+"
                        + (firstNotCount < 0 ? "?" : String.format("%04X", firstNotCount))
                        + ". Collapsing the repetition keeps all three totals right and reopens "
                        + "the count axis. A count of one is the one case this cannot tell from "
                        + "that collapse, so 63 of the 64 fire");
        // THE SET, NOT THE MAXIMUM. Both of these were asserted as their largest member,
        // and prefixing the repetitions only at k == 64 keeps both maxima while the covered set
        // collapses to {0..32, 61, 65, 129}: the end of the reach is not the reach (batch-61
        // review, Codex P2-1, measured). 「{0..32, 65, 129}」 stood here for a batch and was the
        // set BEFORE the accumulation was widened to the named lines in the same batch — 61 is
        // the own first space of the named line that carries SIXTY ZERO-WIDTH SPACES IN
        // FRONT of it — not the one that carries sixty ordinary spaces (its own first space is
        // at index 0) and not the one that carries sixty zero-width spaces BEHIND it (that one
        // reaches {1, 32}) (batch-62 review, subagent P2-2; batch-64 review, subagent P3-3, who
        // measured it; the fourth figure in this paragraph's family to be recorded from the
        // wrong state). The sets below ARE the position bound written beside the sweep, so that
        // sentence cannot go stale while this is green.
        // WHAT THEY DO AND DO NOT CATCH, measured per source: only the dense COUNT tier has a
        // contribution of its own. The named lines, the three swept ones and the dense INDEX
        // tier each add nothing the others do not, so an edit that SHRINKS or rewrites any of
        // those three does not move these sets — an edit that makes a line reach FARTHER does.
        // The arms above are what hold the other direction.
        BitSet removableIndicesTheSweepReaches = new BitSet();
        removableIndicesTheSweepReaches.set(0, 66);
        for (int at = 67; at <= 129; at += 2) {
            removableIndicesTheSweepReaches.set(at);
        }
        assertEquals(removableIndicesTheSweepReaches, firstIndicesOfARemovable,
                "the indices at which a REMOVABLE code point FIRST appears somewhere in this "
                        + "corpus are not 0 to 65 and the odd 67 to 129. The odd tail is a "
                        + "supplementary selector repeated k times shifting the line's own first "
                        + "space by 2k; the even 66 to 128 and everything past 129 are the open "
                        + "part of the position axis, and that is what the bound beside the "
                        + "sweep says");
        BitSet keptIndicesTheSweepReaches = new BitSet();
        keptIndicesTheSweepReaches.set(0, 158);
        keptIndicesTheSweepReaches.set(159);
        assertEquals(keptIndicesTheSweepReaches, firstIndicesOfAKept,
                "the indices at which a KEPT code point FIRST appears somewhere in this corpus "
                        + "are not 0 to 157 and 159. 159 is 128 + 31, the last index of the "
                        + "reinstatement line behind sixty-four supplementary selectors; 158 is "
                        + "the one gap, because the character at index 30 of that line first "
                        + "appears at index 28");
        assertEquals(0, linesNotBuiltFromACanonLine,
                linesNotBuiltFromACanonLine + " code points have a line that is not one of the "
                        + "two canon lines with a single insertion — the first is U+"
                        + (firstNotBuiltFrom < 0 ? "?"
                                : String.format("%04X", firstNotBuiltFrom)) + ". Two must be the "
                        + "reinstatement line with one character inserted and one must be the "
                        + "withdrawal line with one; counted rather than taken in order, so "
                        + "reordering the list changes nothing. Every count, position and answer "
                        + "arm passes a line that is the reinstatement line REVERSED");
        assertEquals(0, edgesNotSeparated,
                edgesNotSeparated + " code points are not swept at three SEPARATE positions — the "
                        + "first is U+" + (firstWithoutEdges < 0 ? "?"
                                : String.format("%04X", firstWithoutEdges)) + ". Exactly one of "
                        + "the three lines may open with it, exactly one may close with it, "
                        + "exactly one may do neither: a middle line that also opens with the "
                        + "code point satisfies 「some line carries it inside」 while the "
                        + "purely-inside sample is gone. The 27 code points the two lines carry "
                        + "themselves are not asked this — how many were skipped is asserted "
                        + "above, and the count of the INSERTED occurrence is asked of all of "
                        + "them");
        assertEquals(0, positionsNotCovered,
                positionsNotCovered + " code points are not swept at all three positions — the "
                        + "first is U+" + (firstWithoutPositions < 0 ? "?"
                                : String.format("%04X", firstWithoutPositions)) + ". The head, "
                        + "the inside and the tail are three different questions, and collapsing "
                        + "them to one keeps every count right while a rule keyed on WHERE a "
                        + "character sits goes unseen");
        // An assertion, not a breakage (closing batch 7, R191): what this lock asserts is a property of what it visits,
        // so the extent it claims IS part of the claim — ON5 narrows the loop and must see this fail.
        assertEquals(Character.MAX_CODE_POINT + 1, codePointsSwept,
                "the sweep visited " + codePointsSwept + " code points, not all "
                        + (Character.MAX_CODE_POINT + 1) + ". A filter on the loop — undefined, "
                        + "surrogate, supplementary, assigned-only — narrows what this lock "
                        + "covers without moving either arm, because what it measures is a "
                        + "property of what it visits");
        assertEquals(named.size() + 3L * (Character.MAX_CODE_POINT + 1), lines,
                "the corpus is " + lines + " lines, not the " + named.size() + " named ones plus "
                        + "three per code point. Three lines with ONE INSERTED occurrence "
                        + "each is the point — the raw count is one more than the line each is "
                        + "built from, which for the 27 code points those lines carry is two, "
                        + "three or four depending on the code point (「two」 stood here and is "
                        + "right for 21 of them: a hyphen reaches four, a space, a 2 and an l "
                        + "reach three, and the tail line for 撤 or 回 carries one): one line "
                        + "carrying the same code point three times cannot catch a rule keyed on "
                        + "「exactly once」, which was measured passing every arm");
        assertEquals(0, notADeletion,
                "the REMOVAL does not only delete, for " + notADeletion + " of " + lines
                        + " lines — the first is 「" + describe(firstThatIsNotADeletion) + "」. It "
                        + "is meant to return a subsequence of what it is given: an upper-casing, "
                        + "a replacement or a normalisation inside it makes lines equal that a "
                        + "reader sees as different. (The PIPELINE is not a deletion and must not "
                        + "be asserted to be one — the outer normalisation composes.)");
        assertEquals(0, notThePerCharacterAnswer,
                "the near-copy of a LINE is not the per-character answer applied everywhere, for "
                        + notThePerCharacterAnswer + " of " + lines + " lines — the first is 「"
                        + describe(firstThatDiffers) + "」. Something in asNearCopy or in the "
                        + "removal depends on WHERE a character sits, on HOW MANY there are, or "
                        + "on its neighbours, or transforms the line instead of deleting from it. "
                        + "Measured shapes that passed everything before this arm: a cap at the "
                        + "40th removable character, a rule keyed on a two-character prefix, an "
                        + "upper-casing, and stripping the whole supplementary range");
    }

    /**
     * The corpus lines for one code point, or the named lines when given -1.
     *
     * <p>THREE LINES, ONE INSERTED OCCURRENCE EACH: the code point at the head, inside, and at
     * the tail. 「ONE OCCURRENCE EACH」 stood here unqualified and is FALSE for the 27 code
     * points the two lines carry themselves — 「（R212）」 alone means the line for R holds two —
     * and nothing asserted either reading, so an edit that doubled the middle line for exactly
     * those 27 passed every arm (batch-59 review, Codex P2-2, measured). What is asserted is the
     * INSERTED one: each line carries the code point once more than the line it is built from.
     * Batch 55 packed the three positions into ONE line to save time, and that line carries the
     * code point THREE times — so 「remove X when it occurs exactly once」 stopped being caught.
     * The subagent sabotaged 41 shapes across both corpora: four differ, and for two of them
     * (U+180E, U+E0101) nothing else in this class fires, which means a canon stating the
     * declaration TWICE reads as stating it once (batch-55 review, subagent P2-2, measured).
     *
     * <p>AND THE TIME IT COSTS WAS OVERSTATED BY THIRTEEN TIMES. The runner starts the named test
     * class twice per control — sabotaged, and again after the restore — and only for the class
     * that control names, so the multiplier is TWICE THE NUMBER OF CONTROLS THAT NAME THIS CLASS
     * and no figure for it is written here: three batches running, a number written in this
     * paragraph went stale in the next batch, including the one that corrected it (batch-55
     * review: all three reviews, independently; batch-56 review: Codex P3 and subagent P3-1;
     * batch-58 review: Codex P3 and subagent P2-3, who measured 94 controls against the 91 this
     * paragraph still claimed). 「Five and a half hours」 came from multiplying by all 2,338
     * controls — the total at the time, which is what a record of a past mistake has to say —
     * which contradicts what this tree already records: a control runs one test class only.
     */
    private static List<String> linesFor(int codePoint, List<String> named) {
        if (codePoint < 0) {
            return named;
        }
        String one = new String(Character.toChars(codePoint));
        return List.of(one + REINSTATED_LINE,
                REINSTATED_LINE.substring(0, 9) + one + REINSTATED_LINE.substring(9),
                WITHDRAWN_LINE + one);
    }

    /** Whether {@code shorter} is {@code longer} with zero or more code points deleted. */
    private static boolean isADeletionOf(String shorter, String longer) {
        int at = 0;
        for (int i = 0; i < shorter.length(); ) {
            int codePoint = shorter.codePointAt(i);
            int found = longer.indexOf(new String(Character.toChars(codePoint)), at);
            if (found < 0) {
                return false;
            }
            at = found + Character.charCount(codePoint);
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** A line with its invisible characters named, for a failure message. */
    private static String describe(String line) {
        if (line == null) {
            return "(none)";
        }
        StringBuilder described = new StringBuilder();
        line.codePoints().forEach(codePoint -> {
            if (codePoint > 0x20 && !carriesNoMeaningHere(codePoint)) {
                described.appendCodePoint(codePoint);
            } else {
                described.append(String.format("<U+%04X>", codePoint));
            }
        });
        return described.length() > 160 ? described.substring(0, 160) + "…" : described.toString();
    }

    /**
     * Whether the near-copy of this line is the per-character answer applied everywhere.
     *
     * <p>THE REFERENCE IS THE CALLER'S. {@code normalised} is the compatibility-normalised line
     * the caller computed, and the comparison is against it — not against a copy this method
     * takes for itself. Batch 60 moved the calculation in here and let it normalise for itself,
     * which looked like removing a duplicate and was a loss: the only remaining reader of the
     * sweep's own {@code normalised} was a subsequence check that is true whatever it holds, so
     * 「{@code String normalised = line}」 went from firing 159 times to firing nothing
     * (batch-60 review, subagent P2-1, measured; re-measured here). A weakened reference makes
     * this arm compare the reader against itself.
     *
     * <p>AND THAT IS TRUE OF ONE CALL SITE, NOT THREE. Weakening the reference the DENSE tiers
     * pass changes no answer at all — every code point the removal empties stays removable
     * through the compatibility normalisation, so the raw line and the normalised one give the
     * same per-character answer for all 49,470 of those lines (measured 0 against 0). The
     * control for this measures the sweep (batch-61 review, subagent P3-3).
     */
    private static boolean isThePerCharacterAnswer(String line, String normalised) {
        StringBuilder perCharacter = new StringBuilder();
        normalised.codePoints().forEach(each -> {
            if (!withoutWhatCarriesNoMeaningHere(new String(Character.toChars(each))).isEmpty()) {
                perCharacter.appendCodePoint(each);
            }
        });
        return Normalizer.normalize(perCharacter.toString(), Normalizer.Form.NFKC)
                .equals(asNearCopy(line));
    }

    /** The removal, stopped after {@code cap} removable code points. */
    private static String removalCappedAt(String line, int cap) {
        StringBuilder kept = new StringBuilder(line.length());
        int[] removed = { 0 };
        line.codePoints().forEach(codePoint -> {
            if (carriesNoMeaningHere(codePoint) && removed[0] < cap) {
                removed[0]++;
            } else {
                kept.appendCodePoint(codePoint);
            }
        });
        return kept.toString();
    }

    /** How many times {@code codePoint} appears in {@code line}. */
    private static long countIn(String line, int codePoint) {
        return line.codePoints().filter(each -> each == codePoint).count();
    }

    /**
     * Records, for this line, every index at which some code point FIRST appears.
     *
     * <p>WHAT THE POSITION BOUND IS MADE OF. A rule keyed on 「the first occurrence sits at index
     * N」 can only be caught if some line puts a first occurrence there, so the SET of such N is
     * this lock's reach on that axis. It was the largest N for a batch, and a maximum is not a
     * set: prefixing the dense repetitions only at the largest count kept both maxima while the
     * set collapsed to four runs (batch-61 review, Codex P2-1, measured; 「three fragments」
     * stood here, counted on the set before the same batch widened the accumulation — batch-63
     * review, subagent P2-2). It is asserted
     * rather than written down, because the sets move with the corpus and a hand-written figure
     * goes stale silently — which is what this family keeps having to correct.
     *
     * <p>CALLED FROM EVERY SOURCE: the named lines, the swept ones, and both dense tiers. While
     * only the dense tier fed it, lengthening a named line left the two figures untouched and
     * the bound claiming open what was covered (batch-61 review, subagent P2-3).
     */
    private static void addFirstIndices(String line, BitSet removable, BitSet kept) {
        String normalised = Normalizer.normalize(line, Normalizer.Form.NFKC);
        Set<Integer> seen = new LinkedHashSet<>();
        for (int at = 0; at < normalised.length(); ) {
            int codePoint = normalised.codePointAt(at);
            if (seen.add(codePoint)) {
                (carriesNoMeaningHere(codePoint) ? removable : kept).set(at);
            }
            at += Character.charCount(codePoint);
        }
    }

    /**
     * Whether {@code line} is {@code base} with {@code k} occurrences of {@code one} added to
     * whatever {@code base} already carries, wherever they sit.
     *
     * <p>NOT 「exactly k occurrences in it」, which stood here and is false of the 27 code points
     * the canon lines carry themselves: for a space and k = 1 the line holds three, and the
     * caller's count — which is relative to the base — passes it (batch-65 review, Codex P3).
     *
     * <p>TWO CONDITIONS, AND THE CALLER ASKS A THIRD. The length says exactly {@code k} copies'
     * worth of code units were added; the subsequence says nothing of the base was changed; and
     * the caller's own count says those code units are {@code k} copies of {@code one} and not
     * something else. EACH HAS A CONTROL THAT REACHES IT ALONE, which is the whole reason the
     * three are kept: PN5 appends a hyphen (length alone, 32,640 of 32,640), PM5 reorders the
     * canon line's own words (subsequence alone, 32,640), PO5 swaps the swept code point for a
     * fixed removable of the same char length (count alone, 32,512). 「The length is the only
     * one of the three that a measured edit reaches」 stood here and was wrong twice over — PM5
     * already reached the subsequence, and the COUNT was the one nothing reached (batch-64
     * review, subagent P2-1 on the length; batch-65 review, subagent P2-1 and P3-1 on the count
     * and on this sentence; all measured).
     */
    private static boolean isTheLineWithThatManyInsertions(String base, String line, String one,
            int k) {
        return line.length() == base.length() + k * one.length() && isADeletionOf(base, line);
    }

    /**
     * Which canon line this swept line is built from, and where the insertion sits in it.
     *
     * <p>「reinstated:head」, 「reinstated:inside」, 「withdrawn:tail」 — or 「neither」 when the
     * line is not either canon line with exactly one occurrence of {@code one} inserted. The
     * PAIRING is the claim: counting two lines from one base and one from the other leaves the
     * head and the tail free to swap bases, which keeps every other arm green and silently
     * drops one of the three input shapes (batch-62 review, Codex P2-1).
     *
     * <p>WHICH END is decided by taking the insertion off that end and comparing — not by an
     * index, because the canon lines carry 27 of the swept code points themselves and an index
     * cannot tell their own occurrence from the inserted one.
     */
    private static String shapeOf(String line, String one) {
        for (String base : new String[] { REINSTATED_LINE, WITHDRAWN_LINE }) {
            if (!isTheLineWithOneInsertion(base, line, one)) {
                continue;
            }
            String name = base.equals(REINSTATED_LINE) ? "reinstated" : "withdrawn";
            if (line.substring(one.length()).equals(base)) {
                return name + ":head";
            }
            if (line.substring(0, line.length() - one.length()).equals(base)) {
                return name + ":tail";
            }
            return name + ":inside";
        }
        return "neither";
    }

    /**
     * Whether {@code line} is {@code base} with exactly one occurrence of {@code one} inserted.
     *
     * <p>THE LENGTH TELLS THE TWO BASES APART. The withdrawal line is a SUBSEQUENCE of the
     * reinstatement line — the latter is the former with 撤回 inserted — so the subsequence test
     * alone would call every line built from one a line built from the other. They differ in
     * length by two, and each swept line is exactly one code point longer than its own base.
     */
    private static boolean isTheLineWithOneInsertion(String base, String line, String one) {
        return line.length() == base.length() + one.length() && isADeletionOf(base, line);
    }

    /** The line with every code point {@code removes} accepts taken out. */
    private static String without(String line, IntPredicate removes) {
        StringBuilder surviving = new StringBuilder(line.length());
        line.codePoints().forEach(codePoint -> {
            if (!removes.test(codePoint)) {
                surviving.appendCodePoint(codePoint);
            }
        });
        return surviving.toString();
    }

    /** Compatibility normalisation, named so the alternatives below read as pipelines. */
    private static String compatibility(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFKC);
    }

    /**
     * The near-copies a reader could compute instead of this one, each a measured sabotage.
     *
     * <p>WHAT THE NAMED CORPUS IS FOR. Every one of these is a rule that either refuses a correct
     * canon or reads two different lines as one, and every one of them passed every other arm of
     * {@link #theNearCopyIsThePerCharacterAnswerEverywhere} at some point in batches 53 to 58.
     * The corpus has to tell each of them from the real near-copy, on a line that comes back
     * through {@code linesFor}'s own seam.
     *
     * <p>THE LIST BEING FINITE IS THE BOUND. A rule not in it, keyed on a context no named line
     * carries, is not caught here — which is the same bound the sweep has, written once.
     */
    private static List<Map.Entry<String, UnaryOperator<String>>> alternativeNearCopies() {
        return List.of(
                Map.entry("a cap at the fortieth removable character",
                        line -> compatibility(removalCappedAt(compatibility(line), 40))),
                Map.entry("a rule keyed on the two-character prefix 「xx」",
                        line -> line.startsWith("xx") ? compatibility(line) : asNearCopy(line)),
                Map.entry("the INNER compatibility normalisation dropped",
                        line -> compatibility(withoutWhatCarriesNoMeaningHere(line))),
                Map.entry("the OUTER compatibility normalisation dropped",
                        line -> withoutWhatCarriesNoMeaningHere(compatibility(line))),
                // THIS ONE ASKS NOTHING OF THE CORPUS: 19 of the 22 named lines separate it,
                // and so does a corpus degenerated to single-character lines, so it is the one
                // entry here that OW5 does not redden. It stays because the assertion is also a
                // fixed point — a reader that BECOMES this is caught — but it is not what the
                // named lines are for (batch-59 review, subagent P3-4, measured).
                Map.entry("an upper-casing inside the near-copy",
                        line -> asNearCopy(line).toUpperCase(java.util.Locale.ROOT)),
                Map.entry("the whole supplementary range stripped as well",
                        line -> compatibility(without(compatibility(line),
                                codePoint -> carriesNoMeaningHere(codePoint)
                                        || codePoint > Character.MAX_VALUE))),
                Map.entry("every combining mark stripped as well",
                        line -> compatibility(without(compatibility(line),
                                codePoint -> carriesNoMeaningHere(codePoint)
                                        || Character.getType(codePoint)
                                                == Character.NON_SPACING_MARK))));
    }

    /**
     * What carries no meaning here is exactly the five categories it names — DRIVEN, not restated.
     *
     * <p>NOT A SECOND COPY OF THE CONDITION. The question is asked of the production helper, one
     * code point at a time: a one-character string either survives it or comes back empty. So the
     * lock cannot drift into agreeing with a rewritten expression, which is how batch 46's
     * boundary lock failed (adding one term to both copies passed everything).
     *
     * <p>And each category is counted WHOLE, against its own total taken from the JDK: the class
     * is inside the five (first arm) and contains all of each one (second arm), so it is their
     * union and nothing else. A character excluded by hand moves the second arm even if the same
     * exclusion is written into a second copy of the rule, and it moves it in a new Unicode
     * version too.
     *
     * <p>THE FORMATTING CATEGORY IS IN THE CLASS AND THAT IS A DECISION, recorded in the canon's
     * §5 and changed in batch 51: the ones that matter are invisible — U+200B measured 0.00px of
     * difference on the page — and leaving the category out left a second declaration, or a
     * retraction, hideable behind one. NOT ALL of the category is invisible: a prepended
     * concatenation mark renders (11.86px measured), and removing it is the over-refusal this
     * decision accepts. It is {@link #isPadding} plus the formatting characters, so the limits
     * listed there apply here too.
     */
    @Test
    @DisplayName("what carries no meaning here is the five categories it names, counted whole")
    void whatCarriesNoMeaningHereIsTheFiveCategoriesItNames() {
        SortedSet<Integer> categoriesSeen = new TreeSet<>();
        int[] removed = new int[32];
        int[] allOfThatCategory = new int[32];
        int removedFromTheBlocks = 0;
        int allInTheBlocks = 0;
        int neitherEmptyNorItself = 0;
        int firstThatIsNeither = -1;
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            int type = Character.getType(codePoint);
            if (type < allOfThatCategory.length) {
                allOfThatCategory[type]++;
            }
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            boolean inTheBlocks = block == Character.UnicodeBlock.VARIATION_SELECTORS
                    || block == Character.UnicodeBlock.VARIATION_SELECTORS_SUPPLEMENT;
            if (inTheBlocks) {
                allInTheBlocks++;
            }
            String one = new String(Character.toChars(codePoint));
            String answer = withoutWhatCarriesNoMeaningHere(one);
            // EMPTY OR ITSELF, and nothing else. Without this, a one-line edit that TRANSFORMS
            // what it keeps passes every other arm and every fixture: toUpperCase(codePoint) and
            // a per-character NFKC were both measured green while each refuses a correct canon
            // (batch-52 review, subagent P3-1, who measured both).
            if (!answer.isEmpty() && !answer.equals(one)) {
                neitherEmptyNorItself++;
                if (firstThatIsNeither < 0) {
                    firstThatIsNeither = codePoint;
                }
            }
            if (!answer.isEmpty()) {
                continue;
            }
            categoriesSeen.add(type);
            if (type < removed.length) {
                removed[type]++;
            }
            if (inTheBlocks) {
                removedFromTheBlocks++;
            }
        }
        assertEquals(0, neitherEmptyNorItself,
                "this reader TRANSFORMS a character instead of keeping or dropping it: U+"
                        + (firstThatIsNeither < 0 ? "?"
                                : String.format("%04X", firstThatIsNeither))
                        + " comes back as something else. A rule that rewrites what it keeps "
                        + "makes lines equal that are not — upper-casing, or folding a "
                        + "compatibility form per character — and every other arm here, and every "
                        + "fixture, stays green while a correct canon is refused");
        List<Integer> named = List.of((int) Character.SPACE_SEPARATOR,
                (int) Character.LINE_SEPARATOR, (int) Character.PARAGRAPH_SEPARATOR,
                (int) Character.CONTROL, (int) Character.FORMAT);
        SortedSet<Integer> expected = new TreeSet<>(named);
        expected.add((int) Character.NON_SPACING_MARK);
        assertEquals(expected, categoriesSeen,
                "this reader removes a character from a category it does not name, so a line that "
                        + "differs from the declaration by something that DOES carry meaning here "
                        + "would read as the declaration. It is the five — space separators, line "
                        + "and paragraph separators, controls, formatting characters — WHOLE, "
                        + "plus the variation selectors, which are NON_SPACING_MARK and are taken "
                        + "by BLOCK and not by category. Categories seen: " + categoriesSeen);
        HarnessBroken.unlessEqual(256, allInTheBlocks,
                "the two variation-selector blocks are no longer 256 code points on this JDK ("
                        + allInTheBlocks + "), so what this reader removes has changed with the "
                        + "JDK rather than with this tree. Re-measure which of them leave no ink "
                        + "before letting the new ones be removed");
        assertEquals(allInTheBlocks, removedFromTheBlocks,
                "this reader removes " + removedFromTheBlocks + " of the " + allInTheBlocks
                        + " code points in the two variation-selector blocks, not all of them. A "
                        + "selector left behind hides a retraction: 240 of these are the "
                        + "ideographic variation selectors, which a Japanese canon can carry");
        assertEquals(removedFromTheBlocks, removed[Character.NON_SPACING_MARK],
                "this reader removes " + removed[Character.NON_SPACING_MARK] + " non-spacing "
                        + "marks but only " + removedFromTheBlocks + " of them are in the two "
                        + "blocks, so it is taking off marks that CARRY meaning — a combining "
                        + "accent is Mn too, and removing those makes an accented line a "
                        + "near-copy of a line a reader sees differently");
        // AND IT IS CHARACTER-WISE, which one-code-point probes cannot see. A
        // sequence-sensitive rewrite passes every arm above and every fixture: replacing
        // codePoints() with codePoints().distinct() compiles, answers identically for every
        // single character, and turns 「the retraction with a second ）」 into a near-copy —
        // refusing a correct canon (batch-51 review, Codex P2, measured). So the property is
        // asserted directly: what the helper does to a string is what it does to its characters,
        // joined.
        for (String sample : new String[] { WITHDRAWN_LINE, REINSTATED_LINE, "ああ", "- - ",
                WITHDRAWN_LINE + WITHDRAWN_LINE, "　  ", "a" + "\u200b" + "a" }) {
            StringBuilder characterWise = new StringBuilder();
            sample.codePoints().forEach(codePoint -> characterWise.append(
                    withoutWhatCarriesNoMeaningHere(new String(Character.toChars(codePoint)))));
            assertEquals(characterWise.toString(), withoutWhatCarriesNoMeaningHere(sample),
                    "this reader's removal is no longer character-wise: what it does to 「" + sample
                            + "」 is not what it does to that line's characters, joined. A rule "
                            + "that looks at a character's NEIGHBOURS can refuse a correct canon "
                            + "while every single-character probe and every fixture still passes "
                            + "— measured with codePoints().distinct(), which makes a repeated "
                            + "visible character vanish");
        }
        for (int type : named) {
            assertEquals(allOfThatCategory[type], removed[type],
                    "this reader does not remove the WHOLE of category " + type + ": it removes "
                            + removed[type] + " of " + allOfThatCategory[type] + ". A character "
                            + "excluded by hand lands here even when the same exclusion is "
                            + "written into a second copy of the rule");
        }
    }

    /**
     * The padding class is exactly the categories it names — counted, not re-expressed.
     *
     * <p>NOT A SECOND COPY OF THE IMPLEMENTATION. The first version of this lock computed the same
     * boolean expression, character for character, and compared it with {@link #isPadding}. That
     * is a tautology: editing BOTH copies the same way passes. Measured — adding
     * {@code || codePoint == 0x1F600} to the method AND to the lock left every lock in this class
     * green, and so did excluding a NO-BREAK SPACE from both (batch-46 review, subagent P2-1, who
     * also supplied the form below). The sentence 「so neither side can drift」 was true only of
     * one-sided drift, and 「there is no hand-written set」 was true of the class and not of the
     * fixtures.
     *
     * <p>SO THE RULE IS SAID A DIFFERENT WAY: every code point this class takes off belongs to one
     * of four Unicode categories, and each category contributes a COUNT. A count cannot be
     * satisfied by editing an expression twice — a term added to both copies moves it, a character
     * excluded from both moves it, and a category that is not one of the four fails the first arm
     * outright.
     *
     * <p>AND EACH COUNT IS THE WHOLE OF ITS CATEGORY, counted from the JDK and not written here.
     * That equality is what makes the extension unique: the class is inside the four categories
     * (first arm) and contains all of each one (second arm), so it is their union and nothing
     * else. Hand-written numbers were nearly as tight and had one hole — a future Unicode version
     * that adds a space separator could be 「fixed」 by excluding one character by hand, which puts
     * the count back to 17 and leaves every lock green while the class quietly stops trimming a
     * space (batch-47 review, subagent P3-4). Comparing with the category's own total also means a
     * JDK upgrade does not redden a gate whose class is right.
     *
     * <p>What those totals were when this was written, measured by running this rule over every
     * code point on three JDKs — 17, 21 and the 27 this tree builds with, all three agreeing: Zs
     * 17, Zl 1, Zp 1, Cc 65 — 84 code points, none outside the BMP. {@code Cc} is 65 by Unicode's
     * stability policy, which is the SPECIFICATION's promise and not something this tree measures
     * (the same qualification the thirteen prepended concatenation marks carry below).
     *
     * <p>THE THIRD ARM TAKES TWO LINES TO REACH, and no control measures it. 「It cannot be
     * reached by changing this tree」 stood here for a batch and was measured FALSE (batch-48
     * review, subagent P2-1). The derivation holds only while the FIRST arm's expected set is
     * these four categories — and that set is one line away. Widen the class to the formatting
     * characters and widen the expected set to match — which is not what the first arm's message
     * ASKS for (it says the opposite: the marks come with the category and refuse a correct
     * canon), but it is what silencing a red assertion by widening its expected value looks like
     * — and: the second arm sees nothing, because its branches count the four
     * NAMED categories and a fifth moves none of them; the first arm is satisfied; and this arm
     * fires on the supplementary {@code Cf} code points — with a message that used to send the
     * reader to the JDK. HOW MANY OF THOSE IS JDK-DEPENDENT: measured 127 on JDK 21 and 27 and
     * 120 on JDK 17, so the number is not a constant of this tree (batch-49 review, subagent
     * P3-1, who measured the three JDKs this javadoc names).
     *
     * <p>AND SINCE BATCH 51, THIS ARM IS THE ONLY THING THAT CATCHES THAT EDIT. The paragraph
     * here named a second defence — the fixture loop over the prepended concatenation marks in
     * {@link #theWithdrawalIsTakenBackOnlyByItsOwnLine} — and batch 51 removed those marks from
     * that loop, because the formatting characters are now REMOVED rather than kept. Widening
     * {@code isPadding} with {@code FORMAT} therefore changes no reading at all: control NZ5
     * measured exactly that and its declaration is down to this class's own lock (batch-51
     * review, subagent P2-4).
     *
     * <p>So the arm is two things at once and its message names both: a tripwire on the JDK (a
     * future version putting a member of these four categories outside the BMP, which is what
     * would mean the class takes off a character no reader can type by accident), and the second
     * half of a two-line widening of this tree.
     */
    @Test
    @DisplayName("the padding class is the four categories it names, counted")
    void thePaddingClassIsTheCategoriesItNames() {
        SortedSet<Integer> categoriesSeen = new TreeSet<>();
        int zs = 0;
        int zl = 0;
        int zp = 0;
        int cc = 0;
        int outsideTheBmp = 0;
        int zsAll = 0;
        int zlAll = 0;
        int zpAll = 0;
        int ccAll = 0;
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            // Every member of the four categories, asked of the JDK and not of isPadding: the
            // second arm compares the two, so 「the whole category」 is what is being asserted.
            switch (Character.getType(codePoint)) {
                case Character.SPACE_SEPARATOR -> zsAll++;
                case Character.LINE_SEPARATOR -> zlAll++;
                case Character.PARAGRAPH_SEPARATOR -> zpAll++;
                case Character.CONTROL -> ccAll++;
                default -> { }
            }
            if (!isPadding(codePoint)) {
                continue;
            }
            int type = Character.getType(codePoint);
            categoriesSeen.add(type);
            if (type == Character.SPACE_SEPARATOR) {
                zs++;
            } else if (type == Character.LINE_SEPARATOR) {
                zl++;
            } else if (type == Character.PARAGRAPH_SEPARATOR) {
                zp++;
            } else if (type == Character.CONTROL) {
                cc++;
            }
            if (codePoint > Character.MAX_VALUE) {
                outsideTheBmp++;
            }
        }
        List<Integer> totals = List.of(zsAll, zlAll, zpAll, ccAll);
        assertEquals(new TreeSet<>(List.of((int) Character.SPACE_SEPARATOR,
                        (int) Character.LINE_SEPARATOR, (int) Character.PARAGRAPH_SEPARATOR,
                        (int) Character.CONTROL)),
                categoriesSeen,
                "the padding class takes off a character from a category it does not name. It is "
                        + "meant to be the space separators and the controls and nothing else. "
                        + "Since batch 51 adding the formatting characters here changes no "
                        + "reading — withoutWhatCarriesNoMeaningHere removes them anyway — so "
                        + "what a category added here means is a rule nobody wrote down, and what "
                        + "a category REMOVED here means is a retraction that stops being read. "
                        + "Categories seen: " + categoriesSeen);
        assertEquals(totals, List.of(zs, zl, zp, cc),
                "the padding class is not the WHOLE of each category it names: Zs/Zl/Zp/Cc came "
                        + "out " + List.of(zs, zl, zp, cc) + " where this JDK has " + totals
                        + " altogether. A character excluded by hand lands here even when the "
                        + "same exclusion is written into a second copy of the rule, and it lands "
                        + "here in a new Unicode version too, where a literal 17 would be put "
                        + "back by excluding the character that version added. When this was "
                        + "written all three JDKs measured 17/1/1/65");
        assertEquals(0, outsideTheBmp,
                "the padding class takes off a character outside the BMP. TWO CAUSES, AND THIS "
                        + "ARM CANNOT TELL THEM APART: either the "
                        + "class and the first arm's expected set were widened together — look at "
                        + "isPadding and at the expected set above before looking anywhere else, "
                        + "because the formatting characters alone bring over a hundred "
                        + "supplementary code points (measured 127 on JDK 21 and 27, 120 on JDK "
                        + "17, so do not read this count as a constant) — or this JDK's Unicode "
                        + "version moved a member of these four categories outside the BMP. No "
                        + "control measures this arm");
    }

    /**
     * The forbidding half, and the claim this lock no longer makes.
     *
     * <p>THE PROSE FIXTURE IS THE WITHDRAWAL. A canon that states the withdrawal on its line and
     * ALSO says in prose that the lock came back is GREEN, deliberately. What catches an actual
     * resurrection is the arm in thePlanFigureLockStaysWithdrawn that reads this file's own code,
     * and that arm has its own limits, listed with it.
     */
    @Test
    @DisplayName("a line taking the withdrawal back refuses, and contradicting prose does not")
    void theWithdrawalIsTakenBackOnlyByItsOwnLine() {
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE, REINSTATED_LINE)),
                "a canon carrying both lines passed, so this lock asserts a withdrawal the canon "
                        + "takes back");

        // ANYWHERE in the document, not only beside the statement.
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith("## 1. 何を主張するか",
                        REINSTATED_LINE, "## 5. 測定", WITHDRAWN_LINE)),
                "a line taking the withdrawal back elsewhere in the canon was not read");

        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        "この錠はいまも現役で、取り下げを撤回した。錠を戻した。")),
                "a canon whose PROSE contradicts the line was refused — that is the claim this "
                        + "lock withdrew, and refusing on it is the over-refusal the withdrawal "
                        + "exists to avoid");

        // QUOTED, EMPHASISED, IN BACKTICKS, OR IN AN EXAMPLE is not the statement — which is what
        // lets §5 explain the counter-line's format while the canon stays green.
        for (String written : new String[] {
                "「" + REINSTATED_LINE + "」とは書かない",
                "__取り下げ撤回: plan-figure-lock（R212）__",
                "**" + REINSTATED_LINE + " と決めた**",
                "`" + REINSTATED_LINE + "`",
                "撤回側は `" + REINSTATED_LINE + "` です" }) {
            assertDoesNotThrow(
                    () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE, written)),
                    "a quotation, an emphasis or a backticked copy was taken for the statement "
                            + "itself: " + written);
        }

        // WHAT THE RENDERING CANNOT TELL APART, the retraction must not slip through. Such
        // shapes were measured GREEN against the real canon while §5 promised the opposite
        // (batch-41 review, subagent P2-3). The statement side stays strict, so the same shapes
        // are NOT the statement — that asymmetry is asserted both ways here.
        for (String invisible : new String[] {
                " " + REINSTATED_LINE,
                "   " + REINSTATED_LINE,
                REINSTATED_LINE + " ",
                REINSTATED_LINE + "  ",
                REINSTATED_LINE + "\t",
                "\t" + REINSTATED_LINE,
                "　" + REINSTATED_LINE }) {
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE, invisible)),
                    "a retraction the rendering cannot tell from the line itself slipped past: 「"
                            + invisible.replace("\t", "⇥") + "」");
        }
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(
                        canonWith(WITHDRAWN_LINE, "前置き\r" + REINSTATED_LINE)),
                "a retraction on a lone-CR line slipped past");

        // THE SAME FOUR SHAPES FOR EVERY CHARACTER THAT CARRIES NO MEANING HERE, and the
        // near-copy count answers all of them the same way — which is the point of having one
        // rule instead of a model of the renderer. Three batches put these five characters in
        // three different places (split on them, call them a limit, remove them), each time with
        // a claim about what a reader sees; the specs and the widths contradicted each of those
        // claims (batch-50 review, both reviewers). A character that carries no meaning here is
        // whitespace, a control, a formatting character, or a VARIATION SELECTOR — the two blocks
        // batch 53 added, which this comment kept out for a batch (batch-54 review, Codex P3) —
        // and a line that differs from the retraction only by those IS read, wherever in the line
        // it sits.
        //
        // WHAT IS STILL NOT READ is a line carrying something this reader does not remove —
        // a visible character, one of the seven invisible ones no JDK predicate gathers, or a
        // look-alike SUBSTITUTED for a character of the line. All three are fixtures below.
        for (String carriesNoMeaning : new String[] { "\u2028", "\u2029", "\u0085", "\u000b", "\u000c", "\u0020", "\u0009",
                "\u00a0", "\u200b", "\ufeff", "\u00ad", "\u200e", "\u0600",
                // AND THE VARIATION SELECTORS, taken by block since batch 53: a
                // plain one and one from the supplement, where the 240 ideographic
                // selectors live.
                "\ufe0f", "\udb40\udd00" }) {
            String name = "U+" + String.format("%04X", carriesNoMeaning.codePointAt(0));
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, carriesNoMeaning + REINSTATED_LINE)),
                    "a retraction behind " + name + " at the start of its own line slipped past");
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, REINSTATED_LINE + carriesNoMeaning)),
                    "a retraction followed by " + name + " slipped past");
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                            REINSTATED_LINE.substring(0, 9) + carriesNoMeaning
                                    + REINSTATED_LINE.substring(9))),
                    "a retraction carrying " + name + " INSIDE its own line slipped past. The "
                            + "near-copy is taken over the whole line, so where in the line it "
                            + "sits does not matter");
            assertDoesNotThrow(
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, "前置き" + carriesNoMeaning + REINSTATED_LINE)),
                    "a recorded limit changed: a line with a VISIBLE prefix in front of the "
                            + "retraction is not read, because a visible character is not "
                            + "removed. If that is now read, the canon's §5 has to say so first "
                            + "— and §5 lists this limit beside the two others, the seven "
                            + "invisible characters and the look-alike substitutions");
        }
        // NFD ON THE RETRACTION ONLY. Normalising the WHOLE document made this fixture green
        // under the sabotage that removes normalisation — the statement arm threw first and
        // satisfied the assertThrows, so the retraction side measured nothing (batch-42 review,
        // subagent P3-2: a sibling arm returning the same outcome).
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        java.text.Normalizer.normalize(REINSTATED_LINE,
                                java.text.Normalizer.Form.NFD))),
                "a retraction written in NFD slipped past");

        // CHARACTER BY CHARACTER, over the shapes a paste brings along. String.strip() stops at
        // Character.isWhitespace, so a NO-BREAK SPACE, a FIGURE SPACE, a NARROW NO-BREAK SPACE, a
        // zero-width space, a BOM, a left-to-right mark and a NEL all left a retraction readable
        // on the page and unread by this lock — every one measured against the real canon
        // (batch-42 review: Codex P2, subagent P2-3). A space pasted out of a rendered page is a
        // NO-BREAK SPACE more often than not, which is how the shape arrives. Every character
        // here IS taken off; what is not is the limit below.
        for (String invisible : new String[] {
                // The space separators Character.isWhitespace does not know — a NO-BREAK SPACE is
                // what a paste out of a rendered page produces, which is how this shape arrives.
                "\u00a0", "\u2007", "\u202f", "\u3000", "\u205f", "\u1680",
                // And the controls: a NEL, a BEL, a DEL.
                "\u0085", "\u0007", "\u007f" }) {
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, invisible + REINSTATED_LINE)),
                    "a retraction behind a padding character slipped past: U+"
                            + String.format("%04X", invisible.codePointAt(0)));
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, REINSTATED_LINE + invisible)),
                    "a retraction followed by a padding character slipped past: U+"
                            + String.format("%04X", invisible.codePointAt(0)));
        }

        // AND WHERE THE CLASS STOPS, said as tests rather than only in a comment: a character
        // this reader does not remove — a bullet, a quotation mark, a Hangul filler, a blank
        // braille pattern — is NOT taken off, so a retraction written behind one is not read.
        // 「A character that does not render」 stood here and was wrong about the bullet and the
        // quotation mark, which plainly render. It was NOT wrong about the other two, and the
        // correction overshot: the Hangul filler and the braille blank ADVANCE 13.55px and
        // 10.94px at 16px but leave NO INK — the braille blank in five font stacks of five, the
        // filler in four of five (batch-52 review, subagent P2-4; batch-55 review, subagent P2-4
        // and P3-1, who measured the ink). Advance is not evidence of ink, which is the whole
        // reason the 263 are counted by BOTH conditions. 「Widening
        // past whitespace」 was the earlier wording and was wrong twice over: the class passed
        // whitespace in batch 43 — batch 42 trimmed exactly Character.isWhitespace, and batch 43
        // added the space separators, the formatting characters and the controls — and this line
        // kept saying otherwise through two batches that edited it (batch-44 review, subagent
        // P2-3; batch-45 review, subagent P3-6, who measured the batch the class crossed).
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        splitEveryComposedCharacterWith("\u200b", REINSTATED_LINE))),
                "a retraction whose composed characters were split by a zero-width space was not "
                        + "read — the same hole as on the declaration side (batch-51 review, "
                        + "Codex P2)");

        // A COMPATIBILITY FORM IS THE SAME WORD. 「plan-ﬁgure-lock」 with U+FB01 LATIN SMALL
        // LIGATURE FI read as no retraction under canonical normalisation, while the page shows
        // the word (batch-52 review, Codex P2). The comparison is NFKC now, on both sides.
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        REINSTATED_LINE.replace("fi", "\ufb01"))),
                "a retraction written with the ﬁ ligature was not read. The near-copy is taken "
                        + "after compatibility normalisation, which folds it");

        // AND WHAT THAT COSTS, fixed here so it cannot change quietly: a FULL-WIDTH COLON is a
        // compatibility form of a colon, so 「the declaration with a full-width colon」 is now a
        // NEAR-COPY and a canon carrying it beside the real line is refused. It used to be one of
        // the three ways §5 offered for quoting the line; §5 now offers two.
        assertThrows(AssertionError.class,
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        WITHDRAWN_LINE.replace(":", "："))),
                "a second line differing from the declaration by only a full-width colon was not "
                        + "counted. That is what compatibility normalisation buys — and §5 has to "
                        + "stop offering a full-width colon as a way to quote the line");

        // AND THE REST OF THAT COST, because 「one remedy lost」 was too narrow: EVERY
        // compatibility-equivalent form of the line is a near-copy now. These two are the ones a
        // reviewer would actually type — half-width parentheses, and the line in full-width
        // latin — and a canon carrying either beside the real line is refused (batch-53 review,
        // subagent P3-5, who listed nine such forms).
        for (String compatibilityForm : new String[] {
                WITHDRAWN_LINE.replace("（", "(").replace("）", ")"),
                "- 取り下げ: ｐｌａｎ－ｆｉｇｕｒｅ－ｌｏｃｋ（Ｒ２１２）" }) {
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, compatibilityForm)),
                    "a second line that is a COMPATIBILITY form of the declaration was not "
                            + "counted: 「" + compatibilityForm + "」. The near-copy is taken "
                            + "after NFKC, so every such form counts — which is the price §5 "
                            + "records, and it is not one remedy but a family");
        }

        // AND THE FAIL-OPEN SUBSTITUTION LEAVES, recorded: replacing the three ASCII hyphens with
        // U+2010 HYPHEN draws the SAME PIXELS in four of five font stacks (629px wide in both),
        // and this reader does not read it as a retraction (batch-52 review, subagent P2-5, who
        // measured it). Closing it needs a confusables table — Unicode has one, the JDK does not
        // expose it — and every hand-written set in this class drifted. The shape arrives the way
        // a NO-BREAK SPACE does: a paste out of a rendered page, or an editor's typography.
        assertDoesNotThrow(
                () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE,
                        REINSTATED_LINE.replace("-", "\u2010"))),
                "the recorded fail-open closed: a retraction written with U+2010 HYPHEN instead "
                        + "of the ASCII hyphen is now read. That is better than the record says "
                        + "— update §5 and this comment, and say what made it possible");

        // AND THE FAIL-OPEN THAT IS LEFT: SEVEN CHARACTERS, in all three positions. A sweep of
        // every assigned code point outside Zs/Zl/Zp/Cc/Cf found 263 that BOTH advance 0.00px AND
        // leave no ink IN ALL FIVE STACKS — neither condition alone gives 263: ink alone admits
        // U+2800 (68.36px at 100px) and U+FFA0 (50.00px), and advance alone admits the inked
        // combining marks this reader must NOT remove (batch-54 review, subagent P2-3 and P2-4).
        //
        // AND 「IN ALL FIVE STACKS」 IS PART OF THE CONDITION, which this comment stated without
        // it while stating the answer as seven. NARROW IT TO ONE STACK AND SEVEN IS NOT THE
        // NUMBER: U+F860 and U+F861, in Apple's private use area, advance 0.00px AND leave no ink
        // in system-ui, so a reader on that stack has two more characters that hide a retraction
        // completely — U+F862, U+F87A and U+F87F leave no ink there too but take advance. Batch
        // 58 measured all five and recorded them in the plan; what it did not do is bring the
        // qualifier to the claim (batch-58 review, subagent P2-4).
        //
        // THE METHOD, because without it the number did not reproduce and 「14」 was recorded for
        // a batch: five stacks — sans-serif, serif, monospace, system-ui, 'Hiragino Sans' — at
        // 100px, drawn into a 400×400 canvas WITH THE PEN AT x=150, y=250. The pen matters: a
        // zero-advance mark draws to the LEFT of it, so at x=20 seven ordinary combining marks
        // (U+0740, U+0EC8, U+0F87, U+1DCE, U+1DDD, U+20D3, U+1E130) read as 0 ink and the sweep
        // returned 270 = 256 + 14. At x=150 they ink 129 to 414 pixels in all five stacks, while
        // these seven ink nothing at either origin (batch-56 review, subagent P1, who found the
        // artifact in their own earlier sweep; re-measured here before reverting) — and
        // 256 of those are the two variation-selector blocks, which batch 53 now removes. These
        // seven are the rest: U+034F COMBINING GRAPHEME JOINER, U+17B4 and U+17B5 KHMER VOWEL
        // INHERENT, U+180B to U+180D MONGOLIAN FREE VARIATION SELECTOR, and U+FFFC OBJECT
        // REPLACEMENT CHARACTER (batch-52 review, subagent P2-1, who measured all 148,997).
        //
        // WHY THESE SEVEN STAY: six are Mn OUTSIDE those blocks and the seventh is So, so no JDK
        // predicate or block gathers them — only a hand-written set would, and seven of those
        // drifted over seven batches. Taking Mn as a CATEGORY is not available either: a
        // combining accent is Mn, and removing those makes an accented line a near-copy of a line
        // a reader sees differently (and it breaks this class's own NFD fixtures, measured).
        // 「The JDK cannot say invisible」 stood here and was too strong — it could say 256 of
        // them by block (batch-52 review, subagent P2-2).
        for (String invisibleButKept : new String[] { "\u034f", "\u17b4", "\u17b5",
                "\u180b", "\u180c", "\u180d", "\ufffc" }) {
            String name = "U+" + String.format("%04X", invisibleButKept.codePointAt(0));
            for (String shape : new String[] { invisibleButKept + REINSTATED_LINE,
                    REINSTATED_LINE + invisibleButKept,
                    REINSTATED_LINE.substring(0, 9) + invisibleButKept
                            + REINSTATED_LINE.substring(9) }) {
                assertDoesNotThrow(
                        () -> theCanonSaysTheWithdrawalStands(canonWith(WITHDRAWN_LINE, shape)),
                        "the recorded fail-open closed: a retraction behind " + name + " is now "
                                + "read. That is better than the record says — update §5 and this "
                                + "comment, and say what predicate made it possible");
            }
        }

        for (String notRemoved : new String[] { "* ", "+ ", "> ", "「",
                // NOT characters this reader removes: a Hangul filler (Lo) and a blank braille
                // pattern (So). They carry meaning here in the only sense this reader has — not
                // whitespace, not a control, not a formatting character, not a variation selector
                // — so a line behind one is a different line. 「Invisible to a reader」 stood here
                // and 「was measured FALSE for both」 replaced it, which was ALSO wrong, and
                // 「both leave NO INK」 replaced THAT, which is wrong for the filler: both
                // ADVANCE (13.55px and 10.94px at 16px); the braille blank draws nothing in all
                // five stacks, and the filler draws 1,616 pixels in serif and nothing in the
                // other four (batch-55 review, subagent P2-4; batch-57 review, subagent P2-4,
                // who found this sentence still carrying the stronger claim). So 「not removed」
                // is one question and 「invisible」 is another, and for the filler the answer
                // depends on the font. The variation selectors were in this list until batch 53.
                "\u3164", "\u2800" }) {
            assertDoesNotThrow(
                    () -> theCanonSaysTheWithdrawalStands(
                            canonWith(WITHDRAWN_LINE, notRemoved + REINSTATED_LINE)),
                    "the recorded limit changed: a retraction behind 「" + notRemoved.trim()
                            + "」 (U+" + String.format("%04X", notRemoved.codePointAt(0))
                            + "), which this reader does not remove, is now read. That is "
                            + "a wider claim than the canon's §5 makes.\n\n"
                            + "THE FORMATTING CHARACTERS USED TO BE IN THIS LIST — a "
                            + "prepended concatenation mark, a zero-width space, a BOM, a "
                            + "soft hyphen, a left-to-right mark — and batch 51 moved them "
                            + "to the matrix above, where a line differing by only those "
                            + "IS read. Batch 43 measured the reason they were excluded: "
                            + "trimming them made a line a reader sees as DIFFERENT count "
                            + "as the retraction. What changed is that the outcome is now "
                            + "a refusal of the canon with a message saying so, not a "
                            + "silent reading — and the hole it leaves open otherwise was "
                            + "measured at 0.00px of difference on the page");
        }

        // A RETRACTION ON A LINE OF ITS OWN IS READ WHEREVER IT SITS. §5 and the constant's
        // javadoc both say so and nothing drove it; three rounds added and removed a fence skip,
        // so the shape is one that comes back (batch-43 review, subagent P3-4).
        for (String[] hidden : new String[][] {
                { "```", REINSTATED_LINE, "```" },
                { "    ```", REINSTATED_LINE, "    ```" },
                { "~~~", REINSTATED_LINE, "~~~" },
                { "<!--", REINSTATED_LINE, "-->" } }) {
            String[] canon = new String[hidden.length + 1];
            canon[0] = WITHDRAWN_LINE;
            System.arraycopy(hidden, 0, canon, 1, hidden.length);
            assertThrows(AssertionError.class,
                    () -> theCanonSaysTheWithdrawalStands(canonWith(canon)),
                    "a retraction on a line of its own inside 「" + hidden[0].trim()
                            + "」 was not read, while §5 says it is");
        }
    }

    /**
     * A section heading's label: what it is called, without the dated parenthesis or the summary
     * after the dash. 「バッチ 20 — R186: …（2026-10-19…）」 is 「バッチ 20」, 「確認 35 巡目（…」 is
     * 「確認 35 巡目」. This is what the readiness document names, and it is compared EQUAL: both
     * 「バッチ 2」 and 「R186」 are substrings of that first heading.
     */
    private static String labelOf(String heading) {
        int dash = heading.indexOf(" — ");
        int paren = heading.indexOf('（');
        int end = heading.length();
        if (dash >= 0) {
            end = Math.min(end, dash);
        }
        if (paren >= 0) {
            end = Math.min(end, paren);
        }
        return heading.substring(0, end).trim();
    }

    /**
     * The document's PROSE: inline code, fenced blocks and HTML comments taken out, or a refusal.
     *
     * <p>In this order, and the order is the point. Inline code goes first because this plan talks
     * ABOUT markdown — it mentions ``` and {@code <!--} inside backticks — and a mention is not a
     * construct; counting delimiters anywhere refused the document for one of those mentions
     * (round 33), and counting only line-start ones left a mid-line {@code <!--} able to hide a
     * whole fake section (round-35 review: Codex P2, subagent P2-3, both measured round=99).
     * Fences go next, because a comment delimiter inside a fence is code; a fence may be three or
     * more backticks or tildes and closes with at least as many of the same (round-35 review,
     * Codex P2). Comments go last, from anywhere, and an opener that never closes is a refusal:
     * ignoring it let a later {@code -->} delete everything between (round-34 review, subagent
     * P2-3).
     *
     * <p>What is deliberately NOT stripped: a four-space or tab indented code block. The loose
     * counter below limits itself to three spaces of indent instead, so such a block cannot be
     * read as a gate line (round-35 review: Codex P2, subagent P3-3).
     *
     * <p>{@code document} names the file in the refusals. Both of them said 「the batch plan」 while
     * this reader had two callers, so a canon that failed to close a fence sent whoever read the
     * refusal to the wrong document (batch-35 review, subagent P3-4).
     */
    private static String asProse(String markdown, String document) {
        markdown = withoutInlineCode(markdown);
        markdown = withoutFences(markdown, document);
        return withoutSpans(markdown, Pattern.compile("<!--"), "-->", "an HTML comment", document);
    }

    /** Backtick spans removed, so a mention of a construct is not read as one. */
    private static String withoutInlineCode(String markdown) {
        StringBuilder prose = new StringBuilder();
        // ONE LINE: a code span does not cross a paragraph, and letting it cross lines made the
        // span reach from a MENTION of ``` to a real fence and swallow the gate paragraph between
        // them — the reader then refused the document for an unclosed fence (measured while
        // driving these fixtures, round 35).
        Matcher span = Pattern.compile("(?<!`)(`+)(?!`)([^\\n]+?)(?<!`)\\1(?!`)")
                .matcher(markdown);
        int at = 0;
        while (span.find()) {
            prose.append(markdown, at, span.start());
            at = span.end();
        }
        return prose.append(markdown, at, markdown.length()).toString();
    }

    /**
     * Fenced blocks removed: three or more backticks or tildes, closed by at least as many.
     *
     * <p>An opener that never closes is a refusal — a heading inside an unclosed example would
     * otherwise read as a section.
     */
    private static String withoutFences(String markdown, String document) {
        StringBuilder prose = new StringBuilder();
        Matcher opener = Pattern.compile("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*$").matcher(markdown);
        int at = 0;
        while (opener.find(at)) {
            String delimiter = opener.group(1);
            // The closer is built from the delimiter CHARACTER, not from a quoted span: a
            // `\Q`\E{3,}` quantifies the end of the quote rather than the backtick, so the real
            // fence never matched its own closer and the reader refused the document (measured
            // while driving the fence fixtures, round 35).
            Matcher closer = Pattern.compile("(?m)^[ \\t]{0,3}" + delimiter.charAt(0)
                    + "{" + delimiter.length() + ",}[ \\t]*$").matcher(markdown);
            if (!closer.find(opener.end())) {
                throw new HarnessBroken(document + " opens a 「" + delimiter + "」 fence at "
                        + "offset " + opener.start() + " and never closes it, so this reader "
                        + "cannot tell its prose from its code");
            }
            prose.append(markdown, at, opener.start());
            at = closer.end();
        }
        return prose.append(markdown, at, markdown.length()).toString();
    }

    /** Every opener-to-closer span removed, or a refusal naming the one that never closes. */
    private static String withoutSpans(String markdown, Pattern opener, String closer, String what,
            String document) {
        StringBuilder prose = new StringBuilder();
        int at = 0;
        while (true) {
            Matcher open = opener.matcher(markdown);
            if (!open.find(at)) {
                prose.append(markdown, at, markdown.length());
                return prose.toString();
            }
            int closes = markdown.indexOf(closer, open.end());
            if (closes < 0) {
                throw new HarnessBroken(document + " opens " + what + " at offset "
                        + open.start() + " and never closes it, so this reader cannot tell its "
                        + "prose from its code");
            }
            prose.append(markdown, at, open.start());
            at = closes + closer.length();
        }
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
        HarnessBroken.unless(classified > 0, "the RC gate's table carries no residual ids: " + gate);

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
        HarnessBroken.unless(says.find(), "the RC gate's heading no longer says how many it classified");
        assertEquals(classified, Integer.parseInt(says.group(1)),
                "the RC gate's heading says it classified " + says.group(1) + " residuals and "
                        + "its table carries " + classified);
        assertFalse(says.find(), "the section carries more than one RC condition 11 heading");
        // Anchored on the sentence that does the counting, not on the verdict before it: until
        // 2026-09-29 this read 「書ける状態にまだない。開いている N 件を」, and the verdict is
        // the part of that sentence that changes when the condition is met.
        Matcher under = Pattern.compile("開いている (\\d+) 件を\\s*1 つずつ読んで分類した")
                .matcher(intro);
        HarnessBroken.unless(under.find(), "the RC gate's prose no longer says how many are open");
        assertEquals(classified, Integer.parseInt(under.group(1)),
                "the RC gate's prose says " + under.group(1) + " open residuals and its table "
                        + "carries " + classified);
        assertFalse(under.find(), "the RC gate states its open count in more than one sentence, "
                + "so which one this compares is a guess");
    }

    /**
     * Condition 11 is declared met exactly when the gate's table allows it (2026-09-29).
     *
     * <p>The declaration is prose and the severities are the table under it, so the two can
     * part: a P0 or P1 row added later, or a residual carried without a severity — the three
     * frozen ones stood in the table as 「凍結」 until 2026-09-29 — would leave "met" standing
     * over a table that contradicts it. The other direction is read too: a table that allows
     * the declaration, under prose that withholds it, is the document reading as further from
     * release than the work is.
     *
     * <p>A row allows it when its classification names P2, P3 or 「設計判断待ち」 and names no
     * P0 or P1. 「設計判断待ち」 is R67's: a feature that cannot be exported yet — the export
     * entry refuses the version — not an answer given wrongly, and the gate has read it as not
     * blocking since it was first classified on 2026-09-23. This lock writes that reading down
     * instead of leaving it implicit.
     *
     * <p>The declaration has two exits in this document — the gate's own introduction and §0's
     * list of met conditions — and both are read.
     */
    @Test
    @DisplayName("condition 11 is declared met exactly when its table holds no P0, no P1 and nothing unclassified")
    void conditionElevenIsDeclaredExactlyWhenTheTableAllowsIt() throws IOException {
        String readiness = read(READINESS);
        String gate = slice(readiness, "### RC 条件 11", "## ",
                "the readiness document's RC condition 11 section");
        String intro = gate.split("(?m)^\\| ", 2)[0];

        SortedSet<String> blocking = new TreeSet<>();
        int rows = 0;
        for (String line : gate.split("\n")) {
            if (!line.startsWith("| R")) {
                continue;
            }
            rows++;
            String[] cells = line.split("\\|", 4);
            assertTrue(cells.length >= 4, "an RC gate row has no classification cell: " + line);
            String classification = cells[2];
            boolean allows = classification.matches("(?s).*(\\bP[23]\\b|設計判断待ち).*")
                    && !classification.matches("(?s).*\\bP[01]\\b.*");
            if (!allows) {
                blocking.add(cells[1].trim() + " —" + classification);
            }
        }
        HarnessBroken.unless(rows > 0, "the RC gate's table carries no rows: " + gate);

        boolean introDeclares = Pattern.compile("条件 11 は 20\\d\\d-\\d\\d-\\d\\d に満たした")
                .matcher(intro).find();
        String zero = slice(readiness, "## 0. 現在地", "## 1.", "the readiness document's §0");
        boolean zeroLists = zero.contains("/ 11 open P0/P1 = 0（");

        if (blocking.isEmpty()) {
            assertTrue(introDeclares, "every row of the RC gate's table is P2, P3 or pending a "
                    + "design decision, and the gate's introduction does not declare condition 11 "
                    + "met: " + intro);
            assertTrue(zeroLists, "every row of the RC gate's table allows condition 11, and §0 "
                    + "does not list it among the met conditions");
        } else {
            assertFalse(introDeclares, "the gate's introduction declares condition 11 met over "
                    + "rows that are P0, P1 or unclassified: " + blocking);
            assertFalse(zeroLists, "§0 lists condition 11 as met over rows that are P0, P1 or "
                    + "unclassified: " + blocking);
        }
    }

    /**
     * The canon's count of the controls that name the sweep is the runner's.
     *
     * <p>Written because the figure was wrong four rounds running (6 → 7 → 9 → 12): each time the
     * controls added in the SAME batch were left out, and twice the count came from a grep that
     * only knew single quotes while the runner reads both. What this stops is the figure IN THE
     * CANON drifting; no lock compares the PLAN's copies of it with the runner, which is why
     * two stale ones sat there for a round. (The plan is not unread — {@code
     * NoForbiddenClaimShipsTest} walks every .md under docs — it is unread BY A NUMBER CHECK;
     * saying otherwise was the fourth time this tree claimed a gap without grepping for the
     * existing reader, round-23 review, subagent P2-2.)
     * This javadoc is this method's own. The gap-count lock further down has its own again:
     * inserting this method before that declaration orphaned the one that was there for a round
     * (round-20 review, subagent P2-1; the fourth time this tree has made that edit).
     */
    @Test
    @DisplayName("the canon's count of the sweep's own controls is the runner's")
    void theSweepsOwnControlCountIsTheRunners() throws IOException {
        // Written because the figure was wrong four rounds running (6 → 7 → 9 → 12): each time
        // the controls added in the SAME batch were left out, and twice the count came from a grep
        // that only knew single quotes while the runner reads both. What this stops is the figure
        // IN THE CANON drifting; no lock compares the PLAN's copies of this figure with the
        // runner, which is why two stale ones sat there for a round (round-21 review, subagent
        // P2-4 — "discipline did not stop it; this does" was stronger than the lock). The plan IS
        // read, by the forbidden-claim sweep; what no lock does is compare its NUMBERS.
        String runner = read(RUNNER);
        String canon = read(CANON);

        // BY POSITION, on the anchored declaration lines its siblings use. `id=…(?s).*?test=…`
        // walks past the end of its own dict: FV3's sabotage text contains `-Dtest='…'`, so that
        // control was read as naming "!MultiThreadTest,…" and never as naming its own test class
        // — a control whose sabotage quotes a `test=` line would have dropped out of this count
        // in silence (round-20 review: subagent P2-2 measured it, Codex P3 named the shape).
        // WITHIN ONE dict. Pairing a `test=` with "the id above it" reads the wrong control as
        // soon as a dict puts `test=` first — valid Python, and then the id of the PREVIOUS
        // control is credited and this one drops out (round-23 review, Codex P2).
        SortedSet<String> naming = new TreeSet<>();
        Matcher dict = Pattern.compile("(?m)^\\s*dict\\($").matcher(runner);
        int dicts = 0;
        while (dict.find()) {
            int end = runner.indexOf("\n    ),", dict.end());
            if (end < 0) {
                throw new HarnessBroken("a control dict starting at " + dict.start()
                        + " does not close, so this reader cannot pair its id with its test");
            }
            String body = runner.substring(dict.end(), end);
            dicts++;
            Matcher id = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(body);
            Matcher test = Pattern.compile("(?m)^\\s+test=[\"']([^\"']+)[\"'],").matcher(body);
            if (id.find() && test.find()
                    && "HarnessBreakageIsNotAFiringTest".equals(test.group(1))) {
                naming.add(id.group(1));
            }
        }
        int declared = 0;
        Matcher everyId = Pattern.compile("(?m)^\\s+id=[\"']([A-Z0-9]+)[\"'],").matcher(runner);
        while (everyId.find()) {
            declared++;
        }
        int closers = 0;
        Matcher closer = Pattern.compile("(?m)^\\s+\\),$").matcher(runner);
        while (closer.find()) {
            closers++;
        }
        HarnessBroken.unlessEqual(declared, closers,
                "this reader found " + closers + " dict closers and " + declared + " ids. Three "
                        + "counts have to agree — opener, id and closer — because all three are "
                        + "read from raw lines, and a crafted sabotage string could fake one of "
                        + "them (round-25 review, Codex P2)");
        HarnessBroken.unlessEqual(declared, dicts,
                "this reader found " + dicts + " control dicts and the runner declares " + declared
                        + " ids. It reads raw lines, so a dict opener written another way (`dict (`)"
                        + " or a `test=` line sitting inside a sabotage string moves the two apart —"
                        + " and then this count is of something else (round-24 review, Codex P2)");
        HarnessBroken.unless(!(naming.isEmpty()),
                "no control names the sweep at all, so this reader is measuring nothing");

        Matcher stated = Pattern.compile(
                "\\*\\*掃き取り自身を名指す control は (\\d+) 本\\*\\*（([^）]+)）").matcher(canon);
        int statements = 0;
        while (stated.find()) {
            // EVERY copy. The sibling that reads the sweep boundary learned this the same way:
            // reading only the first let a second one drift unread (round-20 review, subagent
            // P2-3, which found two stale copies of this very figure in the plan).
            statements++;
            assertEquals(naming.size(), Integer.parseInt(stated.group(1)),
                    "the canon says " + stated.group(1) + " controls name the sweep and the runner "
                            + "has " + naming.size() + ": " + naming);
            assertEquals(naming, new TreeSet<>(List.of(stated.group(2).split("・"))),
                    "the canon lists different control ids than the runner declares for the sweep: "
                            + naming);
        }
        assertEquals(1, statements,
                "the canon states this figure " + statements + " times; it is the single source for "
                        + "it, so there has to be exactly one");
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
        Matcher boundary = Pattern.compile("境界 ([A-Z]{2}[3-9])").matcher(canon);
        HarnessBroken.unless(boundary.find(), "the canon no longer names the sweep boundary");
        String last = boundary.group(1);

        // The three-letter sequence the ids run through, from the boundary to the newest: the
        // "3" generation AA3…ZZ3, then the "4" generation AA4…ZZ4 that follows it, and so on (the
        // first id after ZZ3 was AA4, and after ZZ4 AA5, 2026-10-13; a sequence that stopped at
        // a generation would count no gap past it).
        List<String> sequence = new ArrayList<>();
        for (char generation = '3'; generation <= '9'; generation++) {
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

        HarnessBroken.unless(statesACount, "the canon no longer states a gap count");
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
        HarnessBroken.unless(stated.find(), "the readiness document does not state a verifier-core count");
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
        HarnessBroken.unless(row.find(), "the canon has no R59 row");
        Matcher counts = Pattern.compile("錠 (\\d+) 本中 R59 の (\\d+) 本（(.*?)）、control").matcher(row.group());
        HarnessBroken.unless(counts.find(), "the R59 row no longer states its lock counts in the expected shape");
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
            throw new HarnessBroken("core/pom.xml could not be read: " + unreadable.getMessage());
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
            throw new HarnessBroken("the R59 row names a test class that is not on the class path: " + className);
        }
        // The same tag filters surefire runs under, so a tagged lock that the suite skips is not
        // counted as run (review, P3). The EFFECTIVE values: surefire forwards -D user
        // properties into the forked JVM, so an override on the command line is read before the
        // pom's default (review, forty-seventh round, P3); the include side (-Dgroups) likewise.
        String excludedSetting = System.getProperty("surefire.excludedGroups");
        if (excludedSetting == null) {
            Matcher excluded = Pattern.compile("<surefire\\.excludedGroups>([^<]*)</")
                    .matcher(readPomForExcludedGroups());
            HarnessBroken.unless(excluded.find(), "core/pom.xml no longer declares the excluded groups, so this "
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
        HarnessBroken.unless(!(names.isEmpty()), "JUnit discovered no test in " + className + ", so this counted nothing");
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
        HarnessBroken.unless(inline.find(), "the readiness document's summary line no longer repeats the "
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
    @DisplayName("the frozen residuals say so in the canon, and the plan names exactly those")
    void theFrozenResidualsAreMarkedInBothPlaces() throws IOException {
        String canon = read(CANON);
        String plan = read(PLAN);

        for (String id : new TreeSet<>(FROZEN)) {
            Matcher row = Pattern.compile("(?m)^\\| " + id + " \\| (.*)$").matcher(canon);
            HarnessBroken.unless(row.find(), "the canon has no row for " + id);
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
     *
     * <p>「未着手 = なし」 is the untouched line once every phase has begun (Phase 9 did on
     * 2026-10-05). The line itself stays required — the partition below still has to hold.
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
        Matcher notStarted = Pattern.compile("\\*\\*未着手 = (?:Phase ([\\d・]+)|なし)\\*\\*")
                .matcher(readiness);
        assertTrue(notStarted.find(), "the readiness document no longer says which phases are "
                + "untouched, so 'started' has nothing to be the complement of");

        int from = Integer.parseInt(started.group(1));
        int to = Integer.parseInt(started.group(2));
        SortedSet<String> claimedNotStarted = notStarted.group(1) == null
                ? new TreeSet<>()
                : new TreeSet<>(List.of(notStarted.group(1).split("・")));

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
            HarnessBroken.unless(row.find(), "the plan has no row for Phase " + phase);
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
        HarnessBroken.unless(Files.exists(release), "the verifier release runbook is not at " + release);
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
        assertEquals(List.of(), LedgerText.unreadablePathLines(yaml), "the workflow's paths carry "
                + "a negated pattern or a block scalar, so which files start it cannot be read "
                + "from the list — this is not a missing entry");
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
        HarnessBroken.unless(!(read.isEmpty()),
                "no lock appears to open a file outside core. Either the tests moved or this "
                        + "check's pattern no longer matches how they name a path, and it would "
                        + "pass by finding nothing");
        HarnessBroken.unless(read.contains("RELEASE_NOTES.md") && read.contains("docs"),
                "the locks that read the release notes and docs/ as a root are not seen here, so "
                        + "this check no longer sees files outside docs/ or directory roots: " + read);

        assertEquals(List.of(), LedgerText.unreadablePathLines(yaml), "the workflow's paths carry "
                + "a negated pattern or a block scalar, so which files start it cannot be read "
                + "from the list — this is not a missing entry");
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
        HarnessBroken.unless(latestRound > 0, "the canon records no completed sweep");
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
            HarnessBroken.unless(sites >= 1, "the " + doc.getKey() + " no longer states the "
                    + "added-since-sweep count anywhere, so nothing here can be checked");
        }
    }
}
