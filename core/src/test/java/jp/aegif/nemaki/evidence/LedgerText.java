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
package jp.aegif.nemaki.evidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the locks that read the progress documents read two things: a sweep figure, and a path
 * the workflow starts for.
 *
 * <p>Shared because two classes in two packages read both, and each had grown its own reading —
 * one compared every sweep figure with the latest sweep, the other with the sweep its sentence
 * named; one counted a path's quoted occurrences anywhere in the workflow, which a comment or a
 * duplicate in one trigger satisfied (both reviews, fourth round of the parallel-review batch).
 *
 * <h2>One written form for a sweep figure</h2>
 *
 * <p>Outside the canon's own record lines, a document states what a sweep ran in exactly one
 * form: {@link #SWEEP_FIGURE} — 「6 回目の通し（1551 本）」. Every other four-digit figure is
 * today's total. This replaced three rounds of reading free text for which round a figure
 * belonged to (the first round in the sentence, then the nearest before, then the nearest either
 * side), each of which a reviewer broke with a sentence the documents could plausibly contain:
 * two records in one sentence, a round after its figure, 「6 回目の通しの後に足した分」, a line
 * break before 「以後」 (both reviews, fifth and sixth rounds). A form that names its round in
 * the same token as its figure cannot be misattributed, and a four-digit figure written any other
 * way is read as the total — refused if it is not, with the form to use named in the failure.
 *
 * <p>What the form cannot stop: a sentence that uses it to say something else —
 * 「現行の総数は 6 回目の通し（1551 本）と同じ」 reads as a true record of the sixth sweep, and the
 * false claim about the total is in words this does not parse (Codex, seventh round). The total
 * itself is carried by the documents' own total statements, which the locks compare with the
 * runner; a sentence that restates it through a sweep figure is a review matter.
 */
public final class LedgerText {

    private LedgerText() {
    }

    /** The canon's record of a completed sweep: 「6 回目 2026-09-25〜26（1551 本、…」. */
    private static final Pattern SWEEP_RECORD =
            Pattern.compile("(\\d+) 回目 20\\d\\d-\\d\\d-\\d\\d[^（]*（(\\d+) 本");

    /** How any document other than the canon's record lines states what a sweep ran. */
    public static final Pattern SWEEP_FIGURE = Pattern.compile("(\\d+) 回目の通し（(\\d+) 本）");

    /** The sweeps the canon records, round to the number of controls that round ran. */
    public static Map<Integer, Integer> sweptInRound(String canon) {
        Map<Integer, Integer> swept = new HashMap<>();
        Matcher sweeps = SWEEP_RECORD.matcher(canon);
        while (sweeps.find()) {
            swept.putIfAbsent(Integer.parseInt(sweeps.group(1)), Integer.parseInt(sweeps.group(2)));
        }
        return swept;
    }

    /**
     * Rounds the canon records more than once with different numbers. The map above keeps the
     * FIRST record; before this check a later, different record was silently overruled, and the
     * version before that kept the last one, so a wrong record written after the right one won
     * (Codex, P3).
     */
    public static List<String> conflictingSweeps(String canon) {
        Map<Integer, Integer> first = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        Matcher sweeps = SWEEP_RECORD.matcher(canon);
        while (sweeps.find()) {
            int round = Integer.parseInt(sweeps.group(1));
            int ran = Integer.parseInt(sweeps.group(2));
            Integer earlier = first.putIfAbsent(round, ran);
            if (earlier != null && earlier != ran) {
                conflicts.add("round " + round + ": " + earlier + " and " + ran);
            }
        }
        return conflicts;
    }

    /** A four-digit 「N 本」 — with the bold or the space a writer may leave out — and where it is. */
    public record Figure(int value, int at, String context) { }

    private static final Pattern FOUR_DIGIT_COUNT = Pattern.compile("(?<![0-9,])([0-9]{4})\\*{0,2} ?本");

    /**
     * The four-digit 「N 本」 figures in {@code text} that are NOT a sweep figure in its form nor
     * inside text shaped like a line of the canon's sweep record (「6 回目 2026-09-25〜26（1551 本」
     * — masked in whichever document it appears, not only the canon) — each of which, in these
     * documents, is today's control total. Comma-grouped numbers (「7,706 本」) are the unit and
     * verifier test totals by this tree's convention and are not read.
     */
    public static List<Figure> figuresOutsideSweeps(String text) {
        StringBuilder rest = new StringBuilder(text);
        for (Pattern sweep : new Pattern[] {SWEEP_FIGURE, SWEEP_RECORD}) {
            Matcher m = sweep.matcher(text);
            while (m.find()) {
                for (int i = m.start(); i < m.end(); i++) {
                    rest.setCharAt(i, ' ');
                }
            }
        }
        List<Figure> figures = new ArrayList<>();
        Matcher count = FOUR_DIGIT_COUNT.matcher(rest);
        while (count.find()) {
            figures.add(new Figure(Integer.parseInt(count.group(1)), count.start(),
                    text.substring(Math.max(0, count.start() - 40),
                            Math.min(text.length(), count.end() + 10)).replace('\n', ' ')));
        }
        return figures;
    }

    /**
     * The lines of either trigger's {@code paths:} list that this helper cannot read as a plain
     * entry: a negated pattern ({@code '!…'}, anywhere on a non-comment line — a folded scalar
     * puts the {@code !} on the line after the dash, Codex seventh round) and a block-scalar item
     * ({@code - >} / {@code - |}). Reported separately from "not listed" so a failure names what
     * is actually there (subagent review, P3).
     */
    public static List<String> unreadablePathLines(String yaml) {
        List<String> unreadable = new ArrayList<>();
        for (String trigger : new String[] {"push", "pull_request"}) {
            String paths = pathsOf(yaml, trigger);
            for (String line : paths.split("\n")) {
                String code = line.replaceFirst("\\s#.*$", "").replaceFirst("^\\s*#.*$", "");
                if (code.contains("!") || code.matches("^\\s*-\\s*[>|].*")) {
                    unreadable.add(trigger + ": " + line.trim());
                }
            }
        }
        return unreadable;
    }

    private static String pathsOf(String yaml, String trigger) {
        return block(block(yaml, "\n  " + trigger + ":", "\n  [a-z_]+:|\n[a-z_]+:"),
                "\n    paths:", "\n    [a-z_-]+:");
    }

    /**
     * Whether {@code path} is an entry of the {@code paths:} list of BOTH the push and the
     * pull_request trigger, and neither list carries a negated pattern ({@code '!…'}).
     *
     * <p>Counting the quoted path anywhere in the file was satisfied by a comment, or by one
     * trigger listing it twice while the other did not list it at all (Codex, P2); taking any
     * list line inside a trigger was satisfied by {@code paths-ignore:} or a block-style
     * {@code branches:} (Codex, P2, and subagent review). A negated pattern later in the list
     * cancels an earlier match in GitHub's evaluation, and working out WHICH paths it cancels is
     * glob matching this helper does not do — so a list that carries one answers "not listed"
     * for every path, rather than "listed" for paths it may exclude (subagent review, P2).
     * An entry may be single-quoted, double-quoted or bare, as YAML allows (Codex, P2).
     */
    public static boolean listedInBothTriggers(String yaml, String path) {
        Pattern entry = Pattern.compile("(?m)^\\s*-\\s*(['\"]?)" + Pattern.quote(path)
                + "\\1\\s*(#.*)?$");
        if (!unreadablePathLines(yaml).isEmpty()) {
            return false;
        }
        for (String trigger : new String[] {"push", "pull_request"}) {
            if (!entry.matcher(pathsOf(yaml, trigger)).find()) {
                return false;
            }
        }
        return true;
    }

    /** The text after {@code opener} up to the next match of {@code closer}, or "" if absent. */
    private static String block(String text, String opener, String closer) {
        int at = text.indexOf(opener);
        if (at < 0) {
            return "";
        }
        int from = at + opener.length();
        Matcher next = Pattern.compile(closer).matcher(text);
        return text.substring(from, next.find(from) ? next.start() : text.length());
    }
}
