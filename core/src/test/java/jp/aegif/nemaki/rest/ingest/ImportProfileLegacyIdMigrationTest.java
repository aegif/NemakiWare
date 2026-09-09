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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.AllDocsResult;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.DocsResultRow;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * The import-profile half of §62: the same three doors the connectors were given, on the
 * same database, against the same rebuilding index, through the same startup patch.
 *
 * <h2>Why a second copy of the connector locks exists</h2>
 *
 * <p>{@code Patch_DefaultCloudDriveConnectorProfile} creates a connector and then, per
 * repository, a {@code cloud-import-<repo>} profile — and the profile service still had the
 * pre-closure shape the day after the connectors were cured: a selector-based
 * {@code exists()}, a generated id on miss, no id-addressed check, no migration. A review
 * found it by asking whether the closed patch could still write a twin. It could.
 *
 * <p>The walk is shared ({@code NemakiConfAllDocs}); the per-row policy is a twin in each
 * service and locked on each side, because "fixed the connector, forgot the profile" is the
 * one-arm shape this batch keeps producing, and two lock files are what notice it.
 */
class ImportProfileLegacyIdMigrationTest {

    private Cloudant cloudant;
    private CloudantClientWrapper wrapper;
    private CloudantClientPool pool;
    private ImportProfileDefinitionServiceImpl service;
    /** The database as every walk sees it. null = the listing does not answer. */
    private AllDocsResult currentPage;
    /** Ids this test's own delete removed: a later read must not be served them. */
    private final java.util.Set<String> deletedIds = new java.util.HashSet<>();
    /** How many more walks the sticky page answers; -1 means "always". */
    private int listingAnswersLeft = -1;

    @SuppressWarnings("unchecked")
    private void wire() {
        pool = mock(CloudantClientPool.class);
        wrapper = mock(CloudantClientWrapper.class);
        cloudant = mock(Cloudant.class);
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        when(wrapper.getClient()).thenReturn(cloudant);
        deletedIds.clear();
        listingAnswersLeft = -1;
        service = new ImportProfileDefinitionServiceImpl();
        service.setConnectorPool(pool);

        // Nothing exists under any deterministic id unless a test says so. get() now falls
        // back to an id-addressed read when the selector misses, and an UNSTUBBED
        // getDocument returns null → NPE inside the service — a fixture failure the runner
        // scores as "fired for the wrong reason".
        when(cloudant.getDocument(any(GetDocumentOptions.class))).thenAnswer(inv -> {
            ServiceCall<Document> call = mock(ServiceCall.class);
            when(call.execute()).thenThrow(
                    mock(com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
            return call;
        });

        // The stub honours startKey/endKey. It used to return the whole page whatever the
        // range was — a fixture that could not tell a ranged walk from a full one. The
        // ranged walk has since been withdrawn from production, but the continuation still
        // uses startKey, so a stub that ignores it models the wrong database.
        when(cloudant.postAllDocs(any(PostAllDocsOptions.class))).thenAnswer(call -> {
            PostAllDocsOptions options = call.getArgument(0);
            ServiceCall<AllDocsResult> ranged = mock(ServiceCall.class);
            when(ranged.execute()).thenAnswer(exec -> {
                // within(...) reads and stubs mocks. Called INSIDE thenReturn(...) it runs
                // while the stubbing of response.getResult() is still open, and Mockito
                // aborts the whole class with UnfinishedStubbingException — every test that
                // walked the database was failing on that instead of on its own claim.
                AllDocsResult served = within(options);
                Response<AllDocsResult> response = mock(Response.class);
                when(response.getResult()).thenReturn(served);
                return response;
            });
            return ranged;
        });
    }

    /**
     * The page a walk with these options sees. Sticky, not a queue: a create or update walks
     * the database TWICE (the uniqueness listing, then the count scan) and both must see the
     * same rows — the connector fixture's queue would starve the second walk into a false
     * "did not answer". Rows outside {@code [startKey, endKey]} are not served, the way
     * CouchDB does not serve them.
     */
    private AllDocsResult within(PostAllDocsOptions options) {
        if (listingAnswersLeft == 0) {
            return null;
        }
        if (listingAnswersLeft > 0) {
            listingAnswersLeft--;
        }
        if (currentPage == null || currentPage.getRows() == null) {
            return currentPage;
        }
        // Rows this call already DELETED are not served again. The page is sticky so a create
        // or update sees the same database twice; a delete that then counts what is left was
        // being served the row it had just removed, so a lock on the survivor count was red
        // on a healthy tree. A database does not do that.
        String from = options.startKey();
        String to = options.endKey();
        if (from == null && to == null && deletedIds.isEmpty()) {
            return currentPage;
        }
        List<DocsResultRow> inRange = new ArrayList<>();
        for (DocsResultRow r : currentPage.getRows()) {
            String id = r.getId();
            if (id == null) continue;
            if (deletedIds.contains(id)) continue;
            if (from != null && id.compareTo(from) < 0) continue;
            if (to != null && (Boolean.FALSE.equals(options.inclusiveEnd())
                    ? id.compareTo(to) >= 0 : id.compareTo(to) > 0)) continue;
            inRange.add(r);
        }
        AllDocsResult page = mock(AllDocsResult.class);
        when(page.getRows()).thenReturn(inRange);
        return page;
    }

    /** The rows every walk from now on sees (one page — the profile tests never page). */
    private void listingAnswers(List<DocsResultRow> rows) {
        AllDocsResult result = mock(AllDocsResult.class);
        when(result.getRows()).thenReturn(rows);
        currentPage = result;
    }

    /** The page answers exactly once; every later walk finds the listing unanswered. */
    private void listingAnswersOnce(List<DocsResultRow> rows) {
        listingAnswers(rows);
        listingAnswersLeft = 1;
    }

    private void listingDoesNotAnswer() {
        currentPage = null;
    }

    /** The selector (postFind) shows exactly these raw rows — a PARTIAL view of the DB. */
    private void selectorShows(DocsResultRow... visibleRows) {
        // findCallFor BEFORE the stubbing: it stubs mocks of its own, and doing that inside
        // thenReturn(...) leaves this stubbing open — Mockito then aborts the test with
        // UnfinishedStubbingException instead of letting it fail on its own claim.
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> call = findCallFor(visibleRows);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(call);
    }

    /**
     * The selector answers NOTHING first and shows {@code laterRows} from the second call on
     * — two concurrent requests: the existence check misses, the write's own look-up finds.
     */
    private void selectorAnswersNothingThenShows(DocsResultRow... laterRows) {
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> nothing = findCallFor();
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> later = findCallFor(laterRows);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(nothing).thenReturn(later);
    }

    @SuppressWarnings("unchecked")
    private ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> findCallFor(
            DocsResultRow... visibleRows) {
        List<Document> docs = new ArrayList<>();
        for (DocsResultRow r : visibleRows) {
            // Read r BEFORE the stubbing starts. r is a mock too, and a call on it between
            // when(...) and thenReturn(...) leaves Mockito's stubbing unfinished: the class
            // then dies with UnfinishedStubbingException instead of failing on its own claim.
            // Every test here that shows the selector anything was failing on this.
            String rowId = r.getId();
            String rowRev = r.getDoc().getRev();
            java.util.Map<String, Object> rowProps = r.getDoc().getProperties();
            Document d = mock(Document.class);
            when(d.getId()).thenReturn(rowId);
            when(d.getRev()).thenReturn(rowRev);
            when(d.getProperties()).thenReturn(rowProps);
            docs.add(d);
        }
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> findCall =
                mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.FindResult> findResponse =
                mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.FindResult found =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(found.getDocs()).thenReturn(docs);
        when(findResponse.getResult()).thenReturn(found);
        when(findCall.execute()).thenReturn(findResponse);
        return findCall;
    }

    private static DocsResultRow row(String id, Map<String, Object> props, String rev) {
        DocsResultRow row = mock(DocsResultRow.class);
        when(row.getId()).thenReturn(id);
        if (props != null) {
            Document doc = mock(Document.class);
            when(doc.getProperties()).thenReturn(props);
            when(doc.getRev()).thenReturn(rev);
            // The BODY carries the id too, as a real _all_docs row does. The index-free
            // delete addresses rows by doc.getId(); with it unstubbed the Cloudant builder
            // refused an empty docId and every delete test failed on that instead of on its
            // own claim — five locks red on a healthy tree, two green for the wrong reason.
            // A review traced it.
            when(doc.getId()).thenReturn(id);
            when(row.getDoc()).thenReturn(doc);
        }
        return row;
    }

    private static Map<String, Object> profileProps(String profileId, String displayName) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", ImportProfileDefinition.DOC_TYPE);
        props.put("profileId", profileId);
        props.put("repositoryId", "bedroom");
        props.put("displayName", displayName);
        return props;
    }

    /** The deterministic-id read answers {@code doc} for {@code profileId}, else 404. */
    @SuppressWarnings("unchecked")
    private void deterministicReadAnswers(String profileId, Document doc) {
        String wantedId = ImportProfileDefinition.DOC_TYPE + ":" + profileId;
        when(cloudant.getDocument(any(GetDocumentOptions.class))).thenAnswer(inv -> {
            GetDocumentOptions options = inv.getArgument(0);
            ServiceCall<Document> call = mock(ServiceCall.class);
            if (wantedId.equals(options.docId()) && doc != null) {
                Response<Document> response = mock(Response.class);
                when(response.getResult()).thenReturn(doc);
                when(call.execute()).thenReturn(response);
            } else {
                when(call.execute()).thenThrow(
                        mock(com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
            }
            return call;
        });
    }

    /**
     * The id-addressed read MISSES first and answers {@code doc} from the second call on.
     * get() now falls back to that read, so a create's existence check would otherwise find
     * the row and refuse before ever reaching the write's own gate — the lock would measure
     * the wrong door. Two calls are also what the race being closed looks like: the row
     * appears between the existence check and the write.
     */
    @SuppressWarnings("unchecked")
    private void deterministicReadMissesThenAnswers(String profileId, Document doc) {
        String wantedId = ImportProfileDefinition.DOC_TYPE + ":" + profileId;
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(cloudant.getDocument(any(GetDocumentOptions.class))).thenAnswer(inv -> {
            GetDocumentOptions options = inv.getArgument(0);
            ServiceCall<Document> call = mock(ServiceCall.class);
            if (wantedId.equals(options.docId()) && calls.incrementAndGet() > 1) {
                Response<Document> response = mock(Response.class);
                when(response.getResult()).thenReturn(doc);
                when(call.execute()).thenReturn(response);
            } else {
                when(call.execute()).thenThrow(
                        mock(com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
            }
            return call;
        });
    }

    @SuppressWarnings("unchecked")
    private void writesSucceed() {
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        Response<DocumentResult> postResponse = mock(Response.class);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(postResponse.getResult()).thenReturn(ok);
        when(postCall.execute()).thenReturn(postResponse);
        when(cloudant.postDocument(any(PostDocumentOptions.class))).thenReturn(postCall);

        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenAnswer(inv -> {
            DeleteDocumentOptions deleteOptions = inv.getArgument(0);
            deletedIds.add(deleteOptions.docId());
            ServiceCall<DocumentResult> deleteCall = mock(ServiceCall.class);
            Response<DocumentResult> deleteResponse = mock(Response.class);
            when(deleteResponse.getResult()).thenReturn(ok);
            when(deleteCall.execute()).thenReturn(deleteResponse);
            return deleteCall;
        });
    }

    @SuppressWarnings("unchecked")
    private void selectorAnswersNothing() {
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> findCall =
                mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.FindResult> findResponse =
                mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.FindResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(empty.getDocs()).thenReturn(List.of());
        when(findResponse.getResult()).thenReturn(empty);
        when(findCall.execute()).thenReturn(findResponse);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(findCall);
    }

    /** A profile that passes validateRequiredFields with the scheduler OFF. */
    private static ImportProfileDefinition validProfile(String profileId) {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setProfileId(profileId);
        def.setDisplayName("Cloud Import (bedroom)");
        def.setRepositoryId("bedroom");
        def.setTargetFolderId("root-folder-1");
        def.setDefaultObjectTypeId("cmis:document");
        def.setEnabled(true);
        def.setSchedulerEnabled(false);
        return def;
    }

    // ────────────────────────────────────────────────────────────────────
    // The migration
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a legacy row is rewritten under its deterministic id, then retired")
    void aLegacyRowIsRewrittenUnderItsDeterministicId() {
        wire();
        listingAnswers(List.of(
                row("4383c1a96093a7526774f8d2db0a13b5",
                        profileProps("cloud-import-bedroom", "Cloud Import (bedroom)"), "3-r")));
        deterministicReadAnswers("cloud-import-bedroom", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("import_profile_definition:cloud-import-bedroom",
                written.getValue().document().getId(),
                "the copy was not written under the deterministic id, so the duplicate "
                        + "check still cannot see this profile");
        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("4383c1a96093a7526774f8d2db0a13b5", deleted.getValue().docId());
        assertEquals("3-r", deleted.getValue().rev(),
                "the retirement is not conditional on the revision the row was READ at");
        assertEquals(1, result.migrated);
        assertTrue(result.clean(), "a clean migration reported problems: " + result);
    }

    @Test
    @DisplayName("an identical leftover twin is retired without a new write")
    void anIdenticalLeftoverTwinIsRetired() {
        wire();
        Map<String, Object> props = profileProps("cloud-import-canopy", "Cloud Import (canopy)");
        listingAnswers(List.of(row("7c1d", props, "5-r")));
        Document deterministicTwin = mock(Document.class);
        when(deterministicTwin.getProperties()).thenReturn(new HashMap<>(props));
        deterministicReadAnswers("cloud-import-canopy", deterministicTwin);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("7c1d", deleted.getValue().docId());
        assertEquals(1, result.sweptDuplicates);
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("twins that DISAGREE are untouched and reported — neither row is chosen")
    void aDivergentTwinIsUntouchedAndReported() {
        wire();
        listingAnswers(List.of(row("2a9e", profileProps("cloud-import-bedroom", "A"), "2-r")));
        Document divergent = mock(Document.class);
        when(divergent.getProperties()).thenReturn(profileProps("cloud-import-bedroom", "B"));
        deterministicReadAnswers("cloud-import-bedroom", divergent);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.divergent.size(), "the disagreement was not reported: " + result);
        assertTrue(result.divergent.get(0).contains("cloud-import-bedroom"));
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("a retirement that conflicts is reported, not swallowed — the edit wins")
    void aConflictedRetirementIsReportedNotSwallowed() {
        wire();
        listingAnswers(List.of(row("4b8f", profileProps("p-conflict", "P"), "1-r")));
        deterministicReadAnswers("p-conflict", null);
        writesSucceed();
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class)))
                .thenThrow(new RuntimeException("409 document update conflict"));

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertEquals(0, result.migrated,
                "a migration whose retirement failed still counted itself migrated");
        assertEquals(1, result.failures.size(), "the conflict was swallowed: " + result);
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("a listing that does not answer refuses — it is not 'nothing to migrate'")
    void anUnansweredListingRefuses() {
        wire();
        listingDoesNotAnswer();

        assertThrows(IllegalStateException.class, () -> service.migrateLegacyGeneratedIds());
    }

    @Test
    @DisplayName("a row that cannot be classified is a loud failure, not a silent skip")
    void anUnclassifiableRowIsALoudFailure() {
        wire();
        listingAnswers(List.of(row("odd-row", null, null)));

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        assertEquals(1, result.failures.size());
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("deterministic rows, foreign docs and design docs are left alone — the control")
    void everythingElseIsLeftAlone() {
        wire();
        Map<String, Object> connector = new HashMap<>();
        connector.put("type", ConnectorDefinition.DOC_TYPE);
        connector.put("connectorId", "google-drive-default");
        listingAnswers(List.of(
                row("_design/_repo", null, null),
                row("connector_definition:google-drive-default", connector, "1-a"),
                row("import_profile_definition:cloud-import-bedroom",
                        profileProps("cloud-import-bedroom", "X"), "9-z")));
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(0, result.migrated);
        assertTrue(result.clean(), "an already-clean database was reported dirty: " + result);
    }

    @Test
    @DisplayName("a twin differing ONLY in storage fields is identical, not divergent")
    void aTwinDifferingOnlyInStorageFieldsIsIdentical() {
        wire();
        Map<String, Object> legacyProps = profileProps("p-same", "Same");
        legacyProps.put("_id", "7c1d");
        legacyProps.put("_rev", "5-r");
        listingAnswers(List.of(row("7c1d", legacyProps, "5-r")));
        Map<String, Object> twinProps = profileProps("p-same", "Same");
        twinProps.put("_id", "import_profile_definition:p-same");
        twinProps.put("_rev", "2-z");
        Document deterministicTwin = mock(Document.class);
        when(deterministicTwin.getProperties()).thenReturn(twinProps);
        deterministicReadAnswers("p-same", deterministicTwin);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertEquals(0, result.divergent.size(),
                "rows that agree in every content field were reported divergent: " + result);
        assertEquals(1, result.sweptDuplicates);
    }

    @Test
    @DisplayName("the copy never carries storage fields, whatever getProperties surfaced")
    void aCopyNeverCarriesStorageFields() {
        wire();
        Map<String, Object> legacyProps = profileProps("p-copy", "Copy");
        legacyProps.put("_id", "gen-999");
        legacyProps.put("_rev", "9-r");
        listingAnswers(List.of(row("gen-999", legacyProps, "9-r")));
        deterministicReadAnswers("p-copy", null);
        writesSucceed();

        service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        Map<String, Object> copied = written.getValue().document().getProperties();
        assertFalse(copied.containsKey("_rev"), "the copy carries the legacy row's _rev");
        assertFalse(copied.containsKey("_id"), "the copy carries the legacy _id in its body");
    }

    @Test
    @DisplayName("a legacy row carrying ATTACHMENTS is refused, not migrated incompletely")
    void anAttachmentBearingRowIsRefused() {
        wire();
        DocsResultRow legacy = row("att-legacy", profileProps("p-att", "Att"), "2-r");
        when(legacy.getDoc().getAttachments()).thenReturn(Map.of("x",
                mock(com.ibm.cloud.cloudant.v1.model.Attachment.class)));
        listingAnswers(List.of(legacy));
        deterministicReadAnswers("p-att", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.failures.size());
        assertTrue(result.failures.get(0).contains("attachments"));
    }

    @Test
    @DisplayName("a copy blocked by a TOMBSTONE purges it and retries once")
    @SuppressWarnings("unchecked")
    void aTombstoneBlockedCopyIsPurgedAndRetried() {
        wire();
        listingAnswers(List.of(row("t-legacy", profileProps("p-ghost", "Ghost"), "1-r")));
        deterministicReadAnswers("p-ghost", null);
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        Response<DocumentResult> postResponse = mock(Response.class);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(postResponse.getResult()).thenReturn(ok);
        when(postCall.execute())
                .thenThrow(new RuntimeException("409 document update conflict"))
                .thenReturn(postResponse);
        when(cloudant.postDocument(any(PostDocumentOptions.class))).thenReturn(postCall);
        ServiceCall<DocumentResult> deleteCall = mock(ServiceCall.class);
        Response<DocumentResult> deleteResponse = mock(Response.class);
        when(deleteResponse.getResult()).thenReturn(ok);
        when(deleteCall.execute()).thenReturn(deleteResponse);
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenReturn(deleteCall);
        when(wrapper.purgeTombstone("import_profile_definition:p-ghost")).thenReturn(true);

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(wrapper).purgeTombstone("import_profile_definition:p-ghost");
        verify(cloudant, org.mockito.Mockito.times(2))
                .postDocument(any(PostDocumentOptions.class));
        assertEquals(1, result.migrated, "the tombstone-blocked copy did not recover: " + result);
    }

    // ────────────────────────────────────────────────────────────────────
    // The three doors on CREATE / UPDATE
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a CREATE refuses when the deterministic row is hidden from the selector")
    void aCreateRefusesWhenTheDeterministicRowIsHidden() {
        wire();
        selectorAnswersNothing();
        // A page WITHOUT profile rows: the uniqueness listing and the count scan both walk
        // it and see nothing, so the id-addressed read is the arm that refuses. Without a
        // page the first walk answered "did not answer" and this test was red on the
        // healthy tree (and its update twin green for the wrong reason) — the sticky
        // fixture's own lesson, applied to the two tests written before it.
        listingAnswers(List.of(row("config-1", Map.of("type", "configuration"), "1-a")));
        Document hidden = mock(Document.class);
        when(hidden.getProperties()).thenReturn(profileProps("p-hidden", "Hidden"));
        // MISSES for create()'s existence check and answers for the write's own gate: get()
        // gained an id-addressed fallback, so a single-stage stub made the early check
        // refuse and this lock stopped measuring the gate its control sabotages.
        deterministicReadMissesThenAnswers("p-hidden", hidden);
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validProfile("p-hidden")),
                "a create wrote a second definition beside the deterministic row the "
                        + "selector could not show");
        assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
        assertTrue(refused.getMessage().contains("id-addressed read"),
                "refused by some other arm than the write's own id-addressed gate: "
                        + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE refuses retryably when the deterministic row is hidden")
    void anUpdateRefusesRetryablyWhenTheDeterministicRowIsHidden() {
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row("config-1", Map.of("type", "configuration"), "1-a")));
        Document hidden = mock(Document.class);
        when(hidden.getProperties()).thenReturn(profileProps("p-hidden2", "Hidden"));
        deterministicReadAnswers("p-hidden2", hidden);
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.update(validProfile("p-hidden2")),
                        "an update over a hidden deterministic row was written (a twin) or "
                                + "escaped as a 500");
        // Pins the ARM: an unanswered listing also arrives as this type, and without the
        // message a test that never reached the id-addressed read stayed green.
        assertTrue(refused.getMessage().contains("deterministic id"),
                "refused by some other arm than the id-addressed read: "
                        + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a CREATE refuses when the index-free scan finds a legacy row")
    void aCreateRefusesWhenTheScanFindsALegacyRow() {
        // The door Patch_DefaultCloudDriveConnectorProfile walks through at startup: the
        // selector rebuilding, no deterministic row yet, and the legacy cloud-import-<repo>
        // row invisible to both. Before this, create() saved a second one under a
        // generated id — the connector story, one service over.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("cloud-import-bedroom", null);
        listingAnswers(List.of(row("4383c1a96093a7526774f8d2db0a13b5",
                profileProps("cloud-import-bedroom", "Cloud Import (bedroom)"), "1-r")));
        // Unused on the healthy tree; here for the control so the sabotaged flow completes
        // and assertThrows fails on "nothing was thrown", not on a laundered NPE.
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validProfile("cloud-import-bedroom")),
                "the create wrote a second cloud-import profile beside the legacy one");
        assertTrue(refused.getMessage().contains("index-free"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    /** A disabled profile: validateAutoResolveUniqueness is skipped, so the count scan's
     *  own refusal is the arm a test reaches (the uniqueness listing walks first and would
     *  refuse the same unreadable row with its own message). */
    private static ImportProfileDefinition disabledProfile(String profileId) {
        ImportProfileDefinition def = validProfile(profileId);
        def.setEnabled(false);
        return def;
    }

    @Test
    @DisplayName("a CREATE refuses when the scan cannot READ a row — uniqueness unprovable")
    void aCreateRefusesWhenTheScanCannotRead() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-new", null);
        listingAnswers(List.of(row(null, null, null)));
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(disabledProfile("p-new")));
        assertTrue(refused.getMessage().contains("cannot be established"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a CREATE still works when the scan finds nothing — and lands on the "
            + "deterministic id")
    void aCreateStillWorksWhenTheScanFindsNothing() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-fresh", null);
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        listingAnswers(List.of(row("config-1", config, "1-a")));
        writesSucceed();

        ImportProfileDefinition created = service.create(validProfile("p-fresh"));

        assertEquals("p-fresh", created.getProfileId());
        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("import_profile_definition:p-fresh",
                written.getValue().document().getId(),
                "a new profile is still saved under a GENERATED id, so it stays invisible to "
                        + "the id-addressed duplicate check — the pre-closure shape");
    }

    @Test
    @DisplayName("a CREATE never adopts a row the scan found — the concurrent-create race")
    void aCreateNeverAdoptsARowTheScanFound() {
        // create() checks existence through the selector, then upsertDocument looks again:
        // two requests, not one snapshot. Two concurrent creates both passed the check, and
        // the slower one adopted the faster one's _id/_rev, overwrote its configuration and
        // reported 201. A review caught it. The index-free count is what refuses it.
        wire();
        DocsResultRow theirs = row("import_profile_definition:p-race",
                profileProps("p-race", "Theirs"), "1-a");
        selectorAnswersNothingThenShows(theirs);
        listingAnswers(List.of(theirs));
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validProfile("p-race")),
                "the create overwrote a row that already defined this profile");
        assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the selector must not out-report the index-free scan — the write refuses")
    void theSelectorMustNotOutReportTheWalk() {
        // The selector listed a row the authoritative walk did not find. Which row defines
        // this profile is not established, and the next line would have written to it.
        wire();
        selectorShows(row("import_profile_definition:p-ghost",
                profileProps("p-ghost", "Ghost"), "1-a"));
        listingAnswers(List.of());
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.update(validProfile("p-ghost")),
                        "the update wrote to a row the index-free scan says is not there");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE over a VISIBLE twin pair does not write — nobody chose a winner")
    void anUpdateWithTwoVisibleTwinsDoesNotWrite() {
        // The count-versus-selector arm refuses only HIDDEN twins. A standing pair seen
        // through a healthy index (2 rows, selector shows 2) sailed past it and wrote to
        // existing.get(0) with a 200 — the same PUT retried does not converge, one twin is
        // overwritten. A parallel review caught it. Not retryable: 409, naming the resolver.
        wire();
        DocsResultRow twinA = row("generated-a", profileProps("p-twin", "A"), "1-a");
        DocsResultRow twinB = row("generated-b", profileProps("p-twin", "B"), "1-b");
        selectorShows(twinA, twinB);
        listingAnswers(List.of(twinA, twinB));
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException.class,
                        () -> service.update(validProfile("p-twin")),
                        "the update wrote to one of two visible twins");
        assertTrue(refused.getMessage().contains("?docId="),
                "the refusal does not name the resolver: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE over an invisible legacy row refuses retryably — the scan's "
            + "other arm")
    void anUpdateOverAnInvisibleLegacyRowRefusesRetryably() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-vet", null);
        listingAnswers(List.of(row("old-vet-id", profileProps("p-vet", "Vet"), "1-r")));
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.update(validProfile("p-vet")),
                        "the update wrote a second definition beside a legacy row the index "
                                + "cannot show");
        assertTrue(refused.getMessage().contains("rebuilding index shows 0"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE whose scan cannot READ a row refuses retryably too — not a 500")
    void anUpdateWhoseScanCannotReadRefusesRetryablyToo() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-murky", null);
        listingAnswers(List.of(row(null, null, null)));
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.update(disabledProfile("p-murky")));
        assertTrue(refused.getMessage().contains("cannot be established"));
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE with a clean scan still writes — the upsert semantics survive")
    void anUpdateWithACleanScanStillWrites() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-brand-new", null);
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        listingAnswers(List.of(row("config-1", config, "1-a")));
        writesSucceed();

        service.update(validProfile("p-brand-new"));

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("import_profile_definition:p-brand-new",
                written.getValue().document().getId());
    }

    // ────────────────────────────────────────────────────────────────────
    // A PARTIAL selector: one twin visible, one hidden
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an UPDATE while a second twin is hidden refuses retryably — it does not "
            + "adopt the visible one")
    void anUpdateWithAHiddenTwinIsAStandingPairNotARetry() {
        // The first version consulted the walk only when the selector returned NOTHING.
        // With one twin visible and one hidden, the update adopted the visible twin's
        // _id/_rev and wrote — the pair diverged silently, with a 200. A round-2 review
        // named it. Round 3: the count has established a PAIR whatever the selector shows,
        // so the answer is the standing-twin refusal (409), not "retry" — a retry could
        // only end in that same 409.
        wire();
        DocsResultRow visible = row("import_profile_definition:p-pair",
                profileProps("p-pair", "Pair"), "4-r");
        DocsResultRow hidden = row("legacy-pair", profileProps("p-pair", "Pair (old)"), "2-r");
        selectorShows(visible);
        listingAnswers(List.of(visible, hidden));
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException.class,
                        () -> service.update(validProfile("p-pair")),
                        "a pair with one twin hidden was written to, or answered 'retry' — "
                                + "the count had already established the pair");
        assertTrue(refused.getMessage().contains("2 definition row"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE whose selector shows every row still writes — the control")
    void anUpdateWithTheWholeTruthVisibleStillWrites() {
        wire();
        DocsResultRow only = row("import_profile_definition:p-solo",
                profileProps("p-solo", "Solo"), "4-r");
        selectorShows(only);
        listingAnswers(List.of(only));
        writesSucceed();

        service.update(validProfile("p-solo"));

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("4-r", written.getValue().document().getRev(),
                "an ordinary update no longer adopts the row's revision");
    }

    // ────────────────────────────────────────────────────────────────────
    // The plain delete is index-free and repository-confined
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plain delete removes the twin the selector hid — 'deleted' means deleted")
    void thePlainDeleteRemovesHiddenTwinsToo() {
        // Selector-based delete removed only what the rebuilding index showed, then the
        // controller audited a complete deletion and stopped the scheduler while a hidden
        // twin lived on. A round-2 review named it.
        wire();
        DocsResultRow visible = row("import_profile_definition:p-gone",
                profileProps("p-gone", "Gone"), "4-r");
        DocsResultRow hidden = row("legacy-gone", profileProps("p-gone", "Gone (old)"), "2-r");
        listingAnswers(List.of(visible, hidden));
        writesSucceed();

        service.delete("p-gone", "bedroom");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant, org.mockito.Mockito.times(2)).deleteDocument(deleted.capture());
        List<String> ids = deleted.getAllValues().stream().map(DeleteDocumentOptions::docId)
                .toList();
        assertTrue(ids.contains("legacy-gone"),
                "the twin the selector could not show survived a 'successful' delete");
        assertTrue(ids.contains("import_profile_definition:p-gone"));
    }

    @Test
    @DisplayName("the plain delete leaves another repository's row alone")
    void thePlainDeleteLeavesOtherRepositoriesAlone() {
        wire();
        Map<String, Object> canopyProps = profileProps("p-shared-id", "Canopy's");
        canopyProps.put("repositoryId", "canopy");
        listingAnswers(List.of(
                row("import_profile_definition:p-shared-id", profileProps("p-shared-id", "Mine"), "1-r"),
                row("canopy-row", canopyProps, "1-c")));
        writesSucceed();

        service.delete("p-shared-id", "bedroom");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant, org.mockito.Mockito.times(1)).deleteDocument(deleted.capture());
        assertEquals("import_profile_definition:p-shared-id", deleted.getValue().docId(),
                "a caller of bedroom deleted canopy's row");
    }

    @Test
    @DisplayName("getForRepository returns THIS repository's row, index-free")
    void getForRepositoryAnswersWithoutTheIndex() {
        // The controllers use it when the selector hands back another repository's twin of a
        // shared profileId. Measured here: the controller locks mock this service away, so
        // the index-free implementation itself was unmeasured — a review named the gap.
        wire();
        selectorAnswersNothing();
        Map<String, Object> theirs = profileProps("p-shared", "Theirs");
        theirs.put("repositoryId", "canopy");
        listingAnswers(List.of(row("generated-canopy", theirs, "1-b"),
                row("import_profile_definition:p-shared", profileProps("p-shared", "Mine"), "1-a")));

        ImportProfileDefinition mine = service.getForRepository("p-shared", "bedroom");

        assertTrue(mine != null && "Mine".equals(mine.getDisplayName()),
                "the caller's own row was not found without the index: " + mine);
        assertEquals(null, service.getForRepository("p-shared", "sunroom"),
                "a repository with no row got somebody else's");

        // An UNRELATED broken row of the same repository must not refuse the lookup: only the
        // row being asked about is read. Reusing the uniqueness listing here made one such
        // row answer 503 for every profile — the connector over-throw, reintroduced.
        wire();
        selectorAnswersNothing();
        Map<String, Object> broken = profileProps("p-other", "Broken");
        broken.put("allowedConnectorIds", Map.of("not", "a list"));
        listingAnswers(List.of(row("import_profile_definition:p-other", broken, "1-x"),
                row("import_profile_definition:p-shared", profileProps("p-shared", "Mine"), "1-a")));
        ImportProfileDefinition stillFound = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.getForRepository("p-shared", "bedroom"),
                "an unrelated row this version cannot read refused the lookup");
        assertTrue(stillFound != null && "Mine".equals(stillFound.getDisplayName()),
                "resolved to: " + stillFound);
    }

    @Test
    @DisplayName("getForRepository REFUSES two rows of one profile in this repository")
    void getForRepositoryRefusesAPair() {
        // Returning either row is the silent choice this batch exists to prevent — and the
        // caller authorises a delete from what this returns, then removes EVERY row of the
        // repository, so a delegated caller authorised by one twin could remove the other.
        // Measured against the real implementation: the controller lock for this mocks the
        // service away, so the control aimed at the refusal was firing against a stub.
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row("import_profile_definition:p-pair", profileProps("p-pair", "A"), "1-a"),
                row("generated-pair", profileProps("p-pair", "B"), "1-b")));

        ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException.class,
                        () -> service.getForRepository("p-pair", "bedroom"),
                        "one of two rows was picked and returned as the profile");
        assertTrue(refused.getMessage().contains("more than one definition row"),
                refused.getMessage());
    }

    @Test
    @DisplayName("a post-delete count that cannot answer reports -1 (profile)")
    void aProfilePostDeleteCountThatCannotAnswerReportsMinusOne() {
        // The connector side had this lock; the profile side did not, and turning its catch
        // into `return 0;` would stop another repository's scheduler on a guess. A review
        // found the missing half.
        wire();
        Document legacyRow = mock(Document.class);
        when(legacyRow.getProperties()).thenReturn(profileProps("p-twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        // The page answers the pre-delete count and nothing after it.
        listingAnswersOnce(List.of(row("legacy-abc", profileProps("p-twin", "Twin"), "7-r"),
                row("import_profile_definition:p-twin", profileProps("p-twin", "Twin"), "1-d")));
        writesSucceed();

        int remaining = service.delete("p-twin", "legacy-abc", "bedroom");

        assertEquals(-1, remaining,
                "a count that could not answer was reported as a survivor count");
    }

    @Test
    @DisplayName("the plain delete reports the rows left in ANY repository")
    void thePlainDeleteReportsTheRowsLeftAnywhere() {
        // The caller stops the IMAP IDLE thread from this number, and that thread is keyed by
        // profileId alone — so the question has to be "does ANY repository still have this
        // profileId", not "does mine". Measured here because the controller tests mock this
        // service away: two controls aimed at this number were firing against a stub, which
        // is the same shape a review found twice before.
        wire();
        Map<String, Object> mine = profileProps("p-shared", "Mine");
        Map<String, Object> theirs = profileProps("p-shared", "Theirs");
        theirs.put("repositoryId", "canopy");
        listingAnswers(List.of(row("import_profile_definition:p-shared", mine, "1-a"),
                row("generated-canopy", theirs, "1-b")));
        writesSucceed();

        int leftAnywhere = service.delete("p-shared", "bedroom");

        assertEquals(1, leftAnywhere,
                "the delete does not report the rows another repository still has, so the "
                        + "caller stops a scheduler that repository is still using");
        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("import_profile_definition:p-shared", deleted.getValue().docId(),
                "the confined delete reached another repository's row");
    }

    @Test
    @DisplayName("the row-addressed delete reports how many rows REMAIN")
    void theRowDeleteReportsTheSurvivors() {
        // The controller decides whether to finish the work this path skips (the scheduler
        // stop, the deletion record) from this number. A control that sabotages it can only
        // be measured HERE: the controller tests mock this service away, so a control aimed
        // at the count fired against a stub. A review found exactly that.
        wire();
        Document legacyRow = mock(Document.class);
        when(legacyRow.getProperties()).thenReturn(profileProps("p-twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        Map<String, Object> elsewhere = profileProps("p-twin", "Twin");
        elsewhere.put("repositoryId", "canopy");
        // A PAIR in this repository (the resolver refuses to remove a lone row), plus one in
        // ANOTHER repository. The count after the delete has to be global, because the caller
        // stops a scheduler keyed by profileId alone; with every row in one repository a
        // confined count gives the same answer and the distinction is unmeasured. Caught by
        // tracing this fixture against the pre-delete guard rather than trusting it.
        listingAnswers(List.of(row("legacy-abc", profileProps("p-twin", "Twin"), "7-r"),
                row("import_profile_definition:p-twin", profileProps("p-twin", "Twin"), "1-d"),
                row("generated-canopy", elsewhere, "1-c")));
        writesSucceed();

        int remaining = service.delete("p-twin", "legacy-abc", "bedroom");

        assertEquals(2, remaining,
                "the delete does not report the surviving rows ACROSS repositories, so the "
                        + "caller stops a scheduler another repository is still using — or "
                        + "misses that the profile is gone");
    }

    @Test
    @DisplayName("a selector that THROWS falls through to the deterministic id, it does not "
            + "escape as a 500")
    void aFailingSelectorDoesNotEscape() {
        // get() left its Mango call unwrapped, so a read that threw escaped as a raw
        // RuntimeException — a 500 in front of GET, PUT and ownership transfer, the verbs this
        // batch made index-free. (The plain DELETE was fixed by deleting its selector read;
        // a review found the other three still exposed.) A failed selector is not an answer.
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("500 internal server error from the index"));
        Document row = mock(Document.class);
        when(row.getProperties()).thenReturn(profileProps("p-wrapped", "Wrapped"));
        when(row.getRev()).thenReturn("1-a");
        deterministicReadAnswers("p-wrapped", row);

        ImportProfileDefinition found = assertDoesNotThrow(() -> service.get("p-wrapped"),
                "a failed selector escaped instead of falling through to the id-addressed read");

        assertEquals("p-wrapped", found == null ? null : found.getProfileId(),
                "the deterministic id did not answer after the selector failed");
    }

    @Test
    @DisplayName("a missing conf client on the id fallback does not escape as a 500")
    void aMissingConfClientOnTheIdFallbackDoesNotEscape() {
        // The selector wrap caught findBySelector, but getConfClient() sat outside the
        // fallback try. A pool that answered the first read and then vanished leaked
        // IllegalStateException to GET/PUT. A review found the leak.
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("500 internal server error from the index"));
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper).thenReturn(null);

        ImportProfileDefinition found = assertDoesNotThrow(() -> service.get("p-no-client"),
                "a missing conf client on the id fallback escaped");
        assertEquals(null, found, "a failed fallback must not invent a row");
    }

    @Test
    @DisplayName("a transport failure on the _all_docs walk is typed not-ready, not a 500")
    void aTransportFailureOnTheWalkIsTypedNotReady() {
        // postAllDocs().execute() throwing a plain RuntimeException left the walk, and
        // callers only wrap IllegalStateException. A review named the leak.
        wire();
        when(cloudant.postAllDocs(any(PostAllDocsOptions.class))).thenAnswer(call -> {
            ServiceCall<AllDocsResult> failed = mock(ServiceCall.class);
            when(failed.execute()).thenThrow(new RuntimeException("connection reset by peer"));
            return failed;
        });

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.existsIndexFree("p-reset", "bedroom"),
                "a transport failure escaped as a raw runtime exception");
        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.getForRepository("p-reset", "bedroom"),
                "getForRepository leaked a transport failure");
        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.getOwnedRowIndexFree("p-reset"),
                "getOwnedRowIndexFree leaked a transport failure");
    }

    @Test
    @DisplayName("an unowned legacy row names the reachable DELETE, not a database repair")
    void anUnownedRowNamesTheReachableDelete() {
        // RELEASE_NOTES says any administrator can DELETE ?docId= on a row that belongs
        // to no repository. The migration ERROR still told the operator to repair the
        // database directly. A review found the two disagreeing.
        wire();
        Map<String, Object> unowned = profileProps("p-unowned", "Broken");
        unowned.remove("repositoryId");
        listingAnswers(List.of(row("generated-unowned", unowned, "1-a")));

        ConnectorDefinitionService.LegacyIdMigrationResult result = service.migrateLegacyGeneratedIds();

        assertTrue(result.failures.stream().anyMatch(f -> f.contains("?docId=")),
                "the operator is told to edit the database: " + result.failures);
    }

    @Test
    @DisplayName("the twin-row 409 names a repair the caller can actually perform")
    void theTwinRefusalNamesAReachableRepair() {
        // The count that raises this 409 is GLOBAL and ?docId= is confined to the caller's
        // repository, so naming it unconditionally told the caller to run something that would
        // be refused — in the two commonest shapes of this state. A review found the same
        // mismatch a third time; nothing measured the message until now.
        wire();
        Map<String, Object> mine = profileProps("p-shared-advice", "Mine");
        Map<String, Object> theirs = profileProps("p-shared-advice", "Theirs");
        theirs.put("repositoryId", "canopy");
        listingAnswers(List.of(row("import_profile_definition:p-shared-advice", mine, "1-a"),
                row("legacy-elsewhere", theirs, "1-b")));
        selectorShows(row("import_profile_definition:p-shared-advice", mine, "1-a"));
        writesSucceed();

        ImportProfileDefinition update = new ImportProfileDefinition();
        update.setProfileId("p-shared-advice");
        update.setRepositoryId("bedroom");
        update.setDisplayName("Mine");
        update.setTargetFolderId("folder-1");
        ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException refused = assertThrows(
                ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException.class,
                () -> service.update(update));

        assertFalse(refused.getMessage().contains("?docId="),
                "the caller is told to run a repository-confined delete for a row in another"
                        + " repository: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("not in this repository"),
                "the caller is not told where the other row is: " + refused.getMessage());
    }

    @Test
    @DisplayName("a row read by id that the scan counts as ZERO is a disagreement, not "
            + "'the only row'")
    void aCountOfZeroIsADisagreementNotTheOnlyRow() {
        // The row was READ by id and the scan counted none. "This is the only definition
        // row" is a claim neither read supports; the write path refuses exactly this
        // disagreement retryably, and answering a definitive 409 here would say more than
        // the reads establish. A review found the two states sharing one answer.
        wire();
        Document only = mock(Document.class);
        when(only.getProperties()).thenReturn(profileProps("p-solo-row", "Solo"));
        when(only.getRev()).thenReturn("1-a");
        stubGetDocument("only-row", only);
        listingAnswers(List.of());
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused = assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class, () -> service.delete("p-solo-row", "only-row", "bedroom"),
                "a scan that counted no rows answered 'this is the only row'");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("the row-addressed delete refuses to remove the ONLY definition row")
    void theRowResolverRefusesTheOnlyRow() {
        // This operation resolves a divergent PAIR: it skips the scheduler stop and the
        // deletion record because it assumes the definition survives. Removing the last row
        // through it deletes the definition outright with neither. A review found the
        // assumption unchecked.
        wire();
        Document only = mock(Document.class);
        when(only.getProperties()).thenReturn(profileProps("p-solo-row", "Solo"));
        when(only.getRev()).thenReturn("1-a");
        stubGetDocument("only-row", only);
        listingAnswers(List.of(row("only-row", profileProps("p-solo-row", "Solo"), "1-a")));
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileHasNoTwinException refused = assertThrows(ImportProfileDefinitionServiceImpl.ProfileHasNoTwinException.class,
                () -> service.delete("p-solo-row", "only-row", "bedroom"),
                "the last definition row was removed through the divergent-pair resolver");
        assertTrue(refused.getMessage().contains("only definition row"), refused.getMessage());
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("a schedulable row with no profileId is skipped, not scheduled")
    void anIdentitylessRowIsNotScheduled() {
        // The per-row skip caught rows that FAILED to deserialise. A row that deserialises but
        // has no profileId went into the schedule, and the delegated tick then put a null id
        // into a key set — an NPE that escaped to the poll's outer catch and stopped every
        // profile after it. One broken row, a second shape. A review traced it.
        wire();
        Map<String, Object> nameless = profileProps("p-fine", "Fine");
        nameless.remove("profileId");
        nameless.put("enabled", true);
        nameless.put("schedulerEnabled", true);
        Map<String, Object> good = profileProps("p-fine", "Fine");
        good.put("enabled", true);
        good.put("schedulerEnabled", true);
        listingAnswers(List.of(row("nameless-row", nameless, "1-a"),
                row("import_profile_definition:p-fine", good, "1-b")));

        List<ImportProfileDefinition> scheduled = service.listScheduledIndexFree();

        assertEquals(1, scheduled.size(),
                "a row with no profileId was scheduled: " + scheduled);
        assertEquals("p-fine", scheduled.get(0).getProfileId());
    }

    // ────────────────────────────────────────────────────────────────────
    // listOwnedIndexFree — the webhook receiver's recipients, read without the index
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the owned listing returns every owned row the walk can read — legacy id or "
            + "deterministic, enabled or not — reports the ones it cannot interpret with their "
            + "raw connector fields, and neither lists nor reports an unowned or disabled one")
    void theOwnedListingSeesEveryOwnedRowAndReportsTheRest() {
        // The webhook receiver picked its recipients out of list() — a selector: whenever
        // the index did not show a row it saw no profile and answered no_profile with a 200,
        // the sender's event consumed. The walk does not consult the index, so the selector
        // is left unstubbed here and asserted never asked. A row the walk cannot interpret is
        // REPORTED, not dropped: dropped, it answered no_profile for a recipient that exists.
        wire();
        Map<String, Object> legacy = profileProps("p-legacy", "Legacy");
        Map<String, Object> disabled = profileProps("p-off", "Off");
        disabled.put("enabled", false);
        Map<String, Object> unowned = profileProps("p-nobody", "Nobody");
        unowned.put("repositoryId", "  ");
        Map<String, Object> broken = profileProps("p-broken", "Broken");
        broken.put("retentionDays", "not-a-number");
        broken.put("defaultConnectorId", "c-dbx");
        broken.put("allowedConnectorIds", List.of("c-a", "c-dbx"));
        broken.put("allowedArchetypes", List.of("FILE_SHARE"));
        Map<String, Object> offAndBroken = profileProps("p-off-broken", "Off and broken");
        offAndBroken.put("enabled", false);
        offAndBroken.put("retentionDays", "not-a-number");
        offAndBroken.put("defaultConnectorId", "c-dbx");
        // The string form of a disabled row, as the connector listing reads it too: a row
        // this node cannot interpret has no other reader to normalise the string, so it is
        // not reported either. A review found the two listings reading "false" differently.
        Map<String, Object> offAsStringAndBroken = profileProps("p-off-str-broken", "Off (string)");
        offAsStringAndBroken.put("enabled", "false");
        offAsStringAndBroken.put("retentionDays", "not-a-number");
        offAsStringAndBroken.put("defaultConnectorId", "c-dbx");
        listingAnswers(List.of(
                row("a1b2c3-generated", legacy, "1-a"),
                row("import_profile_definition:p-off", disabled, "1-b"),
                row("import_profile_definition:p-nobody", unowned, "1-c"),
                row("import_profile_definition:p-broken", broken, "1-d"),
                row("import_profile_definition:p-off-broken", offAndBroken, "1-e"),
                row("import_profile_definition:p-off-str-broken", offAsStringAndBroken, "1-f")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();
        List<String> listed = owned.profiles().stream()
                .map(ImportProfileDefinition::getProfileId).sorted().toList();

        assertEquals(List.of("p-legacy", "p-off"), listed,
                "not the owned rows the walk could read — an unowned row is not a recipient, "
                        + "a disabled one is listed (the receiver filters enabled itself), and "
                        + "a row that cannot be interpreted is not listed as read: " + listed);
        assertEquals(1, owned.uninterpretable().size(),
                "the rows the walk could not read are not reported as such (a disabled one — "
                        + "literal or string — is not a recipient and must not be): "
                        + owned.uninterpretable());
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertEquals("import_profile_definition:p-broken", reported.docId());
        assertEquals("p-broken", reported.profileId());
        assertEquals("c-dbx", reported.defaultConnectorId(),
                "the raw default connector is what tells the receiver the row was addressed to it");
        assertEquals(List.of("c-a", "c-dbx"), reported.allowedConnectorIds());
        assertTrue(reported.namesConnector("c-dbx") && reported.namesConnector("c-a")
                        && !reported.namesConnector("c-z"),
                "namesConnector does not read both raw fields");
        assertFalse(reported.addresseeUnknown(), "readable connector fields were reported as unreadable");
        assertEquals(List.of(SourceArchetype.FILE_SHARE), reported.allowedArchetypes(),
                "the archetype list was not carried — the receiver refuses on the name alone");
        assertTrue(reported.addressedTo("c-dbx", SourceArchetype.FILE_SHARE)
                        && !reported.addressedTo("c-dbx", SourceArchetype.MESSAGE_CONTEXT),
                "addressedTo does not read the raw archetype list");
        assertFalse(reported.addressedTo("c-dbx", null),
                "a connector with no archetype was admitted by a restricting list — "
                        + "isArchetypeAllowed admits it by none");
        verify(cloudant, never()).postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class));
    }

    @Test
    @DisplayName("the admin listing names the row it could not read — the WARN carries the "
            + "row's id, as the release notes promise")
    void theAdminListingNamesTheRowItCannotRead() {
        // list() skips a row it cannot deserialise and answers with the rest; the operator's
        // only handle on the skipped row is this WARN, and it carried the exception message
        // without the id. A review found the notes promising more than the log said.
        wire();
        Map<String, Object> odd = profileProps("p-odd", "Odd");
        odd.put("retentionDays", "not-a-number");
        Document oddDoc = selectorDoc(odd);
        when(oddDoc.getId()).thenReturn("legacy-p-odd-row");
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> page = findCallOf(List.of(oddDoc), null);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(page);
        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        List<ImportProfileDefinition> listed;
        try {
            listed = service.list();
        } finally {
            log.detachAppender(appender);
        }

        assertTrue(listed.isEmpty(), "the unreadable row was listed: " + listed);
        assertTrue(appender.list.stream().anyMatch(e ->
                        e.getLevel() == ch.qos.logback.classic.Level.WARN
                                && e.getFormattedMessage().contains("legacy-p-odd-row")),
                "the WARN does not name the skipped row: " + appender.list);
    }

    @Test
    @DisplayName("an owned row with no profileId is reported too — it may still name a connector")
    void theOwnedListingReportsANamelessRowToo() {
        // The second shape of "one broken row": deserialisable, no identity. It is not
        // listed (nothing can be dispatched to it) but it is not silently dropped either —
        // its raw connector fields still say whom it was addressed to. The earlier lock's
        // broken row had a profileId, so this arm was unmeasured. A review found it.
        wire();
        Map<String, Object> nameless = profileProps("p-x", "Nameless");
        nameless.remove("profileId");
        nameless.put("defaultConnectorId", "c-dbx");
        listingAnswers(List.of(row("nameless-row", nameless, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertTrue(owned.profiles().isEmpty(), "a row with no profileId was listed: " + owned.profiles());
        assertEquals(1, owned.uninterpretable().size(),
                "the nameless row was dropped instead of reported: " + owned.uninterpretable());
        assertTrue(owned.uninterpretable().get(0).namesConnector("c-dbx"),
                "the nameless row's raw default connector was not read");
    }

    @Test
    @DisplayName("a row whose connector fields have no readable shape addresses EVERY connector")
    void aRowWhoseConnectorFieldsHaveNoReadableShapeAddressesEveryConnector() {
        // defaultConnectorId that is not a string (a newer node's shape, or corruption): the
        // first version read it as "names nobody" — the skip this record exists to prevent,
        // one field down. Whom the row addresses cannot be established, so it addresses all.
        wire();
        Map<String, Object> oddShape = profileProps("p-odd", "Odd");
        oddShape.put("retentionDays", "not-a-number");
        // A list where a string is expected: a shape the mapper refuses. (A number would
        // not be one — the mapper coerces it to "42"; a review found the first version of
        // this lock pinning that mistake.)
        oddShape.put("defaultConnectorId", List.of("c-dbx"));
        listingAnswers(List.of(row("import_profile_definition:p-odd", oddShape, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertTrue(reported.addresseeUnknown(),
                "a connector field of unreadable shape was not reported as such");
        assertTrue(reported.namesConnector("c-anything"),
                "a row whose addressee cannot be established answered 'names nobody'");
    }

    @Test
    @DisplayName("a row whose archetype field has no readable shape admits EVERY archetype — "
            + "but still names only the connectors its readable connector fields name")
    void aRowWhoseArchetypeFieldHasNoReadableShapeAdmitsEveryArchetypeButNamesOnlyItsConnectors() {
        // The archetype list is the third field the receiver reads off a broken row (to let a
        // row that plainly excludes the connector's archetype through). A list that is not a
        // list of strings cannot exclude anything — reading it as "excludes everyone" would be
        // the same skip one field further down. But its shape says nothing about WHOM the row
        // names: the first version folded it into addresseeUnknown, and a row naming c-dbx
        // stopped c-other's dispatch. A review found that over-throw.
        wire();
        Map<String, Object> oddShape = profileProps("p-odd", "Odd");
        oddShape.put("retentionDays", "not-a-number");
        oddShape.put("defaultConnectorId", "c-dbx");
        oddShape.put("allowedArchetypes", "FILE_SHARE");
        listingAnswers(List.of(row("import_profile_definition:p-odd", oddShape, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertEquals(null, reported.allowedArchetypes(),
                "an archetype field of unreadable shape was carried as a list");
        assertTrue(reported.addressedTo("c-dbx", SourceArchetype.MESSAGE_CONTEXT),
                "a row whose archetypes cannot be read answered 'not addressed to this one'");
        assertFalse(reported.addresseeUnknown(),
                "the archetype field's shape was folded into the connector fields' flag");
        assertFalse(reported.addressedTo("c-other", SourceArchetype.FILE_SHARE),
                "a row naming c-dbx, whose archetypes cannot be read, was addressed to c-other");
    }

    @Test
    @DisplayName("a row whose connector fields have no readable shape is still not addressed to "
            + "a connector its readable archetype list plainly excludes")
    void aRowWhoseConnectorFieldsHaveNoReadableShapeIsNotAddressedToAnArchetypeItExcludes() {
        // Whom the row names cannot be established (it names everyone), but its archetype list
        // is readable and excludes FILE_SHARE: a readable row with these fields would have
        // been filtered out on the archetype alone. The first version short-circuited on the
        // connector flag before looking at the list; a review found the over-throw.
        wire();
        Map<String, Object> oddShape = profileProps("p-odd", "Odd");
        oddShape.put("retentionDays", "not-a-number");
        // A list where a string is expected: a shape the mapper refuses. (A number would
        // not be one — the mapper coerces it to "42"; a review found the first version of
        // this lock pinning that mistake.)
        oddShape.put("defaultConnectorId", List.of("c-dbx"));
        oddShape.put("allowedArchetypes", List.of("MESSAGE_CONTEXT"));
        listingAnswers(List.of(row("import_profile_definition:p-odd", oddShape, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertTrue(reported.addresseeUnknown(),
                "a connector field of unreadable shape was not reported as such");
        assertTrue(reported.addressedTo("c-anything", SourceArchetype.MESSAGE_CONTEXT),
                "a row that may name anyone was not addressed to the archetype it admits");
        assertFalse(reported.addressedTo("c-anything", SourceArchetype.FILE_SHARE),
                "a row whose readable archetype list excludes FILE_SHARE was addressed to it");
    }

    @Test
    @DisplayName("a mis-cased archetype name is unreadable through the real mapper, and the row "
            + "it breaks addresses every archetype — the premise addressedTo's unknown-name arm "
            + "stands on")
    void aMiscasedArchetypeNameMakesTheRowUnreadableAndItAddressesEveryArchetype() {
        // addressedTo lets a broken row through when its raw list plainly excludes the
        // connector's archetype, on the ground that a readable row with that list would have
        // been filtered out. That ground holds only if a list with a name the enum does not
        // spell that way cannot be a readable row at all — which is the mapper's doing, not
        // this code's. Measured here so a mapper made case-insensitive would show up as this
        // failing, not as a broken row silently read as "excludes everyone".
        wire();
        Map<String, Object> miscased = profileProps("p-miscased", "Miscased");
        miscased.put("defaultConnectorId", "c-dbx");
        miscased.put("allowedArchetypes", List.of("file_share"));
        listingAnswers(List.of(row("import_profile_definition:p-miscased", miscased, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertTrue(owned.profiles().isEmpty(),
                "a mis-cased archetype name read as a profile — the mapper is not reading enum "
                        + "names exactly: " + owned.profiles());
        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertEquals(null, reported.allowedArchetypes(),
                "a list the mapper refuses was carried as a list — read by something other "
                        + "than the mapper");
        assertFalse(reported.addresseeUnknown(), "a list of strings was reported as unreadable in shape");
        assertTrue(reported.addressedTo("c-dbx", SourceArchetype.FILE_SHARE)
                        && reported.addressedTo("c-dbx", SourceArchetype.MESSAGE_CONTEXT),
                "a list with a name this node does not know was read as excluding an archetype");
    }

    @Test
    @DisplayName("a numeric connector id on a broken row reads as the mapper reads it — \"42\", "
            + "not 'cannot be read'")
    void aNumericConnectorIdReadsAsTheMapperReadsIt() {
        // The production mapper coerces a number into a string field (Jackson 2 defaults),
        // so a readable row with defaultConnectorId 42 names connector "42". The hand-rolled
        // reader called it unreadable and made the row name every connector — an
        // establishable recipient turned into a refusal of all. A review found it; the
        // fields are now read by the mapper itself, and this measures that on the real one.
        wire();
        Map<String, Object> numeric = profileProps("p-num", "Numeric");
        numeric.put("retentionDays", "not-a-number");
        numeric.put("defaultConnectorId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("import_profile_definition:p-num", numeric, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertFalse(reported.addresseeUnknown(),
                "a connector id the mapper reads was reported as unreadable");
        assertEquals("42", reported.defaultConnectorId());
        assertTrue(reported.namesConnector("42") && !reported.namesConnector("c-dbx"),
                "a row naming connector \"42\" was read as naming someone else");
    }

    @Test
    @DisplayName("a numeric profileId in the shape the READ PATH delivers still reads as \"42\" "
            + "— the identity claims are measured on Gson's number, not a Java Integer")
    void aNumericProfileIdInTheReadPathsOwnShapeStillReadsAsTheMapperReadsIt() {
        // The rows this service reads are built by the SDK with Gson, so a JSON number is a
        // LazilyParsedNumber, not an Integer. For a primitive boolean the mapper refuses that
        // type (a review found a lock holding the opposite claim green with an Integer), so
        // the identity claims — counted, looked up and deleted as "42" — have to be measured
        // on the same shape rather than inherited from the boolean case. Every numeric fixture
        // in these two classes now uses this type, so those locks measure it too; this one
        // names the concern.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        numeric.put("retentionDays", "not-a-number");
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        assertEquals("42", owned.uninterpretable().get(0).profileId(),
                "the identity of a row whose profileId is a Gson number was not read as \"42\"");
    }

    @Test
    @DisplayName("a null element in a broken row's archetype list reads as the mapper reads it — "
            + "the list still excludes what it does not contain")
    void aNullElementInTheArchetypeListReadsAsTheMapperReadsIt() {
        // Jackson keeps a null element in an enum list, and isArchetypeAllowed then answers
        // by contains(): a readable row carrying [MESSAGE_CONTEXT, null] excludes FILE_SHARE.
        // The hand-rolled reader rejected the whole list as unreadable and admitted every
        // archetype — a refusal the readable path would not have made. A review found it.
        wire();
        Map<String, Object> holed = profileProps("p-holed", "Holed");
        holed.put("retentionDays", "not-a-number");
        holed.put("defaultConnectorId", "c-dbx");
        holed.put("allowedArchetypes", java.util.Arrays.asList("MESSAGE_CONTEXT", null));
        listingAnswers(List.of(row("import_profile_definition:p-holed", holed, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(1, owned.uninterpretable().size(), "the row was not reported: " + owned);
        ImportProfileDefinitionService.UninterpretableRow reported = owned.uninterpretable().get(0);
        assertEquals(java.util.Arrays.asList(SourceArchetype.MESSAGE_CONTEXT, null),
                reported.allowedArchetypes(), "the list was not read as the mapper reads it");
        assertTrue(reported.addressedTo("c-dbx", SourceArchetype.MESSAGE_CONTEXT));
        assertFalse(reported.addressedTo("c-dbx", SourceArchetype.FILE_SHARE),
                "a list that plainly excludes FILE_SHARE was read as admitting it");
    }

    @Test
    @DisplayName("a broken row whose enabled is an explicit null is not a recipient — the mapper "
            + "reads null into the primitive as false")
    void aRowWhoseEnabledIsAnExplicitNullIsNotARecipient() {
        // A readable row with enabled: null carries enabled == false (Jackson 2 defaults for a
        // primitive) and the receiver filters it out; the hand-rolled check knew only the
        // literal and the string, so the same row, broken elsewhere, was reported and could
        // refuse a dispatch it could never have received. A review found it.
        wire();
        Map<String, Object> nulled = profileProps("p-nulled", "Nulled");
        nulled.put("retentionDays", "not-a-number");
        nulled.put("defaultConnectorId", "c-dbx");
        nulled.put("enabled", null);
        listingAnswers(List.of(row("import_profile_definition:p-nulled", nulled, "1-a")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertTrue(owned.uninterpretable().isEmpty(),
                "a row the mapper reads as disabled was reported as a possible recipient: "
                        + owned.uninterpretable());
        assertTrue(owned.profiles().isEmpty(),
                "a broken row was listed as read: " + owned.profiles());
    }

    @Test
    @DisplayName("the owned listing refuses a row it cannot read rather than answering short")
    void theOwnedListingRefusesAnUnreadableRow() {
        // Dropping the row and answering with the rest reads, at the receiver, as "these are
        // all the profiles" — and when the dropped row was the recipient, as no_profile.
        wire();
        listingAnswers(List.of(
                row("import_profile_definition:p-fine", profileProps("p-fine", "Fine"), "1-a"),
                row(null, null, null)));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.listOwnedIndexFree(),
                "a row the walk could not read was dropped and the listing answered as if "
                        + "it were complete");
    }

    // ────────────────────────────────────────────────────────────────────
    // The selector listing follows its bookmark — one page was never the whole answer
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the selector listing pages past a full first page — a profile on page two "
            + "is listed")
    void theSelectorListingPagesPastTheFirstPage() {
        // list() and listByRepository() asked the selector for one page of 200 and returned
        // it as the whole answer: the 201st profile was never listed, by the admin API or by
        // anything that filtered list(). Unlike the rebuilding index, that answered short
        // EVERY time it ran.
        wire();
        List<Document> firstPage = new ArrayList<>();
        for (int i = 0; i < NemakiConfFind.PAGE; i++) {
            firstPage.add(selectorDoc(profileProps(String.format("p-%04d", i), "Early")));
        }
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> first =
                findCallOf(firstPage, "page-2");
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> second =
                findCallOf(List.of(selectorDoc(profileProps("p-late", "Late"))), "page-3");
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenAnswer(call -> {
                    com.ibm.cloud.cloudant.v1.model.PostFindOptions options = call.getArgument(0);
                    return "page-2".equals(options.bookmark()) ? second : first;
                });

        List<ImportProfileDefinition> all = service.list();

        assertEquals(NemakiConfFind.PAGE + 1, all.size(),
                "the listing stopped at its first page — a profile past it is not listed");
        assertTrue(all.stream().anyMatch(p -> "p-late".equals(p.getProfileId())),
                "the profile on page two is missing: " + all.size() + " listed");
    }

    @Test
    @DisplayName("a full selector page with no bookmark to continue from is refused, not "
            + "returned as the whole answer")
    void theSelectorListingRefusesAFullPageWithoutABookmark() {
        // The typed refusal, not a bare IllegalStateException: the create path answers 400
        // for that, and "the listing could not be completed" is not the caller's fault.
        wire();
        List<Document> fullPage = new ArrayList<>();
        for (int i = 0; i < NemakiConfFind.PAGE; i++) {
            fullPage.add(selectorDoc(profileProps(String.format("p-%04d", i), "Early")));
        }
        // Built BEFORE the stubbing: findCallOf stubs mocks of its own, and doing that inside
        // thenReturn(...) leaves this stubbing unfinished — the class then dies with
        // UnfinishedStubbingException instead of failing on its own claim.
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> noBookmark = findCallOf(fullPage, null);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(noBookmark);

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.list(),
                "a full page with nothing to continue from was returned as complete");
    }

    @Test
    @DisplayName("a selector transport failure is logged WARN with its cause — the admin "
            + "handlers answer 503 without logging, so this is where the stack trace lives")
    void theSelectorTransportFailureIsLoggedWithItsCause() {
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("connection reset"));
        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                    () -> service.list());
        } finally {
            log.detachAppender(appender);
        }

        assertTrue(appender.list.stream().anyMatch(e ->
                        e.getLevel() == ch.qos.logback.classic.Level.WARN
                                && e.getThrowableProxy() != null
                                && e.getFormattedMessage().contains("could not be read")),
                "the selector transport failure was not logged WARN with its cause: "
                        + appender.list);
    }

    @Test
    @DisplayName("an IllegalStateException from the SDK is logged WARN with its cause too — the "
            + "listing's own refusals share that arm")
    void anSdkIllegalStateExceptionIsLoggedWithItsCause() {
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new IllegalStateException("client closed"));
        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                    () -> service.list());
        } finally {
            log.detachAppender(appender);
        }

        assertTrue(appender.list.stream().anyMatch(e ->
                        e.getLevel() == ch.qos.logback.classic.Level.WARN
                                && e.getThrowableProxy() != null
                                && e.getFormattedMessage().contains("could not be completed")),
                "an SDK IllegalStateException was not logged WARN with its cause: " + appender.list);
    }

    @Test
    @DisplayName("the selector listing refuses a TRANSPORT failure with the typed refusal too — "
            + "not the raw exception the controllers' handlers never see")
    void theSelectorListingRefusesATransportFailureWithTheTypedRefusal() {
        // The three listing refusals (no docs, no bookmark, a repeated bookmark) were typed;
        // the SDK's own failure escaped raw and became a 500 where they answered 503. A
        // review found the fourth shape.
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.list(),
                "a selector transport failure escaped the listing untyped");
    }

    @Test
    @DisplayName("a selector listing that did not answer is refused, not returned empty")
    void theSelectorListingRefusesAListingThatDidNotAnswer() {
        wire();
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> noDocs = findCallOf(null, null);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(noDocs);

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.list(),
                "an answer without docs was read as an empty listing");
    }

    @Test
    @DisplayName("a bookmark that comes back a second time is refused — a cycle would append "
            + "the same pages for ever")
    void theSelectorListingRefusesABookmarkCycle() {
        // A → B → A: the immediate-repeat check alone lets this loop. Preemptive timeout so
        // that a listing that DOES loop fails on this assertion instead of hanging the class.
        wire();
        List<Document> fullPage = new ArrayList<>();
        for (int i = 0; i < NemakiConfFind.PAGE; i++) {
            fullPage.add(selectorDoc(profileProps(String.format("p-%04d", i), "Early")));
        }
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> first = findCallOf(fullPage, "A");
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> pageA = findCallOf(fullPage, "B");
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> pageB = findCallOf(fullPage, "A");
        int[] served = new int[1];
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenAnswer(call -> {
                    com.ibm.cloud.cloudant.v1.model.PostFindOptions options = call.getArgument(0);
                    // A listing that follows the cycle grows by 200 references a turn and
                    // would hit the heap before the preemptive timeout — a fork death, not a
                    // firing. The stub ends it deterministically on the fifth page instead
                    // (a correct listing asks for at most three: first, A, B).
                    if (++served[0] > 4) {
                        throw new AssertionError("bookmark cycle followed: page " + served[0]
                                + " requested");
                    }
                    if (options.bookmark() == null) return first;
                    return "A".equals(options.bookmark()) ? pageA : pageB;
                });

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.list(),
                        "a bookmark cycle was followed instead of refused"),
                "the listing looped on the bookmark cycle");
    }

    @Test
    @DisplayName("a DISABLED row with no profileId does not block a create — it cannot be "
            + "chosen either")
    void aDisabledRowWithoutAProfileIdDoesNotBlockACreate() {
        // The identity check ran before the disabled skip, so a disabled row with no
        // profileId refused every write of its repository — over a row no rule can read
        // anything from. A review found the order.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-new3", null);
        Map<String, Object> offAndNameless = profileProps("p-x", "X");
        offAndNameless.remove("profileId");
        offAndNameless.put("enabled", false);
        listingAnswers(List.of(row("nameless-off-row", offAndNameless, "1-r")));
        writesSucceed();

        ImportProfileDefinition created = assertDoesNotThrow(
                () -> service.create(defaultProfile("p-new3")),
                "a disabled row with no identity refused the create");

        assertEquals("p-new3", created.getProfileId());
    }

    /** One raw document as the selector serves it. */
    private static Document selectorDoc(Map<String, Object> props) {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(ImportProfileDefinition.DOC_TYPE + ":" + props.get("profileId"));
        when(doc.getRev()).thenReturn("1-a");
        when(doc.getProperties()).thenReturn(props);
        return doc;
    }

    /** A selector page: these documents, and this bookmark to continue from. */
    @SuppressWarnings("unchecked")
    private static ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> findCallOf(
            List<Document> docs, String bookmark) {
        com.ibm.cloud.cloudant.v1.model.FindResult found =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(found.getDocs()).thenReturn(docs);
        when(found.getBookmark()).thenReturn(bookmark);
        Response<com.ibm.cloud.cloudant.v1.model.FindResult> response = mock(Response.class);
        when(response.getResult()).thenReturn(found);
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> call = mock(ServiceCall.class);
        when(call.execute()).thenReturn(response);
        return call;
    }

    @Test
    @DisplayName("a row whose repositoryId is BLANK is removable by its docId too")
    void aBlankRepositoryRowIsReachable() {
        // The migration classifies null and blank alike as malformed and tells the operator to
        // remove them with ?docId=. The delete path recognised only literal null, so a blank
        // row was undeletable through the very API the message prescribes — and went on
        // blocking every write of that profileId. A review found the two halves disagreeing.
        wire();
        Map<String, Object> blank = profileProps("p-blank", "Blank");
        blank.put("repositoryId", "   ");
        Document orphan = mock(Document.class);
        when(orphan.getProperties()).thenReturn(blank);
        when(orphan.getRev()).thenReturn("1-a");
        stubGetDocument("blank-row", orphan);
        listingAnswers(List.of(row("blank-row", blank, "1-a")));
        writesSucceed();

        assertDoesNotThrow(() -> service.delete("p-blank", "blank-row", "bedroom"),
                "a row whose repositoryId is blank is refused by the API that is supposed to"
                        + " remove it");
        assertTrue(deletedIds.contains("blank-row"),
                "the blank row is still unreachable: " + deletedIds);
    }

    @Test
    @DisplayName("a blank repositoryId is not an owned row for IDLE either")
    void aBlankRepositoryRowIsNotOwned() {
        // getOwnedRowIndexFree rejected only null, so a blank row counted as owned and IDLE
        // would start a capture on a row no repository can manage.
        wire();
        Map<String, Object> blank = profileProps("p-blank", "Blank");
        blank.put("repositoryId", "");
        listingAnswers(List.of(row("blank-row", blank, "1-a")));

        assertEquals(null, service.getOwnedRowIndexFree("p-blank"),
                "a row that names no repository was handed back as an owned one");
    }

    @Test
    @DisplayName("a foreign document sitting on the deterministic id is not the profile")
    void theDeterministicIdIsNotReserved() {
        // The id is deterministic, not reserved: any document occupying it was deserialised
        // and returned, so GET /{id} could answer with a different row entirely. The connector
        // twin has always checked type and id; a review found the profile half missing it.
        wire();
        Map<String, Object> impostor = profileProps("someone-else", "Impostor");
        Document row = mock(Document.class);
        when(row.getProperties()).thenReturn(impostor);
        when(row.getRev()).thenReturn("1-a");
        deterministicReadAnswers("p-target", row);
        selectorShows();

        assertEquals(null, service.get("p-target"),
                "a document occupying the deterministic id was returned as the profile");
    }

    @Test
    @DisplayName("a row that belongs to NO repository is removable by its docId")
    void anUnownedRowIsReachable() {
        // The migration leaves such rows deliberately. Every repository-confined read skips
        // them, yet they count towards the global row count that refuses writes — so the
        // profileId's PUT answered 409 for ever while both delete verbs refused to touch the
        // row causing it. The 409 even named this operation. A review found it unreachable.
        wire();
        Map<String, Object> unowned = profileProps("p-unowned", "Unowned");
        unowned.remove("repositoryId");
        Document orphan = mock(Document.class);
        when(orphan.getProperties()).thenReturn(unowned);
        when(orphan.getRev()).thenReturn("1-a");
        stubGetDocument("orphan-row", orphan);
        listingAnswers(List.of(row("orphan-row", unowned, "1-a")));
        writesSucceed();

        // assertDoesNotThrow, not a bare call: the refusal this lock is about is an
        // IllegalArgumentException, and letting it escape makes the failure an ERROR rather
        // than an assertion — the control runner then scores its own sabotage as "broke the
        // harness, proves nothing". Measured, it did exactly that.
        int leftAnywhere = assertDoesNotThrow(
                () -> service.delete("p-unowned", "orphan-row", "bedroom"),
                "the row no repository owns is refused: no other verb reaches it, and it holds"
                        + " that profileId's PUT at a standing 409");

        assertTrue(deletedIds.contains("orphan-row"),
                "the row no repository owns is still unreachable: " + deletedIds);
        assertEquals(0, leftAnywhere,
                "the survivor count did not see the removal");
    }

    @Test
    @DisplayName("the plain delete removes a row whose profileId is the number — 'deleted' "
            + "means deleted for the identity every other read uses")
    void thePlainDeleteRemovesARowWhoseProfileIdIsTheNumber() {
        // The walk compared the raw value, so a row the listing calls "42" was left standing
        // while the delete reported success. A review found the count and the lookups in that
        // state; the delete verbs were in it too and no lock said so.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));
        writesSucceed();

        service.delete("42", "bedroom");

        assertTrue(deletedIds.contains("legacy-42"),
                "the row the listing calls \"42\" survived a 'successful' delete: " + deletedIds);
    }

    @Test
    @DisplayName("the row-addressed delete accepts a row whose profileId is the number")
    void theOneRowDeleteAcceptsARowWhoseProfileIdIsTheNumber() {
        // The addressed-row check compared the raw value and refused the repair its own
        // 409 prescribes. Two rows define "42" here, so this is the pair-resolving operation
        // it is meant to be.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        Document addressed = mock(Document.class);
        when(addressed.getProperties()).thenReturn(numeric);
        when(addressed.getRev()).thenReturn("1-a");
        stubGetDocument("legacy-42", addressed);
        listingAnswers(List.of(row("legacy-42", numeric, "1-a"),
                row("import_profile_definition:42", profileProps("42", "Canonical"), "1-b")));
        writesSucceed();

        // assertDoesNotThrow: the refusal under measurement is an IllegalArgumentException,
        // and a lock that dies on it is "harness broken" to the runner, not a firing.
        assertDoesNotThrow(() -> service.delete("42", "legacy-42", "bedroom"),
                "the row the listing calls \"42\" was refused by the repair its own 409 names");

        assertTrue(deletedIds.contains("legacy-42"),
                "the addressed row was not removed: " + deletedIds);
    }

    @Test
    @DisplayName("a row whose repositoryId is not a string names no repository — the addressed "
            + "delete the migration prescribes reaches it")
    void theOneRowDeleteReachesARowWhoseRepositoryIdIsNotAString() {
        // The migration calls such a row malformed and tells the operator to remove it with
        // ?docId=; the delete read "names no repository" as null-or-blank only, so a value of
        // any other shape was neither owned by the caller nor unowned, and the prescribed
        // repair answered 404. The same defect was fixed once for the blank value; a review
        // found it again one value further out.
        wire();
        Map<String, Object> odd = profileProps("p-odd", "Odd");
        odd.put("repositoryId", new com.google.gson.internal.LazilyParsedNumber("42"));
        Document orphan = mock(Document.class);
        when(orphan.getProperties()).thenReturn(odd);
        when(orphan.getRev()).thenReturn("1-a");
        stubGetDocument("odd-repo-row", orphan);
        listingAnswers(List.of(row("odd-repo-row", odd, "1-a")));
        writesSucceed();

        assertDoesNotThrow(() -> service.delete("p-odd", "odd-repo-row", "bedroom"),
                "the row whose repositoryId is not a string was refused by the very DELETE "
                        + "the migration's message prescribes");

        assertTrue(deletedIds.contains("odd-repo-row"),
                "the malformed row is still unreachable: " + deletedIds);
    }

    @Test
    @DisplayName("a legacy row whose profileId is the number is migrated, and the copy carries "
            + "that identity as the string every read uses")
    void theMigrationNormalisesAProfileIdTheMapperCoerces() {
        // Two halves, both found by review. The classification read the identity raw and
        // reported such a row as having none — while the mapper-based count called it "42".
        // And migrating it without normalising would not have been enough: the Mango selector
        // compares types strictly, so get(), exists() and the write path could never see a
        // stored number, and the profile stayed permanently un-writable (the count said one
        // row, the selector said none, and the update answered "retry once the index has
        // caught up" for a state no rebuild reaches).
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));
        deterministicReadAnswers("42", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("import_profile_definition:42", written.getValue().document().getId(),
                "the row the rest of the service calls \"42\" was not given that deterministic id");
        assertEquals("42", written.getValue().document().get("profileId"),
                "the copy kept the stored number, which the Mango selector can never match — "
                        + "the profile stays un-writable");
        assertEquals(1, result.migrated);
        assertTrue(result.clean(), "a clean migration reported problems: " + result);
    }

    @Test
    @DisplayName("an interrupted normalising pass converges: the legacy row is retired next "
            + "time, not called divergent")
    void anInterruptedNormalisingMigrationRetiresTheLegacyRowOnTheNextPass() {
        // The copy this migration writes is normalised. If the copy succeeded and the
        // retirement of the legacy row then failed, the next pass compared a NORMALISED
        // deterministic row with the UN-normalised legacy one, called the identical pair
        // divergent and never retired it — a standing twin that blocks every update of that
        // profile, for ever. A review found the retry path.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));
        Document alreadyCopied = mock(Document.class);
        when(alreadyCopied.getProperties()).thenReturn(profileProps("42", "Numeric"));
        when(alreadyCopied.getRev()).thenReturn("2-b");
        deterministicReadAnswers("42", alreadyCopied);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertTrue(deletedIds.contains("legacy-42"),
                "the leftover of an interrupted pass was not retired: " + deletedIds);
        assertTrue(result.divergent.isEmpty(),
                "an identical pair was reported as divergent: " + result.divergent);
        assertTrue(result.clean(), "the retry pass reported problems: " + result.failures);
    }

    @Test
    @DisplayName("a row ALREADY at its deterministic id whose profileId is the number is "
            + "rewritten in place")
    void aRowAlreadyAtItsDeterministicIdIsNormalisedInPlace() {
        // Normalising only the copies left the fix conditional on the row being under a
        // legacy id: a row already at import_profile_definition:42 that stores the number
        // stays invisible to the type-strict Mango selector while every walk counts it, so
        // its update answers a permanent 503. A review found the gap.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("import_profile_definition:42", numeric, "3-c")));
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("import_profile_definition:42", written.getValue().document().getId());
        assertEquals("3-c", written.getValue().document().getRev(),
                "the rewrite is not conditional on the revision the row was READ at");
        assertEquals("42", written.getValue().document().get("profileId"),
                "the stored number was left in place, so the selector can never match the row");
        assertTrue(result.clean(), "the normalising pass reported problems: " + result.failures);
        // The counter, not just the write: a pass that ONLY normalises is otherwise summarised
        // by the startup patch as "no legacy rows", which reads as "nothing was touched" while
        // rows were written. A review found the summary unmeasured.
        assertEquals(1, result.normalised, "the rewrite was not counted: " + result);
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("which VALUES of \"enabled\" count as disabled is the mapper's answer — the "
            + "three spellings, blank, \"null\" yes; \"fAlSe\" and a stored number no")
    void whichValuesOfADisabledFlagCountIsTheMappersAnswer() {
        // The release notes name these values. They are the mapper's answer, not this code's,
        // so they are measured here through the production mapper rather than asserted from
        // knowledge: a row disabled by one of them is not a possible recipient, and one whose
        // enabled the mapper cannot read is reported instead. The values are built in the
        // shapes the read path really delivers (Gson types for numbers).
        wire();
        Map<String, Object> capitalised = profileProps("p-cap", "Capitalised");
        capitalised.put("enabled", "False");
        capitalised.put("retentionDays", "not-a-number");
        Map<String, Object> shouted = profileProps("p-shout", "Shouted");
        shouted.put("enabled", "FALSE");
        shouted.put("retentionDays", "not-a-number");
        Map<String, Object> lower = profileProps("p-lower", "Lower");
        lower.put("enabled", "false");
        lower.put("retentionDays", "not-a-number");
        Map<String, Object> empty = profileProps("p-empty", "Empty");
        empty.put("enabled", "");
        empty.put("retentionDays", "not-a-number");
        Map<String, Object> padded = profileProps("p-padded", "Padded");
        padded.put("enabled", "  false  ");
        padded.put("retentionDays", "not-a-number");
        Map<String, Object> blank = profileProps("p-blank-flag", "Blank");
        blank.put("enabled", "   ");
        blank.put("retentionDays", "not-a-number");
        Map<String, Object> textualNull = profileProps("p-null-text", "Null text");
        textualNull.put("enabled", "null");
        textualNull.put("retentionDays", "not-a-number");
        Map<String, Object> paddedNull = profileProps("p-null-padded", "Null padded");
        paddedNull.put("enabled", "  null  ");
        paddedNull.put("retentionDays", "not-a-number");
        // The shape a row really has when it comes back from CouchDB: the SDK builds the
        // properties with Gson, so a JSON number is a LazilyParsedNumber, which the mapper
        // REFUSES for a primitive boolean. A Java Integer would be coerced — measuring with
        // one made the release notes claim a numeric 0 counts as disabled, and this lock held
        // that claim green against a value the read path cannot produce. A review found the
        // substitution; the row is here to measure the refusal, on the production shape.
        Map<String, Object> zero = profileProps("p-zero", "Zero");
        zero.put("enabled", new com.google.gson.internal.LazilyParsedNumber("0"));
        zero.put("retentionDays", "not-a-number");
        Map<String, Object> oddSpelling = profileProps("p-odd-case", "Odd case");
        oddSpelling.put("enabled", "fAlSe");
        listingAnswers(List.of(
                row("import_profile_definition:p-cap", capitalised, "1-a"),
                row("import_profile_definition:p-shout", shouted, "1-c"),
                row("import_profile_definition:p-lower", lower, "1-d"),
                row("import_profile_definition:p-empty", empty, "1-e"),
                row("import_profile_definition:p-padded", padded, "1-f"),
                row("import_profile_definition:p-blank-flag", blank, "1-g"),
                row("import_profile_definition:p-null-text", textualNull, "1-h"),
                row("import_profile_definition:p-null-padded", paddedNull, "1-i"),
                row("import_profile_definition:p-zero", zero, "1-j"),
                row("import_profile_definition:p-odd-case", oddSpelling, "1-b")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(List.of("import_profile_definition:p-odd-case",
                        "import_profile_definition:p-zero"),
                owned.uninterpretable().stream()
                        .map(ImportProfileDefinitionService.UninterpretableRow::docId).sorted().toList(),
                "the mapper's readings are not the ones this code acts on — the rows here are "
                        + "\"false\"/\"False\"/\"FALSE\" (trimmed), an empty and a blank "
                        + "string, and \"null\" "
                        + "(trimmed), which must count as disabled, against \"fAlSe\" and a "
                        + "stored NUMBER, which must not: " + owned.uninterpretable());
        assertTrue(owned.profiles().isEmpty(),
                "a row the mapper cannot read as a profile was listed: " + owned.profiles());
    }

    @Test
    @DisplayName("a row at its deterministic id that carries ATTACHMENTS is not rewritten — "
            + "the binaries are not the migration's to destroy")
    void aRowWithAttachmentsIsNotRewrittenInPlace() {
        // getProperties() does not carry attachments, so writing the row back from them
        // deletes the binaries — and here the row IS the only holder, there is no copy to
        // lose them from. The copy path refuses exactly this bet; the in-place path was
        // making it, and would have reported a clean pass. Two reviews found it.
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        DocsResultRow withAttachment = row("import_profile_definition:42", numeric, "3-c");
        when(withAttachment.getDoc().getAttachments())
                .thenReturn(Map.of("evidence.pdf", mock(com.ibm.cloud.cloudant.v1.model.Attachment.class)));
        listingAnswers(List.of(withAttachment));
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        assertTrue(result.failures.stream().anyMatch(f -> f.contains("import_profile_definition:42")),
                "the row was left alone without saying so: " + result.failures);
        assertTrue(!result.clean(), "a pass that could not repair the row reported clean");
    }

    @Test
    @DisplayName("a foreign row occupying the deterministic id stays divergent — the identity "
            + "is read from each row, not assumed")
    void aForeignRowOnTheDeterministicIdIsStillDivergent() {
        // The normalised comparison substituted the profileId being migrated into BOTH sides,
        // so a row occupying import_profile_definition:42 while naming a DIFFERENT profile
        // compared equal to the legacy row — and the only row defining "42" was retired as
        // its duplicate, counted as a swept duplicate. A review caught it before first
        // contact. Each side is now read on its own.
        wire();
        Map<String, Object> legacy = profileProps("42", "Mine");
        legacy.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", legacy, "1-a")));
        Map<String, Object> foreign = profileProps("42", "Mine");
        foreign.put("profileId", "99");
        Document impostor = mock(Document.class);
        when(impostor.getProperties()).thenReturn(foreign);
        when(impostor.getRev()).thenReturn("2-b");
        deterministicReadAnswers("42", impostor);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertTrue(deletedIds.isEmpty(),
                "the only row defining \"42\" was retired as a duplicate of a row that names "
                        + "another profile: " + deletedIds);
        assertTrue(result.divergent.stream().anyMatch(d -> d.contains("42")),
                "the pair was not reported as divergent: " + result.divergent);
    }

    @Test
    @DisplayName("a rewrite the store refuses is reported, not swallowed")
    void aRefusedRewriteIsReported() {
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("import_profile_definition:42", numeric, "3-c")));
        when(cloudant.postDocument(any(PostDocumentOptions.class)))
                .thenThrow(new RuntimeException("conflict"));

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                assertDoesNotThrow(() -> service.migrateLegacyGeneratedIds(),
                        "a refused rewrite took the whole pass down");

        assertTrue(result.failures.stream().anyMatch(f -> f.contains("import_profile_definition:42")),
                "the refused rewrite was swallowed: " + result.failures);
        assertTrue(!result.clean(), "a pass with a refused rewrite reported clean");
    }

    @Test
    @DisplayName("a row whose repositoryId is not a string is not owned by anybody")
    void aRowWhoseRepositoryIdIsNotAStringIsNotOwned() {
        // The blank twin of this lock has been here since the value was widened to blank;
        // a value of any other shape names no repository either, and IDLE would otherwise
        // start a capture on a row no repository can manage.
        wire();
        Map<String, Object> odd = profileProps("p-odd", "Odd");
        odd.put("repositoryId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("odd-repo-row", odd, "1-a")));

        assertEquals(null, service.getOwnedRowIndexFree("p-odd"),
                "a row that names no repository was handed back as an owned one");
    }

    @Test
    @DisplayName("the owned listing skips a row whose repositoryId is not a string")
    void theOwnedListingSkipsARowWhoseRepositoryIdIsNotAString() {
        // The shared owned-row walk's half of the same reading: such a row is not a webhook
        // recipient and not a scheduled capture, exactly as a blank one is not.
        wire();
        Map<String, Object> odd = profileProps("p-odd", "Odd");
        odd.put("repositoryId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(
                row("import_profile_definition:p-fine", profileProps("p-fine", "Fine"), "1-a"),
                row("odd-repo-row", odd, "1-b")));

        ImportProfileDefinitionService.OwnedProfiles owned = service.listOwnedIndexFree();

        assertEquals(List.of("p-fine"),
                owned.profiles().stream().map(ImportProfileDefinition::getProfileId).toList(),
                "a row that names no repository was listed as owned");
        assertTrue(owned.uninterpretable().isEmpty(),
                "a row that names no repository was reported as a possible recipient: "
                        + owned.uninterpretable());
    }

    @Test
    @DisplayName("a row of ANOTHER repository is still refused by docId")
    void anotherRepositorysRowIsStillRefused() {
        // The exemption above is for rows with NO repositoryId. A row that names a different
        // repository must stay refused — otherwise the exemption becomes the cross-repository
        // delete the confinement exists to prevent.
        wire();
        Map<String, Object> theirs = profileProps("p-theirs", "Theirs");
        theirs.put("repositoryId", "canopy");
        Document row = mock(Document.class);
        when(row.getProperties()).thenReturn(theirs);
        when(row.getRev()).thenReturn("1-a");
        stubGetDocument("their-row", row);
        listingAnswers(List.of(row("their-row", theirs, "1-a")));
        writesSucceed();

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("p-theirs", "their-row", "bedroom"),
                "a caller of one repository deleted another repository's row");
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("a delete that removed SOME rows and then failed says so — and one that "
            + "removed none does not")
    void aPartlyFailedDeleteSaysHowMuchWasRemoved() {
        // Rows are deleted one at a time and CouchDB has no transaction across documents.
        // Two outcomes must not be confused: some rows gone (retry removes the rest) and
        // nothing gone (the store refused the operation — a retry does the same). The
        // controller-level locks mock this service away, so THIS is where the counting is
        // measured; a review found the controls pointing at those mocks.
        wire();
        DocsResultRow first = row("import_profile_definition:p-half",
                profileProps("p-half", "Half"), "4-r");
        DocsResultRow second = row("legacy-half", profileProps("p-half", "Half (old)"), "2-r");
        listingAnswers(List.of(first, second));
        writesSucceed();
        java.util.concurrent.atomic.AtomicInteger deletes = new java.util.concurrent.atomic.AtomicInteger();
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenAnswer(inv -> {
            ServiceCall<DocumentResult> call = mock(ServiceCall.class);
            if (deletes.incrementAndGet() > 1) {
                when(call.execute()).thenThrow(new RuntimeException("conflict"));
            } else {
                Response<DocumentResult> ok = mock(Response.class);
                DocumentResult result = mock(DocumentResult.class);
                when(result.isOk()).thenReturn(true);
                when(ok.getResult()).thenReturn(result);
                when(call.execute()).thenReturn(ok);
            }
            return call;
        });

        ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException partly = assertThrows(ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException.class, () -> service.delete("p-half", "bedroom"),
                "a delete that had already removed a row reported an ordinary failure — "
                        + "the caller reads it as 'nothing happened'");
        assertTrue(partly.getMessage().contains("1 of 2"),
                "the refusal does not say what was removed: " + partly.getMessage());
    }

    @Test
    @DisplayName("a delete that removed NOTHING is not reported as a partial deletion")
    void aDeleteThatRemovedNothingIsNotPartial() {
        // Over-throwing's twin: telling a caller to retry an operation the store refused
        // outright is a retry loop that can never succeed.
        wire();
        DocsResultRow first = row("import_profile_definition:p-half",
                profileProps("p-half", "Half"), "4-r");
        DocsResultRow second = row("legacy-half", profileProps("p-half", "Half (old)"), "2-r");
        listingAnswers(List.of(first, second));
        writesSucceed();
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenAnswer(inv -> {
            ServiceCall<DocumentResult> call = mock(ServiceCall.class);
            when(call.execute()).thenThrow(new RuntimeException("forbidden"));
            return call;
        });

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service.delete("p-half", "bedroom"));
        assertTrue(!(refused instanceof ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException),
                "a delete that removed nothing was reported as partly deleted, so the caller "
                        + "is told to retry an operation the store refused: " + refused);
    }

    @Test
    @DisplayName("the plain delete refuses when a row cannot be read — not a partial success")
    void thePlainDeleteRefusesAnUnreadableRow() {
        wire();
        listingAnswers(List.of(row(null, null, null),
                row("import_profile_definition:p-x", profileProps("p-x", "X"), "1-r")));
        writesSucceed();

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.delete("p-x", "bedroom"),
                "a delete that could not read every row reported success");
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    // ────────────────────────────────────────────────────────────────────
    // Runtime auto-resolution reads through the walk
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findDefaultForRepository finds the default the selector hides")
    void theAutoResolverSeesAHiddenDefault() {
        // This decides WHERE ingested content lands. A selector hiding the intended default
        // while a fallback stayed visible sent content under the wrong profile during an
        // index rebuild — silently, with a successful import. A round-2 review named it.
        wire();
        selectorAnswersNothing();
        Map<String, Object> hiddenDefault = defaultProfileProps("p-real-default");
        hiddenDefault.put("targetFolderId", "root-1");
        listingAnswers(List.of(row("import_profile_definition:p-real-default", hiddenDefault, "1-r")));

        ImportProfileDefinition resolved = service.findDefaultForRepository("bedroom",
                SourceArchetype.FILE_SHARE, null);

        assertTrue(resolved != null && "p-real-default".equals(resolved.getProfileId()),
                "the default the selector could not show was not resolved: " + resolved);
    }

    @Test
    @DisplayName("the default-profile patch does not trust the selector alone")
    void theDefaultProfilePatchDoesNotTrustTheSelectorAlone() throws Exception {
        // exists() is a selector. While its index rebuilds it answers "no" for the row that
        // is there; the create is then refused by the index-free count and the patch logs
        // a WARN on every such startup for a profile that exists. A review found the WARN.
        String patch = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/patch/Patch_DefaultCloudDriveConnectorProfile.java"));
        assertTrue(patch.contains("existsIndexFree(profileId, repositoryId)"),
                "the default-profile patch decides existence from the selector alone again");
    }

    @Test
    @DisplayName("the ingest entry point maps the retryable profile refusal — not an "
            + "unexplained failure")
    void theIngestEntryPointMapsTheRetryableRefusal() throws Exception {
        // findDefaultForRepository decides where an import lands and can now refuse
        // retryably; its only caller caught IllegalStateException alone, so the refusal
        // escaped to CloudDriveResource's generic catch as an unexplained failure. A
        // round-3 review found it unmapped; the ledger had already claimed it mapped.
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java"));
        String body = JavaSource.methodBody(source, "public ExternalIngestResult executeWithAutoResolve(");
        assertTrue(body.contains("catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException"),
                "the ingest entry point no longer maps the retryable profile refusal");
        assertTrue(body.contains("retry shortly"),
                "the refusal is mapped but does not tell the caller to retry: " + body);
    }

    @Test
    @DisplayName("a legacy row the migration could NOT rewrite is still the default")
    void aLegacyRowTheMigrationCouldNotRewriteIsStillTheDefault() {
        // The ranged walk cannot see generated-id rows, and NOTHING can prove none exist:
        // a rebuilding index answers "no rows" for rows that are there, which is the very
        // window §62 is about. The first version consulted the index additively and treated
        // a failed or short answer as absence — the batch's own defect, one layer up. Only a
        // CLEAN migration pass establishes that every row is under a deterministic id.
        wire();
        selectorAnswersNothing();
        Map<String, Object> legacyDefault = defaultProfileProps("p-legacy");
        legacyDefault.put("targetFolderId", "root-legacy");
        DocsResultRow legacy = row("generated-7", legacyDefault, "1-r");
        // Attachments: the migration refuses to copy such a row and leaves it in place, so
        // the pass is NOT clean and the legacy row is still a definition of this repository.
        when(legacy.getDoc().getAttachments()).thenReturn(
                Map.of("scan.pdf", mock(com.ibm.cloud.cloudant.v1.model.Attachment.class)));
        listingAnswers(List.of(legacy));

        assertTrue(!service.migrateLegacyGeneratedIds().clean(),
                "the fixture no longer produces an unclean pass");

        ImportProfileDefinition resolved = service.findDefaultForRepository("bedroom",
                SourceArchetype.FILE_SHARE, null);

        assertTrue(resolved != null && "p-legacy".equals(resolved.getProfileId()),
                "the legacy default was not a candidate, so content lands under whatever "
                        + "deterministic row was left; resolved to: "
                        + (resolved == null ? null : resolved.getProfileId()));
    }

    @Test
    @DisplayName("a deterministic row the index cannot show is still found by get()")
    void aDeterministicRowTheIndexCannotShowIsStillFoundByGet() {
        // get() is the selector. The import path resolves the profile index-free and then
        // looks it up again through get(); a rebuilding index turned the profile just
        // established into "Import profile not found". A review caught the round trip.
        wire();
        selectorAnswersNothing();
        Document stored = mock(Document.class);
        when(stored.getProperties()).thenReturn(profileProps("p-hidden-det", "Hidden"));
        deterministicReadAnswers("p-hidden-det", stored);

        ImportProfileDefinition found = service.get("p-hidden-det");

        assertTrue(found != null && "p-hidden-det".equals(found.getProfileId()),
                "a row under its deterministic id read as absent: " + found);
    }

    @Test
    @DisplayName("two legacy rows for one profileId are BOTH left standing")
    void twoLegacyRowsForOneIdAreBothLeftStanding() {
        // Migrating during the walk made the FIRST row encountered canonical and reported
        // only the second as divergent — enumeration order choosing which configuration
        // wins, while the release notes promise neither row is touched. A review caught it.
        wire();
        selectorAnswersNothing();
        Map<String, Object> a = profileProps("p-dup", "A");
        Map<String, Object> b = profileProps("p-dup", "B");
        listingAnswers(List.of(row("generated-a", a, "1-a"), row("generated-b", b, "1-b")));
        writesSucceed();

        var result = service.migrateLegacyGeneratedIds();

        assertEquals(0, result.migrated, "one of two legacy rows was made canonical by "
                + "nothing but enumeration order");
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        assertTrue(result.divergent.stream().anyMatch(d -> d.contains("p-dup")),
                "the pair was not reported: " + result.divergent);
        assertTrue(!result.clean(), "a pass that left two rows standing reported clean");
    }

    @Test
    @DisplayName("a CREATE whose selector out-reports the scan refuses too — not only UPDATE")
    void aCreateWhoseSelectorOutReportsTheWalkRefuses() {
        // The disagreement arm covers both verbs. With only the update measured, narrowing
        // the guard to updates would leave every lock green while a create adopted the row
        // the walk says is not there. A review named the gap.
        wire();
        selectorAnswersNothingThenShows(row("import_profile_definition:p-ghost2",
                profileProps("p-ghost2", "Ghost"), "1-a"));
        listingAnswers(List.of());
        writesSucceed();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused =
                assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                        () -> service.create(validProfile("p-ghost2")),
                        "the create wrote to a row the index-free scan says is not there");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the import path does not report a profile it just resolved as missing")
    void theIngestPathDoesNotReportAResolvedProfileAsMissing() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java"));
        // The check lives in execute(), not in executeWithAutoResolve: a pre-check there
        // only moved the window, because execute() reads the profile again and EVERY caller
        // of execute() passes through that read. A review pointed that out.
        String body = JavaSource.methodBody(source,
                "CaptureScope captureScope, BeforeEmitHook beforeEmitHook)");
        assertTrue(body.contains("importProfileDefinitionService.existsIndexFree("),
                "\"Import profile not found\" is answered from a selector read alone, so a "
                        + "rebuilding index reports a profile that IS there as absent: " + body);
        assertTrue(body.contains("retry shortly"),
                "the refusal does not tell the caller to retry: " + body);
    }

    @Test
    @DisplayName("a broken index does not refuse the auto-resolver — it reads no index at all")
    void aBrokenIndexDoesNotRefuseTheAutoResolver() {
        // Over-throwing is the twin defect. Auto-resolution is index-free end to end; an
        // index that cannot answer must not stop an import whose rows were all read.
        wire();
        when(cloudant.postFind(any())).thenThrow(new RuntimeException("no usable index"));
        Map<String, Object> props = defaultProfileProps("p-x");
        props.put("targetFolderId", "root-A");
        listingAnswers(List.of(row("import_profile_definition:p-x", props, "1-d")));

        ImportProfileDefinition resolved = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "a failing index refused an import that never needed it");
        assertTrue(resolved != null && "p-x".equals(resolved.getProfileId()),
                "resolved to: " + resolved);
    }

    @Test
    @DisplayName("findDefaultForRepository refuses retryably on an unreadable row")
    void theAutoResolverRefusesAnUnreadableRow() {
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row(null, null, null)));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "an unreadable row resolved to whatever was visible instead of refusing");
    }

    @Test
    @DisplayName("a deserialisable row with NO profileId refuses the uniqueness listing — "
            + "not a 500")
    void aRowWithoutAProfileIdRefusesTheListing() {
        // The comparison dereferenced other.getProfileId() and answered 500 for a row the
        // model accepts with a null identity. A round-2 review found it.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-new2", null);
        Map<String, Object> noId = profileProps("p-x", "X");
        noId.remove("profileId");
        listingAnswers(List.of(row("nameless-row", noId, "1-r")));
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(defaultProfile("p-new2")),
                "a row with no profileId reached the comparison and blew up as a 500");
        assertTrue(refused.getMessage().contains("no usable profileId"),
                "refused by some other guard: " + refused.getMessage());
    }

    // ────────────────────────────────────────────────────────────────────
    // The divergent-twin resolver
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubGetDocument(String docId, Document doc) {
        when(cloudant.getDocument(any(GetDocumentOptions.class))).thenAnswer(inv -> {
            GetDocumentOptions options = inv.getArgument(0);
            ServiceCall<Document> call = mock(ServiceCall.class);
            if (docId.equals(options.docId()) && doc != null) {
                Response<Document> response = mock(Response.class);
                when(response.getResult()).thenReturn(doc);
                when(call.execute()).thenReturn(response);
            } else {
                when(call.execute()).thenThrow(
                        mock(com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
            }
            return call;
        });
    }

    @Test
    @DisplayName("the one-row delete removes exactly the addressed row")
    void theOneRowDeleteRemovesTheAddressedRow() {
        wire();
        Document legacyRow = mock(Document.class);
        when(legacyRow.getProperties()).thenReturn(profileProps("p-twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        // The resolver now counts the rows first: it refuses to remove the LAST one, because
        // that would delete the profile through a path that skips the scheduler stop and the
        // deletion record. Two rows, so this really is a divergent pair.
        listingAnswers(List.of(row("legacy-abc", profileProps("p-twin", "Twin"), "7-r"),
                row("import_profile_definition:p-twin", profileProps("p-twin", "Twin"), "1-d")));
        writesSucceed();

        service.delete("p-twin", "legacy-abc", "bedroom");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("legacy-abc", deleted.getValue().docId());
        assertEquals("7-r", deleted.getValue().rev());
    }

    @Test
    @DisplayName("the one-row delete refuses a row that defines a DIFFERENT profile")
    void theOneRowDeleteRefusesAMismatchedRow() {
        wire();
        Document foreignRow = mock(Document.class);
        when(foreignRow.getProperties()).thenReturn(profileProps("someone-else", "Other"));
        when(foreignRow.getRev()).thenReturn("2-r");
        stubGetDocument("foreign-row", foreignRow);
        // For the control: with the verification narrowed, the flow reaches the count and
        // then the delete — so the count needs rows to see, or the control would fire on an
        // unanswered listing instead of on the claim. A review caught the new walk making
        // these three fire for the wrong reason.
        listingAnswers(List.of(row("row-a", profileProps("p-twin", "Twin"), "7-r"),
                row("row-b", profileProps("p-twin", "Twin"), "1-d")));
        writesSucceed();

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("p-twin", "foreign-row", "bedroom"));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("the one-row delete refuses a row that does not exist")
    void theOneRowDeleteRefusesAMissingRow() {
        wire();
        stubGetDocument("gone-row", null);

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("p-twin", "gone-row", "bedroom"));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("the one-row delete refuses a row of ANOTHER repository — the addressed "
            + "row is authorised, not the twin the selector happened to return")
    void theOneRowDeleteRefusesAnotherRepositorysRow() {
        // The P1 a review found before first contact: the controller authorises against
        // get(profileId) — whichever twin the selector returns first — while the delete
        // addresses docId. Divergent twins can differ in repositoryId, so a caller
        // authorised to repository A could delete repository B's row. The repository is
        // therefore checked HERE, on the row actually being deleted.
        wire();
        Document otherRepoRow = mock(Document.class);
        Map<String, Object> props = profileProps("p-twin", "Twin");
        props.put("repositoryId", "canopy");
        when(otherRepoRow.getProperties()).thenReturn(props);
        when(otherRepoRow.getRev()).thenReturn("3-r");
        stubGetDocument("canopy-row", otherRepoRow);
        // The narrowing controls make the flow reach the COUNT and then the delete, so the
        // count needs rows to see — otherwise the control fires on an unanswered listing
        // instead of on the claim. A review caught the new walk changing why they fire.
        listingAnswers(List.of(row("row-a", profileProps("p-twin", "Twin"), "7-r"),
                row("row-b", profileProps("p-twin", "Twin"), "1-d")));
        writesSucceed();

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("p-twin", "canopy-row", "bedroom"),
                "a row of another repository was deleted through the resolver");
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("when no tombstone explains the 409, the failure is reported as before")
    @SuppressWarnings("unchecked")
    void aNonTombstone409IsStillAFailure() {
        wire();
        listingAnswers(List.of(row("c-legacy", profileProps("p-raced", "Raced"), "1-r")));
        deterministicReadAnswers("p-raced", null);
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        when(postCall.execute()).thenThrow(new RuntimeException("409 document update conflict"));
        when(cloudant.postDocument(any(PostDocumentOptions.class))).thenReturn(postCall);
        when(wrapper.purgeTombstone("import_profile_definition:p-raced")).thenReturn(false);

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(0, result.migrated);
        assertEquals(1, result.failures.size());
    }

    @Test
    @DisplayName("the id read distinguishes NotFound from a failure")
    void theIdReadOnlyCatchesNotFound() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java"));
        String body = JavaSource.methodBody(source,
                "private com.ibm.cloud.cloudant.v1.model.Document readByDeterministicId(");
        assertTrue(body.contains("catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e)"),
                "the id read catches more than NotFound, so a failed read is reported as "
                        + "'no such profile' — inside the gate: " + body);
        assertTrue(body.contains("return null;"),
                "NotFound must still answer null, or a profile that genuinely does not "
                        + "exist could never be created: " + body);
    }

    // ────────────────────────────────────────────────────────────────────
    // Uniqueness validation reads through the walk — a hidden default is still a default
    // ────────────────────────────────────────────────────────────────────

    private static ImportProfileDefinition defaultProfile(String profileId) {
        ImportProfileDefinition def = validProfile(profileId);
        def.setDefaultProfile(true);
        return def;
    }

    private static Map<String, Object> defaultProfileProps(String profileId) {
        Map<String, Object> props = profileProps(profileId, "Default " + profileId);
        props.put("defaultProfile", true);
        props.put("enabled", true);
        return props;
    }

    @Test
    @DisplayName("a second default profile is refused even when the first is HIDDEN from "
            + "the selector")
    void aHiddenDefaultProfileStillBlocksASecondDefault() {
        // The auto-resolution rule ("one default profile per repository") was checked
        // against listByRepository — a selector. While its index rebuilt it saw nothing,
        // the second default went through, and findDefaultForRepository threw ambiguity
        // once the index recovered. The profileId scan does not look at this field, so the
        // door had to be closed on its own; a review found it after the first closure.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-second-default", null);
        listingAnswers(List.of(row("hidden-default", defaultProfileProps("p-first-default"),
                "1-r")));
        writesSucceed();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.create(defaultProfile("p-second-default")),
                "a second default profile was created beside one the selector could not "
                        + "show — auto-resolution is now ambiguous");
        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("default"),
                "refused by some other rule: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the uniqueness listing refuses a row it cannot read — create 400, update 503")
    void anUnreadableRowRefusesTheUniquenessListing() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-uniq", null);
        listingAnswers(List.of(row(null, null, null)));
        writesSucceed();

        assertThrows(IllegalStateException.class, () -> service.create(defaultProfile("p-uniq")),
                "a uniqueness rule checked against a listing that silently dropped a row "
                        + "is not a rule");
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.update(defaultProfile("p-uniq")),
                "the same unreadable listing on UPDATE escaped as something the controller "
                        + "answers 500 for");
    }

    @Test
    @DisplayName("the uniqueness listing still lets an ordinary default through — the control")
    void anOrdinaryDefaultProfileStillCreates() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-only-default", null);
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        listingAnswers(List.of(row("config-1", config, "1-a")));
        writesSucceed();

        ImportProfileDefinition created = service.create(defaultProfile("p-only-default"));

        assertEquals("p-only-default", created.getProfileId());
        verify(cloudant).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the uniqueness listing reads a numeric profileId as the mapper reads it — "
            + "\"42\" is an identity, not 'no usable profileId'")
    void aNumericProfileIdIsAnIdentityForTheUniquenessListing() {
        // The listing's identity check was hand-rolled (a String, or nothing) while the rule's
        // fields are read by the mapper, which coerces 42 to "42": a row the rule could read
        // refused every write of its repository over an identity it had. A review found the
        // class of disagreement; the check now asks the mapper.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-beside-42", null);
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("import_profile_definition:42", numeric, "1-a")));
        writesSucceed();

        ImportProfileDefinition created = assertDoesNotThrow(
                () -> service.create(defaultProfile("p-beside-42")),
                "a profileId the mapper reads as \"42\" was refused as 'no usable profileId'");

        assertEquals("p-beside-42", created.getProfileId());
        verify(cloudant).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a legacy row with a numeric profileId is a twin for the create of \"42\" — the "
            + "count reads identity the way the listing does")
    void aNumericLegacyRowIsATwinForTheCreateOfItsStringId() {
        // The uniqueness listing reads 42 as "42" (above); the count that stops a create from
        // writing a second row under the deterministic id compared the raw value —
        // "42".equals(42) is false — so the legacy row was invisible to it and the twin was
        // written. A parallel review found the two halves disagreeing the moment the
        // listing's reading changed; every identity comparison now asks the mapper.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("42", null);
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(defaultProfile("42")),
                "a second row of profile \"42\" was written beside the legacy row whose "
                        + "profileId is the number 42");
        assertTrue(refused.getMessage().contains("already exists"),
                "refused by some other rule: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("getForRepository finds a legacy row by the identity the mapper reads — \"42\" "
            + "for the number 42")
    void getForRepositoryReadsANumericProfileIdAsTheMapperReadsIt() {
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));

        ImportProfileDefinition found = service.getForRepository("42", "bedroom");

        assertTrue(found != null && "42".equals(found.getProfileId()),
                "the row the listing calls \"42\" could not be looked up by that id: " + found);
    }

    @Test
    @DisplayName("getOwnedRowIndexFree finds a legacy row by the identity the mapper reads")
    void getOwnedRowIndexFreeReadsANumericProfileIdAsTheMapperReadsIt() {
        wire();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        listingAnswers(List.of(row("legacy-42", numeric, "1-a")));

        ImportProfileDefinition found = service.getOwnedRowIndexFree("42");

        assertTrue(found != null && "42".equals(found.getProfileId()),
                "the row the listing calls \"42\" could not be looked up by that id: " + found);
    }

    @Test
    @DisplayName("the deterministic-id read accepts a row whose profileId is the number — the "
            + "same identity the walks read")
    void theDeterministicIdReadAcceptsARowWhoseProfileIdIsTheNumber() {
        // get() confirms that the document under the deterministic id IS this profile before
        // answering it; that confirmation compared the raw value and disowned a row the
        // mapper reads as "42" — get() null, while every walk saw the row.
        wire();
        selectorAnswersNothing();
        Map<String, Object> numeric = profileProps("42", "Numeric");
        numeric.put("profileId", new com.google.gson.internal.LazilyParsedNumber("42"));
        Document underTheId = mock(Document.class);
        when(underTheId.getId()).thenReturn("import_profile_definition:42");
        when(underTheId.getRev()).thenReturn("1-a");
        when(underTheId.getProperties()).thenReturn(numeric);
        deterministicReadAnswers("42", underTheId);

        ImportProfileDefinition found = service.get("42");

        assertTrue(found != null && "42".equals(found.getProfileId()),
                "the row under the deterministic id of \"42\" was disowned: " + found);
    }

    // ────────────────────────────────────────────────────────────────────
    // The uniqueness rule reads only its own fields — a row broken elsewhere neither blocks
    // nor slips past
    // ────────────────────────────────────────────────────────────────────

    /** A row the rule can read (its four fields are fine) but this node cannot interpret. */
    private static Map<String, Object> brokenElsewhereProps(String profileId, boolean enabled,
            boolean defaultProfile) {
        Map<String, Object> props = profileProps(profileId, "Broken " + profileId);
        props.put("enabled", enabled);
        props.put("defaultProfile", defaultProfile);
        props.put("retentionDays", "not-a-number");
        return props;
    }

    @Test
    @DisplayName("a row the rule can still read does not block a create — the rest of the "
            + "row is not interpreted")
    void aRowTheRuleCanStillReadDoesNotBlockACreate() {
        // The listing deserialised the WHOLE row and refused on any it could not: one row
        // with a value this node cannot read (a newer node's, during a rolling upgrade, or a
        // corrupt one) stopped every create and update of its repository — 400 on create,
        // which called the caller's request invalid. The rule reads four fields; only
        // those are interpreted now.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-new-default", null);
        listingAnswers(List.of(row("import_profile_definition:p-broken",
                brokenElsewhereProps("p-broken", true, false), "1-a")));
        writesSucceed();

        // assertDoesNotThrow, not a bare call: the refusal under measurement is an exception,
        // and a test that dies on it is "harness broken" to the runner, not a firing.
        ImportProfileDefinition created = assertDoesNotThrow(
                () -> service.create(defaultProfile("p-new-default")),
                "a row the rule could read refused the create — the rest of the row was "
                        + "interpreted, and it did not need to be");

        assertEquals("p-new-default", created.getProfileId());
        verify(cloudant).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a row the rule can read still COUNTS for the rule — the broken rest does "
            + "not make it invisible")
    void aRowTheRuleCanReadStillCountsForTheRule() {
        // The twin of the test above, and the reason the fix is not "skip rows that do not
        // deserialise": that row is a second default, and dropping it is how a second default
        // gets past the rule.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("p-second-default", null);
        listingAnswers(List.of(row("import_profile_definition:p-broken",
                brokenElsewhereProps("p-broken", true, true), "1-a")));
        writesSucceed();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.create(defaultProfile("p-second-default")),
                "a second default profile was created beside one whose other fields could "
                        + "not be read — the rule skipped the row instead of reading its fields");
        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("default"),
                "refused by some other rule: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a DISABLED row the resolver cannot interpret does not refuse the "
            + "auto-resolution — it could not have been chosen")
    void aDisabledRowTheResolverCannotReadDoesNotRefuseTheResolve() {
        // findDefaultForRepository interprets whole rows (it returns one), and refused on
        // any it could not — so one disabled broken row answered 503 to every import of its
        // repository that named no profile. Every pass of the resolver requires enabled;
        // a row that says it is not can be excluded without reading the rest of it.
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(
                row("import_profile_definition:p-off-broken",
                        brokenElsewhereProps("p-off-broken", false, true), "1-a"),
                row("import_profile_definition:p-good", defaultProfileProps("p-good"), "1-b")));

        // assertDoesNotThrow, not a bare call: the refusal under measurement is an exception,
        // and a test that dies on it is "harness broken" to the runner, not a firing.
        ImportProfileDefinition resolved = assertDoesNotThrow(
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "a disabled row the node cannot read refused the whole resolution");

        assertTrue(resolved != null && "p-good".equals(resolved.getProfileId()),
                "the disabled row was chosen, or nothing was: "
                        + (resolved == null ? null : resolved.getProfileId()));
    }

    @Test
    @DisplayName("a row disabled by the STRING \"false\" that the resolver cannot interpret "
            + "does not refuse the auto-resolution either")
    void aDisabledByStringRowTheResolverCannotReadDoesNotRefuseTheResolve() {
        // The literal-only skip left the string to Jackson — which never runs on the row that
        // matters here, the one Jackson cannot read. The connector listing reads both shapes
        // as disabled; a review found the two listings disagreeing.
        wire();
        selectorAnswersNothing();
        Map<String, Object> offByString = profileProps("p-off-str-broken", "Off (string)");
        offByString.put("enabled", "false");
        offByString.put("retentionDays", "not-a-number");
        offByString.put("defaultProfile", true);
        listingAnswers(List.of(
                row("import_profile_definition:p-off-str-broken", offByString, "1-a"),
                row("import_profile_definition:p-good", defaultProfileProps("p-good"), "1-b")));

        ImportProfileDefinition resolved = assertDoesNotThrow(
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "a row disabled by the string \"false\" that the node cannot read refused the "
                        + "whole resolution");

        assertTrue(resolved != null && "p-good".equals(resolved.getProfileId()),
                "the disabled row was chosen, or nothing was: "
                        + (resolved == null ? null : resolved.getProfileId()));
    }

    @Test
    @DisplayName("a row disabled by an explicit NULL that the resolver cannot interpret does "
            + "not refuse the auto-resolution either — the mapper reads null into the primitive "
            + "as false")
    void aDisabledByNullRowTheResolverCannotReadDoesNotRefuseTheResolve() {
        // The hand-rolled check knew the literal and the string; the mapper — which reads the
        // readable rows this resolver compares against — reads an explicit null for the
        // primitive as false. A row it would never choose refused the whole resolution over
        // the shape of its "no". A review found it; the field is now read by the mapper.
        wire();
        selectorAnswersNothing();
        Map<String, Object> offByNull = profileProps("p-off-null-broken", "Off (null)");
        offByNull.put("enabled", null);
        offByNull.put("retentionDays", "not-a-number");
        offByNull.put("defaultProfile", true);
        listingAnswers(List.of(
                row("import_profile_definition:p-off-null-broken", offByNull, "1-a"),
                row("import_profile_definition:p-good", defaultProfileProps("p-good"), "1-b")));

        ImportProfileDefinition resolved = assertDoesNotThrow(
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "a row disabled by an explicit null that the node cannot read refused the "
                        + "whole resolution");

        assertTrue(resolved != null && "p-good".equals(resolved.getProfileId()),
                "the disabled row was chosen, or nothing was: "
                        + (resolved == null ? null : resolved.getProfileId()));
    }

    @Test
    @DisplayName("an ENABLED row the resolver cannot interpret still refuses — it might have "
            + "been the one")
    void anEnabledRowTheResolverCannotInterpretStillRefuses() {
        // The control for the exclusion above: an enabled row the node cannot read may be
        // the default, and resolving to whatever else was readable sends content under the
        // wrong profile with a successful import.
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(
                row("import_profile_definition:p-on-broken",
                        brokenElsewhereProps("p-on-broken", true, true), "1-a"),
                row("import_profile_definition:p-good", defaultProfileProps("p-good"), "1-b")));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, null),
                "an enabled row the node cannot read was skipped and the resolution answered "
                        + "with whatever else was readable");
    }

    // ────────────────────────────────────────────────────────────────────
    // existsIndexFree — what the controllers ask before saying 404
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existsIndexFree sees a legacy row the selector cannot, and refuses "
            + "unreadable rows rather than answering either way")
    void existsIndexFreeSeesHiddenRowsAndRefusesUnreadableOnes() {
        wire();
        listingAnswers(List.of(row("legacy-x", profileProps("cloud-import-bedroom", "X"), "1-r")));
        assertTrue(service.existsIndexFree("cloud-import-bedroom", "bedroom"),
                "a legacy row the selector cannot show read as absent — the controller "
                        + "then answers 404 for a profile that exists");
        // Confined to the caller's repository: the same hidden row must NOT turn another
        // repository's 404 into a 503 — that discloses the row exists. A round-2 review
        // found the gate repository-blind.
        assertFalse(service.existsIndexFree("cloud-import-bedroom", "canopy"),
                "a row hidden in bedroom made canopy's request read as 'retry' — an "
                        + "existence disclosure across repositories");

        listingAnswers(List.of(row("config-1", Map.of("type", "configuration"), "1-a")));
        assertFalse(service.existsIndexFree("nobody", "bedroom"),
                "a profile no index-free read can find must still read as absent, or "
                        + "every 404 becomes a 503");

        listingAnswers(List.of(row(null, null, null)));
        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.existsIndexFree("murky", "bedroom"),
                "an unreadable row answered 'absent' or 'present' instead of 'retry'");
    }

    @Test
    @DisplayName("getOwnedRowIndexFree sees a hidden owned row and ignores an unowned leftover")
    void getOwnedRowIndexFreeSeesAHiddenOwnedRow() {
        wire();
        listingAnswers(List.of(row("legacy-owned", profileProps("p-idle", "Hidden"), "1-r")));
        ImportProfileDefinition found = service.getOwnedRowIndexFree("p-idle");
        assertEquals("p-idle", found == null ? null : found.getProfileId(),
                "a hidden owned row the selector cannot show was invisible to IDLE");
        assertEquals("bedroom", found == null ? null : found.getRepositoryId());

        Map<String, Object> unowned = profileProps("p-idle-none", "None");
        unowned.remove("repositoryId");
        listingAnswers(List.of(row("generated-unowned", unowned, "1-a")));
        assertEquals(null, service.getOwnedRowIndexFree("p-idle-none"),
                "an unowned leftover was treated as a startable profile");
    }

    // ────────────────────────────────────────────────────────────────────
    // The wiring that makes the closure real
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the startup patch's quiet summary counts a normalising pass as work done")
    void thePatchSummaryDoesNotCallANormalisingPassEmpty() throws Exception {
        // The DEBUG arm says "no legacy rows". A pass that rewrote a stored identity DID
        // write, and summarising it that way was the one shape of this summary that reads as
        // "nothing was touched" while rows changed. Read from the source because the arm is a
        // log line: what has to hold is that the condition names the counter.
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/patch/Patch_ConnectorDefinitionDeterministicIds.java"));
        // Scoped to the method, not the file: a window wider than the thing it measures goes
        // green when the condition moves elsewhere. The sister lock below scopes the same way.
        String summary = JavaSource.methodBody(source, "private void reportPass(");
        assertTrue(summary.contains("result.migrated == 0 && result.sweptDuplicates == 0")
                        && summary.contains("result.normalised == 0"),
                "the quiet summary no longer counts normalised rows as work done");
    }

    @Test
    @DisplayName("the profile migration reads no view and no Mango selector, through the "
            + "SHARED walk")
    void theMigrationConsultsNoIndex() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionServiceImpl.java"));
        String walk = JavaSource.methodBody(source,
                "public ConnectorDefinitionService.LegacyIdMigrationResult migrateLegacyGeneratedIds()");
        String perRow = JavaSource.methodBody(source, "private void migrateOneLegacyRow(");
        String scan = JavaSource.methodBody(source, "private int countProfileRowsIndexFree(");
        assertTrue(walk.contains("NemakiConfAllDocs.forEachRow(")
                        && scan.contains("NemakiConfAllDocs.forEachRow("),
                "the profile migration or scan no longer uses the shared fail-closed walk — "
                        + "a private copy is where pagination defects come back on one side");
        for (String body : new String[] {walk, perRow, scan}) {
            assertFalse(body.contains("postFind(") || body.contains("findBySelector(")
                            || body.contains("queryView"),
                    "the profile migration consults an index that can be rebuilding: " + body);
        }
    }

    @Test
    @DisplayName("the startup patch runs the PROFILE half too, and the controller can carry "
            + "the retryable refusal and the resolver")
    void thePatchAndControllerCarryTheProfileHalf() throws Exception {
        String patch = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/patch/Patch_ConnectorDefinitionDeterministicIds.java"));
        // The call is a method reference inside runHalf(...) since the halves were isolated;
        // the first spelling of this assertion matched the earlier direct call and went red
        // on the healthy tree the moment the isolation landed.
        assertTrue(patch.contains("ImportProfileDefinitionService")
                        && patch.contains("ImportProfileDefinitionService.class).migrateLegacyGeneratedIds()"),
                "the startup patch no longer migrates import profiles — the legacy "
                        + "cloud-import-<repo> rows stay invisible to the duplicate check on "
                        + "every upgraded installation");

        String controller = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinitionController.java"));
        assertTrue(controller.contains("ProfileIndexNotReadyException")
                        && controller.contains("SERVICE_UNAVAILABLE"),
                "the profile controller does not map the retryable refusal, so it reaches "
                        + "the client as a 500");
        // Whitespace-normalised: the call wrapped onto two lines when it gained a return
        // value and this exact-string lock went red on a healthy tree. Three rounds have now
        // found a lock that spelled out production text and lost when production moved.
        String controllerFlat = controller.replaceAll("\\s+", " ");
        assertTrue(controllerFlat.contains(
                        "importProfileDefinitionService.delete(profileId, docId, authRepository(ctx))"),
                "the one-row delete is not reachable from the API with the caller's "
                        + "repository, so either the migration's divergent-twin instruction "
                        + "prescribes the impossible again or the addressed row is not "
                        + "authorised");
        assertTrue(controllerFlat.contains("getForRepository(profileId, authRepository(ctx))"),
                "the controller answers 404 from the selector alone again — a profile the "
                        + "index cannot show, or one that only has a legacy row, reads as "
                        + "absent one layer above the retryable refusal");
    }
}
