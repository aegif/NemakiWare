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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The job record of a run that did not see everything says so (R14, plan A-8).
 *
 * <h2>The sentence being removed</h2>
 *
 * <p>{@code completeJob} had three cases: errors with imports → PARTIAL, errors without → FAILED,
 * and everything else → COMPLETED. A poll that stopped at its limit with more waiting on the
 * other side fell in the third: nothing failed, so the run was recorded COMPLETED, and the only
 * place an operator could have learnt that the counters describe a PART of the source said the
 * opposite.
 *
 * <p>The reason it is not simply added to {@code errors} is in {@link FetchResult}: the scheduler
 * counts an errored run with no imports towards opening the connector's circuit breaker, so a
 * healthy workspace larger than one poll would have shut its own connector off. Over-refusal
 * instead of over-claiming is not an improvement.
 */
class APartialRunIsNotRecordedAsCompleteTest {

    @SuppressWarnings("unchecked")
    private static IngestJobService serviceThatAcceptsWrites() {
        Cloudant cloudant = mock(Cloudant.class);

        FindResult found = mock(FindResult.class);
        when(found.getDocs()).thenReturn(List.<Document>of());
        ServiceCall<FindResult> find = mock(ServiceCall.class);
        Response<FindResult> findResponse = mock(Response.class);
        when(findResponse.getResult()).thenReturn(found);
        when(find.execute()).thenReturn(findResponse);
        when(cloudant.postFind(any())).thenReturn(find);

        DocumentResult written = mock(DocumentResult.class);
        when(written.isOk()).thenReturn(true);
        when(written.getId()).thenReturn("ingest_job:1");
        when(written.getRev()).thenReturn("1-abc");
        ServiceCall<DocumentResult> post = mock(ServiceCall.class);
        Response<DocumentResult> postResponse = mock(Response.class);
        when(postResponse.getResult()).thenReturn(written);
        when(post.execute()).thenReturn(postResponse);
        when(cloudant.postDocument(any())).thenReturn(post);

        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(wrapper);

        IngestJobService jobs = new IngestJobService();
        jobs.setConnectorPool(pool);
        return jobs;
    }

    private static IngestJobRecord job() {
        IngestJobRecord job = new IngestJobRecord();
        job.setJobId("job-1");
        job.setProfileId("p-1");
        job.setConnectorId("c-1");
        job.setRepositoryId("bedroom");
        return job;
    }

    @Test
    @DisplayName("a run that stopped short of the source is PARTIAL, not COMPLETED")
    void aTruncatedRunIsPartial() {
        IngestJobRecord record = job();

        serviceThatAcceptsWrites().completeJob(record, new FetchResult(5, 5, 0, List.of(),
                List.of("Notion page listing: the caller's limit of 5 was reached and Notion "
                        + "says there are more pages")));

        assertEquals(IngestJobRecord.Status.PARTIAL, record.getStatus(),
                "a poll that saw part of the workspace was recorded as a completed one");
        assertEquals(0, record.getFailed(),
                "stopping at a limit is not a failure and must not be counted as one");
        assertTrue(record.getIncompleteReads() != null && !record.getIncompleteReads().isEmpty(),
                "the record does not say WHY it is partial, so PARTIAL is unactionable: "
                        + record.getIncompleteReads());
    }

    @Test
    @DisplayName("a run that saw the whole source is still COMPLETED")
    void aWholeRunIsStillCompleted() {
        // The over-refusal side. A completeJob that never says COMPLETED would satisfy the test
        // above and make the status field meaningless.
        IngestJobRecord record = job();

        serviceThatAcceptsWrites().completeJob(record, new FetchResult(5, 5, 0, List.of()));

        assertEquals(IngestJobRecord.Status.COMPLETED, record.getStatus(),
                "a run that saw everything was recorded as partial");
    }

    @Test
    @DisplayName("an errored run is still FAILED or PARTIAL on its errors, not on completeness")
    void errorsStillDecideFirst() {
        // The incompleteness arm is added AFTER the two error arms, and a reader could reasonably
        // put it first. Doing so would turn every failed run that also stopped at a limit into a
        // PARTIAL with failed=0 — losing the failure.
        IngestJobRecord failed = job();
        serviceThatAcceptsWrites().completeJob(failed,
                new FetchResult(5, 0, 0, List.of("Notion page p: 500"), List.of("cut short")));
        assertEquals(IngestJobRecord.Status.FAILED, failed.getStatus(),
                "a run with errors and no imports must still read as FAILED");
        assertEquals(1, failed.getFailed(), "the error count was lost");

        IngestJobRecord partial = job();
        serviceThatAcceptsWrites().completeJob(partial,
                new FetchResult(5, 3, 0, List.of("Notion page p: 500"), List.of("cut short")));
        assertEquals(IngestJobRecord.Status.PARTIAL, partial.getStatus(), "status");
        assertEquals(1, partial.getFailed(), "the error count was lost");
    }
}
