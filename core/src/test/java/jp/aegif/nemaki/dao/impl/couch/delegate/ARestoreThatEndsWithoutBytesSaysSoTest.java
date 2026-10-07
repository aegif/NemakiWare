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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes.ContentAbsence;
import jp.aegif.nemaki.model.Archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore that finished without bytes says WHY, and the refusal repeats it — R57.
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
class ARestoreThatEndsWithoutBytesSaysSoTest {

    /**
     * A cold MOVE stated by {@code coldMoveMode} ALONE.
     *
     * <p>Each way of saying it gets its own fixture. One archive carrying all of them is read
     * by whichever arm happens to be first, so removing that arm leaves the lock green —
     * measured: the control did not fire.
     */
    private static Archive movedByMode() {
        Archive archive = new Archive();
        archive.setId("arc-1");
        archive.setOriginalId("att-1");
        archive.setColdMoveMode("MOVE");
        return archive;
    }

    /** A cold MOVE stated by the archive STATE and a content reference, with no mode. */
    private static Archive movedByStateAndRef() {
        Archive archive = new Archive();
        archive.setId("arc-1");
        archive.setOriginalId("att-1");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        Map<String, String> ref = new HashMap<>();
        ref.put("ref", "s3://cold/att-1");
        archive.setContentRef(ref);
        return archive;
    }

    @Test
    @DisplayName("a cold MOVE is told apart from a version that never had content")
    void aColdMoveIsToldApartFromNoContent() {
        assertTrue(ArchiveDaoDelegate.movedToCold(movedByMode(), null),
                "a version whose content was MOVED to cold storage was read as one that never "
                        + "had any, so the refusal below cannot tell the operator the truth");
        assertTrue(ArchiveDaoDelegate.movedToCold(movedByStateAndRef(), null),
                "an archive that states the move only by its STATE and a content reference was "
                        + "read as a version that never had content");

        Archive neverHadContent = new Archive();
        neverHadContent.setId("arc-2");
        neverHadContent.setOriginalId("att-2");
        assertFalse(ArchiveDaoDelegate.movedToCold(neverHadContent, null),
                "a version that never had content was read as a cold MOVE, which would tell an "
                        + "operator to go and fetch bytes that do not exist");

        // A COPY leaves the binary in the archive, so it never reaches this path — and if it
        // did, it would not be the permanent case.
        Archive copied = movedByMode();
        copied.setColdMoveMode("COPY");
        assertFalse(ArchiveDaoDelegate.movedToCold(copied, null),
                "a cold COPY was reported as a MOVE");
    }

    @Test
    @DisplayName("nothing-written answers carry WHY, and NOTHING no longer means three things")
    void theAnswerSaysWhy() {
        assertEquals(ContentAbsence.MOVED_TO_COLD,
                jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes
                        .nothingBecause(ContentAbsence.MOVED_TO_COLD).absence(),
                "the restore's answer does not carry the reason, so the caller has nothing to "
                        + "record and the refusal has nothing to read");
        assertEquals(ContentAbsence.NOT_DETERMINED,
                jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes.NOTHING.absence(),
                "NOTHING claims a reason. It is returned when the archive row could not be read "
                        + "at all, and saying anything about the content there would be a "
                        + "finding nobody made");
        assertEquals(ContentAbsence.NONE,
                new jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes("att-1", null, 4L)
                        .absence(),
                "a restore that DID write bytes reports an absence");
    }

}
