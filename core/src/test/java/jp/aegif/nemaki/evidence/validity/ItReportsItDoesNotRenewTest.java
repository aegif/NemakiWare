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
package jp.aegif.nemaki.evidence.validity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The long-term validity service assesses; it does not renew (plan §11).
 *
 * <p>That is a deliberate boundary, not an omission waiting to be filled in by whoever next
 * touches the file. Hash-tree renewal reads every archived object, so WHEN to run it is the
 * operator's cost to choose — and a product that renewed on its own would be choosing it for
 * them. This lock is here so the boundary cannot be crossed by accident.
 */
class ItReportsItDoesNotRenewTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/jp/aegif/nemaki/evidence/validity/LongTermValidityService.java");

    @Test
    @DisplayName("the assessment service mints no timestamp and writes no evidence record")
    void theServiceDoesNotRenew() throws java.io.IOException {
        assertTrue(Files.exists(SERVICE), "the service is not at " + SERVICE);
        // Comments stripped: the file EXPLAINS that it does not renew, in prose that names the
        // very calls being looked for. A grep a comment can satisfy has been the defect in this
        // batch six times.
        String code = Files.readString(SERVICE, StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");

        for (String minting : new String[] {
                "withTimestampRenewal", "withHashTreeRenewal", "ErsRecord.first" }) {
            assertTrue(!code.contains(minting),
                    "the assessment service now calls " + minting + ", so it renews rather than "
                            + "reporting. Hash-tree renewal reads every archived object: WHEN "
                            + "to run it is the operator's cost to choose, and a product that "
                            + "renews on its own is choosing it for them");
        }
    }

    @Test
    @DisplayName("the assessment says which renewal it does NOT assess")
    void theAssessmentNamesWhatItDoesNotCover() throws java.io.IOException {
        // Comments stripped, as the sibling method does. Without it a javadoc line saying the
        // words satisfies the check while the report says nothing — a grep a comment can
        // satisfy has been the defect in this batch seven times (subagent review, P3).
        String text = Files.readString(SERVICE, StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(text.contains("is NOT assessed"),
                "the service no longer says that signature-driven timestamp renewal is not "
                        + "assessed. No rung records the token's signature algorithm, so that "
                        + "renewal cannot be seen from here — and a report that stopped saying "
                        + "so would read as covering it");
    }

    @Test
    @DisplayName("the runbook records that explicit renewal is not implemented")
    void theRunbookSaysRenewalIsNotImplemented() throws java.io.IOException {
        Path runbook = Path.of("../docs/operations/evidence-verifier-release.md");
        assertTrue(Files.exists(runbook), "the runbook is not at " + runbook);
        String text = Files.readString(runbook, StandardCharsets.UTF_8);

        // Scoped to the renewal section: the words appear elsewhere in the document, and a
        // whole-file grep would stay green with the section deleted.
        int start = text.indexOf("## ERS の更新");
        assertTrue(start >= 0, "the runbook no longer has a renewal section, so an operator "
                + "reading it would not learn that renewal is theirs to run");
        // A level-2 heading at the START of a line. indexOf("## ") also matches inside
        // "### ", so a subsection added later would truncate the very section this
        // scopes to and the assertions below would read a fragment (subagent, P3).
        java.util.regex.Matcher next = java.util.regex.Pattern.compile("(?m)^## ")
                .matcher(text);
        int end = next.find(start + 3) ? next.start() : -1;
        String section = end > start ? text.substring(start, end) : text.substring(start);

        assertTrue(section.contains("更新しない") && section.contains("自動更新は入れない"),
                "the renewal section must say plainly that this version does not renew and "
                        + "that automatic renewal is not coming without a dry-run/explicit "
                        + "split:\n" + section);
    }
}
