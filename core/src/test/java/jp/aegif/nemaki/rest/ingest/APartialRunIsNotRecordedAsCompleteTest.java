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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    /** A service whose store answers the given rows to every selector. */
    @SuppressWarnings("unchecked")
    private static IngestJobService serviceReturning(Document... rows) {
        Cloudant cloudant = mock(Cloudant.class);
        FindResult found = mock(FindResult.class);
        when(found.getDocs()).thenReturn(List.of(rows));
        ServiceCall<FindResult> find = mock(ServiceCall.class);
        Response<FindResult> findResponse = mock(Response.class);
        when(findResponse.getResult()).thenReturn(found);
        when(find.execute()).thenReturn(findResponse);
        when(cloudant.postFind(any())).thenReturn(find);

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
    @DisplayName("a manual run does not tell the person who pressed the button 'success'")
    void aManualRunSaysPartialWhenItDidNotSeeEverything() throws IOException {
        // The two "Run now" doors keyed their word off hasErrors() alone, so the same shape of
        // run that the scheduled path records as PARTIAL answered a human "success" (Codex
        // review, P1). One wording now, and both doors have to go through it.
        assertEquals("success", new FetchResult(5, 5, 0, List.of()).runStatus());
        assertEquals("partial", new FetchResult(5, 5, 0, List.of(), List.of("cut short"))
                .runStatus(), "a run that saw part of the source told the caller it succeeded");
        assertEquals("partial", new FetchResult(5, 0, 0, List.of("boom")).runStatus());

        // The wiring, because a correct helper nobody calls changes nothing. Read from source:
        // both doors build a Map response, so there is no type to hang this on.
        for (String door : List.of("IngestSchedulerController.java", "FolderConnectorController.java")) {
            // Comments stripped. The grep below was satisfied by the COMMENT next to the code
            // it was meant to find, so deleting the line that puts incompleteReads in the
            // response left the lock green (Codex review, P2).
            String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                    Files.readString(
                            Path.of("src/main/java/jp/aegif/nemaki/rest/ingest/" + door),
                            StandardCharsets.UTF_8));
            assertTrue(source.contains(".runStatus()"),
                    door + " words its own outcome instead of going through FetchResult.runStatus");
            assertTrue(source.contains("incompleteReads"),
                    door + " answers without saying what the run did not see, so 'partial' is "
                            + "unactionable");
        }

        // And the screens that read those doors. "Unactionable" was measured on the server and
        // not on the only consumer, so the response carried the reason while both manual-run
        // screens still showed a green "done" (subagent review, P1).
        for (String screen : List.of(
                "components/DocumentList/DocumentList.tsx",
                "components/IntegrationSettings/SchedulerStatusTab.tsx")) {
            String source = Files.readString(Path.of("src/main/webapp/ui/src/" + screen),
                    StandardCharsets.UTF_8);
            assertTrue(source.contains("sawEverything") || source.contains("incompleteReads"),
                    screen + " reports a manual run without looking at whether it saw the whole "
                            + "source, so a run that stopped at its limit reads as finished");
        }
    }

    @Test
    @DisplayName("a job row carrying a field this node does not know still decodes")
    void anUnknownFieldDoesNotMakeARowUnreadable() {
        // During a rolling upgrade an old replica reads rows a new one wrote, and this batch
        // added a field (incompleteReads). A review predicted that one added field would make
        // every such row undecodable, degrade the listing to its "some rows could not be read"
        // envelope and empty the console.
        //
        // MEASURED, and the prediction does not hold HERE: the default mapper does refuse an
        // unknown property (probed directly — a bean without the annotation is rejected), but
        // both row classes carry @JsonIgnoreProperties(ignoreUnknown = true), so the tolerance
        // is the record's, not the mapper's. A lenient reader was written for this and reverted
        // when control FP3 showed it protected nothing. What the tolerance actually rests on is
        // the annotation, so that is what this locks.
        Document row = new Document();
        row.setId("ingest_job:1");
        row.setRev("1-abc");
        row.put("type", IngestJobRecord.DOC_TYPE);
        row.put("jobId", "job-1");
        row.put("profileId", "p-1");
        row.put("status", "COMPLETED");
        row.put("fetched", 3);
        row.put("aFieldFromANewerNode", "whatever it means");

        IngestJobService.JobPage page = assertDoesNotThrow(
                () -> serviceReturning(row).listJobsPage(10),
                "the listing threw rather than answering");

        assertEquals(0, page.unreadable(),
                "a row from a newer node was counted as unreadable: the whole listing degrades "
                        + "to the envelope shape on a rolling upgrade");
        assertEquals(1, page.entries().size(), "the row is missing from the listing");
        assertEquals("job-1", page.entries().get(0).getJobId());
    }

    @Test
    @DisplayName("a row whose types are wrong is still counted as unreadable")
    void acorruptRowIsStillRefused() {
        // The over-leniency side. Ignoring unknown fields must not turn into ignoring a row that
        // cannot be read at all — that is the silence R18 closed.
        Document row = new Document();
        row.setId("ingest_job:2");
        row.setRev("1-abc");
        row.put("type", IngestJobRecord.DOC_TYPE);
        row.put("jobId", "job-2");
        row.put("fetched", "not a number at all");

        IngestJobService.JobPage page = serviceReturning(row).listJobsPage(10);

        assertEquals(1, page.unreadable(),
                "a row that could not be decoded was passed over in silence");
        assertTrue(page.entries().isEmpty(), page.entries().toString());
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
