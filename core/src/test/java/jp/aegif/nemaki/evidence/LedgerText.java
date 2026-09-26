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

    /** The sweeps the canon records, round to the number of controls that round ran. */
    public static Map<Integer, Integer> sweptInRound(String canon) {
        Map<Integer, Integer> swept = new HashMap<>();
        Matcher sweeps = Pattern.compile("(\\d+) 回目 20\\d\\d-\\d\\d-\\d\\d[^（]*（(\\d+) 本")
                .matcher(canon);
        while (sweeps.find()) {
            swept.put(Integer.parseInt(sweeps.group(1)), Integer.parseInt(sweeps.group(2)));
        }
        return swept;
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
     * The round a figure at {@code offset} in {@code sentence} belongs to: the nearest 「N 回目」
     * before it, else the nearest after it, else {@code null}. 「N 回目以後 / 以降」 names a span
     * after a round, not a round — 「6 回目以後の分を含め、現行は 1551 本すべて」 is not a record
     * of the sixth sweep (subagent review). The FIRST round in the sentence was used before, so
     * 「6 回目と比べ、7 回目は 1558 本を流した」 was checked against the sixth (Codex, P3).
     */
    public static Integer roundNamedNear(String sentence, int offset) {
        Matcher round = Pattern.compile("(\\d+) 回目(?!以後|以降)").matcher(sentence);
        Integer before = null;
        Integer after = null;
        while (round.find()) {
            if (round.start() < offset) {
                before = Integer.parseInt(round.group(1));
            } else if (after == null) {
                after = Integer.parseInt(round.group(1));
            }
        }
        return before != null ? before : after;
    }

    /**
     * Whether {@code path} is a LIST ENTRY of both triggers' paths. Counting the quoted path
     * anywhere in the file was satisfied by a comment, or by one trigger listing it twice while
     * the other did not list it at all (Codex, P2).
     */
    public static boolean listedInBothTriggers(String yaml, String path) {
        int push = yaml.indexOf("\n  push:");
        int pullRequest = yaml.indexOf("\n  pull_request:");
        if (push < 0 || pullRequest < push) {
            return false;
        }
        Matcher nextKey = Pattern.compile("\n[a-z_]+:").matcher(yaml);
        int end = nextKey.find(pullRequest + 1) ? nextKey.start() : yaml.length();
        Pattern entry = Pattern.compile("(?m)^\\s*-\\s*'" + Pattern.quote(path) + "'\\s*$");
        return entry.matcher(yaml.substring(push, pullRequest)).find()
                && entry.matcher(yaml.substring(pullRequest, end)).find();
    }
}
