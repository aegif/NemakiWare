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

import jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes;
import jp.aegif.nemaki.dao.ContentDaoService.RestoredBytes.ContentAbsence;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.model.Archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The abandoned journal row says WHICH absence — R57's answer reaches its only reader.
 *
 * <h2>What this holds shut</h2>
 *
 * <p>{@code RestoredBytes.wroteBytes()} is false for three different situations: the archived
 * version had no content, the content was MOVED to cold storage, and the archive row could not
 * be read at all. The only product reader of that answer wrote ONE sentence for all three —
 * "the archive carried no binary content; nothing was written back" — and
 * {@code journal.abandon} PERSISTS it. So a cold MOVE, where the archive did carry content and
 * the bytes are sitting in cold storage, left a falsehood in the content-write journal, and so
 * did a row nobody could read (subagent, eighth review, P2).
 *
 * <p>R57 gave the restore a reason to return. This is the half that spends it.
 */
class TheAbandonedRowSaysWhichAbsenceTest {

    /** How many times {@code recordRestored} closes the row for {@code absence}. */
    private static int abandonCallsFor(ContentAbsence absence) throws Exception {
        return recordedFor(absence).size();
    }

    /** The one reason {@code recordRestored} hands to the recorder for {@code absence}. */
    private static String reasonFor(ContentAbsence absence) throws Exception {
        List<String> recorded = recordedFor(absence);
        assertEquals(1, recorded.size(),
                "the row was not abandoned exactly once, so this lock is reading nothing: "
                        + recorded);
        return recorded.get(0);
    }

    /** Every reason handed to {@code abandon} for {@code absence} — possibly none. */
    private static List<String> recordedFor(ContentAbsence absence) throws Exception {
        List<String> recorded = new ArrayList<>();
        RecordContentStateRecorder recorder = mock(RecordContentStateRecorder.class);
        doAnswer(call -> {
            recorded.add(String.valueOf((Object) call.getArgument(1)));
            return null;
        }).when(recorder).abandon(any(), anyString());

        ArchiveServiceDelegate delegate = new ArchiveServiceDelegate(null, null, null,
                null, () -> recorder);
        Archive archive = new Archive();
        archive.setId("arc-1");
        archive.setOriginalId("att-1");

        Method recordRestored = ArchiveServiceDelegate.class.getDeclaredMethod("recordRestored",
                String.class, Archive.class, RecordContentStateRecorder.Pending.class,
                RestoredBytes.class);
        recordRestored.setAccessible(true);
        recordRestored.invoke(delegate, "bedroom", archive,
                mock(RecordContentStateRecorder.Pending.class),
                RestoredBytes.nothingBecause(absence));
        return recorded;
    }

    @Test
    @DisplayName("a cold MOVE is not recorded as 'the archive carried no binary content'")
    void aColdMoveIsNotRecordedAsHavingNoContent() throws Exception {
        String cold = reasonFor(ContentAbsence.MOVED_TO_COLD);

        assertFalse(cold.contains("carried no binary content"),
                "a cold MOVE was recorded in the journal as an archive with no content. The "
                        + "archive DID carry content — it is in cold storage — and this "
                        + "sentence is persisted: " + cold);
        assertTrue(cold.contains("cold storage"),
                "the row does not say where the bytes went, so an operator reading the journal "
                        + "cannot tell this from a version that never had content: " + cold);
    }

    /**
     * NONE means "bytes were written, OR nothing was determined" — so here it is the latter.
     *
     * <p>It used to fall into a {@code default} arm and have "the archived version carried no
     * content of its own" PERSISTED about it, which is a statement the restore never made
     * (subagent, tenth review, P3). Today's callers do not produce this combination; the public
     * three-argument {@code RestoredBytes} constructor makes it in one line.
     */
    @Test
    @DisplayName("an UNSTATED absence leaves the row open, like the undetermined one")
    void anUnstatedAbsenceLeavesTheRowOpen() throws Exception {
        assertEquals(0, abandonCallsFor(ContentAbsence.NONE),
                "a restore that wrote no bytes and stated no reason closed the journal row as "
                        + "abandoned, with a sentence about content nobody looked at");
    }

    /**
     * "We could not look" leaves the row OPEN — it does not close it with a better sentence.
     *
     * <p>{@code abandon} closes a row because the write VERIFIABLY did not happen.
     * {@code NOT_DETERMINED} means the archive row could not be read, which is not that.
     * Closing it took an unresolved gap off the list of unresolved gaps, and the first fix only
     * changed the sentence — the state transition was still "unknown to settled" (Codex, ninth
     * review, P1). The lock that asked for exactly one {@code abandon} here was pinning that.
     */
    @Test
    @DisplayName("'we could not look' leaves the row open, it does not close it")
    void anUndeterminedAbsenceLeavesTheRowOpen() throws Exception {
        assertEquals(0, abandonCallsFor(ContentAbsence.NOT_DETERMINED),
                "a restore that could not read the archive row at all closed the journal row "
                        + "as abandoned, so an unresolved gap stopped being listable");
    }

    @Test
    @DisplayName("a version that really had no content is recorded as that")
    void aVersionWithNoContentIsRecordedAsThat() throws Exception {
        String none = reasonFor(ContentAbsence.ARCHIVE_HAD_NO_CONTENT);

        assertTrue(none.contains("no content of its own"),
                "the one situation the old sentence described is no longer described: " + none);
    }
}
