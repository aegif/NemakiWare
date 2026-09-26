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

import java.util.HashMap;
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
 */
public final class LedgerText {

    private LedgerText() {
    }

    private static final Pattern SWEEP_RECORD =
            Pattern.compile("(\\d+) 回目 20\\d\\d-\\d\\d-\\d\\d[^（]*（(\\d+) 本");

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
     * Rounds the canon records more than once with different numbers. Each map above kept the
     * LAST record, so a second, wrong record written after the right one replaced it without a
     * word — and one written before it was silently overruled (Codex, P3).
     */
    public static java.util.List<String> conflictingSweeps(String canon) {
        Map<Integer, Integer> first = new HashMap<>();
        java.util.List<String> conflicts = new java.util.ArrayList<>();
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
     * The sentence around a position: back to the last 。, blank line, list item or table row
     * before it, and on to the next. Markdown wraps a sentence across lines, so a line-bounded
     * sentence lost the round named on the line above.
     */
    public static String sentenceAround(String text, int at) {
        int start = sentenceStart(text, at);
        int stop = text.length();
        for (String closer : new String[] {"。", "\n\n", "\n- ", "\n|"}) {
            int found = text.indexOf(closer, at);
            if (found >= 0) {
                stop = Math.min(stop, found);
            }
        }
        return text.substring(start, stop);
    }

    /** Where the sentence around a position starts (see {@link #sentenceAround}). */
    public static int sentenceStart(String text, int at) {
        int start = 0;
        for (String opener : new String[] {"。", "\n\n", "\n- ", "\n|"}) {
            int found = text.lastIndexOf(opener, at - 1);
            if (found >= 0) {
                start = Math.max(start, found + opener.length());
            }
        }
        return start;
    }

    /**
     * What may follow 「N 回目」 when it names a time AROUND a round rather than the round:
     * 「6 回目以後の分を含め、現行は 1551 本すべて」 is not a record of the sixth sweep (subagent
     * review), and neither is 「6 回目の後に足した分」 or 「5 回目まで」.
     */
    public static final java.util.List<String> NOT_A_ROUND =
            java.util.List.of("以後", "以降", "の後", "の前", "まで", "より");

    /**
     * The round a figure (from {@code start} to {@code end} in {@code sentence}) belongs to: the
     * 「N 回目」 NEAREST to it, before or after, or {@code null} if the sentence names none. The
     * documents write both 「6 回目（…、1551 本すべて」 and 「1551 本で完走した（6 回目、」.
     * Taking the first round in the sentence checked 「6 回目と比べ、7 回目は 1558 本」 against the
     * sixth (Codex, P3); preferring any round BEFORE the figure did the same to
     * 「1551 本を流した（6 回目）、1566 本を流した（7 回目）」 (Codex, P2).
     */
    public static Integer roundNamedNear(String sentence, int start, int end) {
        StringBuilder notARound = new StringBuilder();
        for (String span : NOT_A_ROUND) {
            notARound.append(notARound.length() == 0 ? "" : "|").append(Pattern.quote(span));
        }
        Matcher round = Pattern.compile("(\\d+) 回目(?!" + notARound + ")").matcher(sentence);
        Integer nearest = null;
        int distance = Integer.MAX_VALUE;
        while (round.find()) {
            int away = round.end() <= start ? start - round.end()
                    : round.start() >= end ? round.start() - end
                    : 0;
            // On a tie the round AFTER the figure wins: a parenthesis right after a figure
            // annotates that figure — 「（6 回目）、1566 本を流した（7 回目）」.
            if (away < distance || (away == distance && round.start() >= end)) {
                distance = away;
                nearest = Integer.parseInt(round.group(1));
            }
        }
        return nearest;
    }

    /**
     * Whether {@code path} is an entry of the {@code paths:} list of BOTH the push and the
     * pull_request trigger. Counting the quoted path anywhere in the file was satisfied by a
     * comment, or by one trigger listing it twice while the other did not list it at all (Codex,
     * P2); taking any list line inside a trigger was satisfied by {@code paths-ignore:} or a
     * block-style {@code branches:} (Codex, P2, and subagent review).
     */
    public static boolean listedInBothTriggers(String yaml, String path) {
        Pattern entry = Pattern.compile("(?m)^\\s*-\\s*'" + Pattern.quote(path) + "'\\s*$");
        for (String trigger : new String[] {"push", "pull_request"}) {
            String paths = block(block(yaml, "\n  " + trigger + ":", "\n  [a-z_]+:|\n[a-z_]+:"),
                    "\n    paths:", "\n    [a-z_-]+:");
            if (!entry.matcher(paths).find()) {
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
