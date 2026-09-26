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
 * the same token as its figure cannot be misattributed, and a figure written any other way is
 * read as the total — refused if it is not, with the form to use named in the failure.
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
        Pattern negated = Pattern.compile("(?m)^\\s*-\\s*['\"]?!");
        for (String trigger : new String[] {"push", "pull_request"}) {
            String paths = block(block(yaml, "\n  " + trigger + ":", "\n  [a-z_]+:|\n[a-z_]+:"),
                    "\n    paths:", "\n    [a-z_-]+:");
            if (negated.matcher(paths).find() || !entry.matcher(paths).find()) {
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
