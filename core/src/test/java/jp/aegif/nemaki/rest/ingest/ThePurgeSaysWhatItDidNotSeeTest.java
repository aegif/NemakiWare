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
package jp.aegif.nemaki.rest.ingest;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dead-letter purge reads the first {@value IngestJobService#PURGE_SCAN_LIMIT} rows. It now
 * says so (R20).
 *
 * <h2>What one number hid</h2>
 *
 * <p>The endpoint answered {@code {"status":"success","deleted":N}}. A queue of forty rows and a
 * queue of forty thousand produce the same answer when the first thousand hold the same N old
 * entries — and the operator, who ran this precisely to clear the queue, reads "success". The
 * limit is not the defect; answering as though there were no limit is.
 *
 * <h2>Read, not guessed</h2>
 *
 * <p>"There may be more" is established by asking for LIMIT + 1 rows. Inferring it from
 * "we read exactly the limit" would be a guess: a database holding exactly the limit looks
 * identical to one holding ten times it, and the plan says not to assert truncation without
 * checking.
 */
class ThePurgeSaysWhatItDidNotSeeTest {

    private static final Instant CUTOFF = Instant.parse("2025-01-01T00:00:00Z");

    private static IngestJobService serviceOn(Cloudant cloudant) {
        IngestJobService jobs = new IngestJobService();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(org.mockito.ArgumentMatchers.anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);
        return jobs;
    }

    /**
     * A store that HONOURS the limit it is asked for.
     *
     * <p>The first version of this fixture returned the whole list whatever the request said,
     * so the control that reverts "ask for limit + 1" changed nothing and the lock stayed green
     * (measured). A store that ignores `limit` cannot measure a change to `limit`.
     */
    @SuppressWarnings("unchecked")
    private static void answering(Cloudant cloudant, List<Document> docs, boolean deleteSucceeds) {
        when(cloudant.postFind(any())).thenAnswer(request -> {
            com.ibm.cloud.cloudant.v1.model.PostFindOptions options = request.getArgument(0);
            long limit = options.limit() == null ? docs.size() : options.limit();
            List<Document> answered = docs.subList(0, (int) Math.min(limit, docs.size()));
            FindResult found = mock(FindResult.class);
            when(found.getDocs()).thenReturn(answered);
            ServiceCall<FindResult> call = mock(ServiceCall.class);
            Response<FindResult> resp = mock(Response.class);
            when(resp.getResult()).thenReturn(found);
            when(call.execute()).thenReturn(resp);
            return call;
        });

        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(deleteSucceeds);
        ServiceCall<DocumentResult> delete = mock(ServiceCall.class);
        Response<DocumentResult> deleteResp = mock(Response.class);
        when(deleteResp.getResult()).thenReturn(result);
        when(delete.execute()).thenReturn(deleteResp);
        when(cloudant.deleteDocument(any())).thenReturn(delete);
    }

    private static Document entry(String id, String failedAt) {
        Document doc = new Document();
        doc.setId("ingest_dlq:" + id);
        doc.setRev("3-abc");
        doc.put("type", IngestDeadLetterRecord.DOC_TYPE);
        doc.put("dlqId", id);
        if (failedAt != null) {
            doc.put("failedAt", failedAt);
        }
        return doc;
    }

    private static List<Document> oldEntries(int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(entry("old-" + i, "2020-01-01T00:00:00Z"));
        }
        return docs;
    }

    @Test
    @DisplayName("a row beyond the limit is SEEN, and the answer says so")
    void aRowBeyondTheLimitIsReported() {
        Cloudant cloudant = mock(Cloudant.class);
        // The store answers LIMIT + 1 because that is what was asked for. The extra row is the
        // evidence; it is not examined and not purged.
        answering(cloudant, oldEntries(IngestJobService.PURGE_SCAN_LIMIT + 1), true);

        IngestJobService.DlqPurgeResult purge = serviceOn(cloudant).purgeDlqOlderThan(CUTOFF);

        assertTrue(purge.moreRowsMayExist(),
                "the walk saw a row past its limit and did not say so: " + purge.asMap());
        assertTrue(purge.limitReached(), purge.asMap().toString());
        assertEquals(IngestJobService.PURGE_SCAN_LIMIT, purge.rowsExamined(),
                "the extra row was examined — it exists to answer the question, not to be "
                        + "purged: " + purge.asMap());
        assertEquals(IngestJobService.PURGE_SCAN_LIMIT, purge.rowsPurged(), purge.asMap().toString());
    }

    @Test
    @DisplayName("a queue that fits does not claim there may be more")
    void aQueueThatFitsSaysSo() {
        // The over-throw side. Saying "there may be more" about a queue the walk reached the end
        // of would send an operator looking for rows that are not there — and would make the
        // flag mean nothing, which is worse than not having it.
        Cloudant cloudant = mock(Cloudant.class);
        answering(cloudant, oldEntries(3), true);

        IngestJobService.DlqPurgeResult purge = serviceOn(cloudant).purgeDlqOlderThan(CUTOFF);

        assertFalse(purge.moreRowsMayExist(), purge.asMap().toString());
        assertFalse(purge.limitReached(), purge.asMap().toString());
        assertEquals(3, purge.rowsExamined(), purge.asMap().toString());
        assertEquals(3, purge.rowsPurged(), purge.asMap().toString());
    }

    @Test
    @DisplayName("exactly the limit, with nothing beyond it, is not 'there may be more'")
    void exactlyTheLimitIsNotTruncation() {
        // The distinction the plan asks for: "we read the limit" is not "there is more". Only
        // the row past the limit establishes that, and here there is none.
        Cloudant cloudant = mock(Cloudant.class);
        answering(cloudant, oldEntries(IngestJobService.PURGE_SCAN_LIMIT), true);

        IngestJobService.DlqPurgeResult purge = serviceOn(cloudant).purgeDlqOlderThan(CUTOFF);

        assertTrue(purge.limitReached(),
                "the walk read every row it was allowed to and did not say so: " + purge.asMap());
        assertFalse(purge.moreRowsMayExist(),
                "the walk read the limit and no more, and claimed there may be more anyway — "
                        + "that is the guess the plan rules out: " + purge.asMap());
    }

    @Test
    @DisplayName("a row whose failedAt cannot be read is counted, not passed over in silence")
    void anUnreadableRowIsCounted() {
        // The cutoff was never applied to it. "Nothing older was found" is a claim about rows
        // the walk compared, and this one it could not.
        Cloudant cloudant = mock(Cloudant.class);
        answering(cloudant, List.of(
                entry("no-date", null),
                entry("bad-date", "not a timestamp"),
                entry("old", "2020-01-01T00:00:00Z")), true);

        IngestJobService.DlqPurgeResult purge = serviceOn(cloudant).purgeDlqOlderThan(CUTOFF);

        assertEquals(2, purge.unreadableRows(),
                "rows whose failedAt could not be read were passed over in silence: "
                        + purge.asMap());
        assertEquals(3, purge.rowsExamined(), purge.asMap().toString());
        assertEquals(1, purge.rowsPurged(), purge.asMap().toString());
    }
}
