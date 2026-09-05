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
