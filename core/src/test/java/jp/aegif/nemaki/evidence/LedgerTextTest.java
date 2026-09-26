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
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readings {@link LedgerText} gives the count and CI locks, on inputs that tell a right
 * reading from a wrong one. The documents and the workflow today contain none of these shapes,
 * so the locks that read them cannot show which rule is in force; these can.
 */
class LedgerTextTest {

    @Test
    @DisplayName("a sweep figure is read only in its one written form, with the round in the token")
    void aSweepFigureIsReadOnlyInItsForm() {
        Matcher figure = LedgerText.SWEEP_FIGURE.matcher(
                "6 回目の通し（1551 本）が完走し、7 回目の通し（1566 本）も完走した");
        assertTrue(figure.find());
        assertEquals("6", figure.group(1));
        assertEquals("1551", figure.group(2));
        assertTrue(figure.find());
        assertEquals("7", figure.group(1));
        assertEquals("1566", figure.group(2), "two records in one sentence each keep their own round");
        for (String prose : List.of("6 回目は 1551 本を流した", "1551 本で完走した（6 回目、",
                "6 回目の通しの後に足した分を含め 1551 本すべて", "6 回目\n以後の分を含め 1551 本")) {
            assertFalse(LedgerText.SWEEP_FIGURE.matcher(prose).find(),
                    "「" + prose + "」 is prose, not the form — its figure is read as the total");
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
        assertTrue(LedgerText.listedInBothTriggers(both.replace("- 'README.md'", "- \"README.md\""),
                "README.md"), "a double-quoted entry starts the workflow as a single-quoted one does");
        assertTrue(LedgerText.listedInBothTriggers(both.replace("- 'README.md'", "- README.md"),
                "README.md"), "a bare entry starts the workflow as a quoted one does");
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
        assertFalse(LedgerText.listedInBothTriggers(
                both.replace("      - 'README.md'\n\njobs:", "      - 'README.md'\n      - '!README.md'\n\njobs:"),
                "README.md"), "a negated pattern after the entry cancels it, and this helper does not "
                        + "work out which paths a negation cancels");
    }
}
