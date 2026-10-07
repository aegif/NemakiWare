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
package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes.ContentAbsence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence an operator sees when a restore finished without bytes — R57.
 *
 * <h2>The defect</h2>
 *
 * <p>Moving a version's content to cold storage leaves the archive row with no {@code content}
 * attachment. Restoring it therefore creates the attachment row, fails to fetch a body, sees
 * {@code archiveHasBinary == false}, and <b>returns normally</b> — with the same
 * {@code RestoredBytes.NOTHING} it returns when the archive row could not be read at all. From
 * then on every checkOut, checkIn and copy of that document is refused with "if a restore from
 * the archive is in progress, retry shortly". The restore is not in progress. It finished, and
 * this product has no path that reads cold storage back, so the message asks the operator to
 * wait for something that will never happen.
 *
 * <p>Four things had to move together, and this measures the two that decide what a caller is
 * told: the restore's answer, and the sentence the refusal produces. Splitting them would have
 * changed the answer while the message stayed wrong.
 */
class TheRefusalSaysWhetherWaitingHelpsTest {

    /**
     * The sentence an operator actually sees.
     *
     * <p>This is the half that was wrong: the answer could have been corrected and the message
     * would still have told them to wait. Both directions are pinned — the cold case must NOT
     * say "retry shortly" as its instruction, and the unknown case must not promise that
     * waiting works either.
     */
    @Test
    @DisplayName("the refusal tells a cold-moved document that waiting will not help")
    void theRefusalSaysWaitingWillNotHelp() {
        String cold = AttachmentServiceDelegate.bodyMissing("bedroom", "att-1", ContentAbsence.MOVED_TO_COLD.name());
        assertTrue(cold.contains("WAITING WILL NOT CHANGE THIS"),
                "an operator whose document will never get its body back is told to retry "
                        + "shortly: " + cold);
        assertFalse(cold.contains("retry shortly"),
                "the cold case still tells the operator to retry, which describes a restore "
                        + "that has already finished: " + cold);

        String unknown = AttachmentServiceDelegate.bodyMissing("bedroom", "att-1", null);
        assertTrue(unknown.contains("does not record why"),
                "a row that says nothing is answered as though it had said something: "
                        + unknown);
        assertTrue(unknown.contains("does not resolve by waiting"),
                "the unknown case promises that waiting works, which is true for one of its two "
                        + "causes and false for the other: " + unknown);

        String noContent = AttachmentServiceDelegate.bodyMissing("bedroom", "att-1", ContentAbsence.ARCHIVE_HAD_NO_CONTENT.name());
        assertTrue(noContent.contains("nothing to wait for"), noContent);
    }
}
