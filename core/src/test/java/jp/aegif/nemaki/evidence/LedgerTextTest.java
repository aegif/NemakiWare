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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readings {@link LedgerText} gives the count locks, on sentences that tell a right reading
 * from a wrong one. The documents today do not contain these shapes, so the locks that read the
 * documents cannot show which rule is in force; these can (subagent review, P3: "KB4〜KE4 fire
 * the same under nearest-before, nearest-after and last-in-sentence").
 */
class LedgerTextTest {

    private static Integer roundOf(String sentence, String figure) {
        int start = sentence.indexOf(figure);
        return LedgerText.roundNamedNear(sentence, start, start + figure.length());
    }

    @Test
    @DisplayName("a figure belongs to the round named nearest it, on either side")
    void aFigureBelongsToTheRoundNearestIt() {
        String two = "1551 本を流した（6 回目）、1566 本を流した（7 回目）";
        assertEquals(6, roundOf(two, "1551 本を流し"));
        assertEquals(7, roundOf(two, "1566 本を流し"),
                "a round named BEFORE the figure is not the figure's round when another is nearer "
                        + "after it (Codex, P2)");
        assertEquals(7, roundOf("6 回目の記録を踏まえ、7 回目は 1551 本を流した", "1551 本を流し"),
                "the first round in the sentence is not every figure's round (Codex, P3)");
        assertEquals(6, roundOf("通し negative-control は 1551 本で完走した（6 回目、2026-09-25〜26",
                "1551 本で完走"));
    }

    @Test
    @DisplayName("a span around a round names no round")
    void aSpanAroundARoundNamesNoRound() {
        // The spans the documents write, listed HERE rather than read from the helper: a span
        // dropped from the helper's list must fail this, not shrink what it walks.
        for (String span : List.of("以後", "以降", "の後", "の前", "まで", "より")) {
            assertNull(roundOf("6 回目" + span + "の分を含め、現行は 1551 本すべて", "1551 本すべて"),
                    "「6 回目" + span + "」 names a time around the sixth sweep, not the sixth sweep");
        }
    }

    @Test
    @DisplayName("a round recorded twice with different numbers is a conflict")
    void aRoundRecordedTwiceIsAConflict() {
        String canon = "6 回目 2026-09-25〜26（1500 本、下書き）\n6 回目 2026-09-25〜26（1551 本、…）";
        assertEquals(List.of("round 6: 1500 and 1551"), LedgerText.conflictingSweeps(canon));
        assertEquals(List.of(), LedgerText.conflictingSweeps(
                "6 回目 2026-09-25〜26（1551 本、…）\n6 回目 2026-09-25〜26（1551 本、再掲）"));
    }

    @Test
    @DisplayName("a path counts only as an entry of both triggers' paths lists")
    void aPathCountsOnlyAsAPathsEntry() {
        String both = "on:\n  push:\n    branches: [ master ]\n    paths:\n      - 'README.md'\n"
                + "  pull_request:\n    branches: [ master ]\n    paths:\n      - 'README.md'\n\njobs:\n";
        assertTrue(LedgerText.listedInBothTriggers(both, "README.md"));
        assertFalse(LedgerText.listedInBothTriggers(
                both.replace("  pull_request:\n    branches: [ master ]\n    paths:\n      - 'README.md'\n",
                        "  pull_request:\n    branches: [ master ]\n    paths:\n      - 'x'\n"
                                + "    paths-ignore:\n      - 'README.md'\n"), "README.md"),
                "an entry of paths-ignore does not start the workflow");
        assertFalse(LedgerText.listedInBothTriggers(
                both.replace("  pull_request:\n    branches: [ master ]\n    paths:\n      - 'README.md'\n",
                        "  pull_request:\n    branches:\n      - 'README.md'\n    paths:\n      - 'x'\n"), "README.md"),
                "an entry of a block-style branches list does not start the workflow");
        assertFalse(LedgerText.listedInBothTriggers(
                both.replace("      - 'README.md'\n\njobs:", "      - 'x'\n"
                        + "  pull_request_target:\n    paths:\n      - 'README.md'\n\njobs:"), "README.md"),
                "a later trigger's paths are not pull_request's");
        assertFalse(LedgerText.listedInBothTriggers(
                both.replace("      - 'README.md'\n\njobs:", "      # 'README.md' is started by push\n\njobs:"),
                "README.md"), "a comment is not an entry");
    }
}
