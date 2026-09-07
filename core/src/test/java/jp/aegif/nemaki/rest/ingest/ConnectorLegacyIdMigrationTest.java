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

/**
 * The §62 migration: legacy generated-id connector rows are rewritten under their
 * deterministic ids without ever consulting an index that could be rebuilding.
 *
 * <h2>What the window is</h2>
 *
 * <p>A connector saved under a CouchDB-generated id is invisible to the id-addressed
 * duplicate check that guards creation, so "the Mango selector answers empty while its index
 * rebuilds" could produce a second definition — once per legacy row, and only on upgraded
 * installations (a fresh install creates deterministic ids from the start). The migration
 * closes it by moving each legacy row to {@code connector_definition:<connectorId>}: copy,
 * verify the copy exists, then retire the original at the revision it was read at.
 *
 * <p>Twins that DISAGREE are reported and left alone: the two rows are the damage §62
 * describes, and silently choosing a winner destroys the loser's configuration — the exact
 * loss the migration exists to prevent.
 */
class ConnectorLegacyIdMigrationTest {

    private Cloudant cloudant;
    private CloudantClientWrapper wrapper;
    private CloudantClientPool pool;
    private ConnectorDefinitionServiceImpl service;
    private final List<AllDocsResult> pages = new ArrayList<>();
    /** Ids this test's own delete removed: a later read must not be served them. */
    private final java.util.Set<String> deletedIds = new java.util.HashSet<>();

    @SuppressWarnings("unchecked")
    private void wire() {
        // A second wire() in one test starts from an empty queue: a page left over from the
        // first half would be served to the second half's walk and the test would measure
        // the wrong rows.
        pages.clear();
        deletedIds.clear();
        pool = mock(CloudantClientPool.class);
        wrapper = mock(CloudantClientWrapper.class);
        cloudant = mock(Cloudant.class);
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        when(wrapper.getClient()).thenReturn(cloudant);
        service = new ConnectorDefinitionServiceImpl();
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

        // The stub honours startKey/endKey, like the profile fixture: without it a ranged
        // walk was served rows outside its range and the locks that check the range measured
        // nothing. A review found that shape on the profile side one round earlier.
        when(cloudant.postAllDocs(any(PostAllDocsOptions.class))).thenAnswer(call -> {
            PostAllDocsOptions options = call.getArgument(0);
            ServiceCall<AllDocsResult> ranged = mock(ServiceCall.class);
            when(ranged.execute()).thenAnswer(exec -> {
                AllDocsResult next = pages.isEmpty() ? null : pages.remove(0);
                // within(...) reads and stubs mocks. Called INSIDE thenReturn(...) it runs
                // while the stubbing of response.getResult() is still open, and Mockito
                // aborts the whole class with UnfinishedStubbingException — every test that
                // walked the database was failing on that instead of on its own claim.
                AllDocsResult served = within(options, next);
                Response<AllDocsResult> response = mock(Response.class);
                when(response.getResult()).thenReturn(served);
                return response;
            });
            return ranged;
        });
    }

    /** The rows of {@code page} that a walk with these options would be served. */
    private AllDocsResult within(PostAllDocsOptions options, AllDocsResult page) {
        if (page == null || page.getRows() == null) {
            return page;
        }
        String from = options.startKey();
        String to = options.endKey();
        if (from == null && to == null && deletedIds.isEmpty()) {
            return page;
        }
        List<DocsResultRow> inRange = new ArrayList<>();
        for (DocsResultRow r : page.getRows()) {
            String id = r.getId();
            if (id == null) continue;
            if (deletedIds.contains(id)) continue;
            if (from != null && id.compareTo(from) < 0) continue;
            if (to != null && (Boolean.FALSE.equals(options.inclusiveEnd())
                    ? id.compareTo(to) >= 0 : id.compareTo(to) > 0)) continue;
            inRange.add(r);
        }
        AllDocsResult filtered = mock(AllDocsResult.class);
        when(filtered.getRows()).thenReturn(inRange);
        return filtered;
    }

    private void listingAnswers(List<DocsResultRow> rows) {
        AllDocsResult result = mock(AllDocsResult.class);
        when(result.getRows()).thenReturn(rows);
        pages.add(result);
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

    private static Map<String, Object> connectorProps(String connectorId, String displayName) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", ConnectorDefinition.DOC_TYPE);
        props.put("connectorId", connectorId);
        props.put("displayName", displayName);
        props.put("sourceSystem", "google");
        return props;
    }

    /** The deterministic-id read answers {@code doc} for {@code connectorId}, else 404. */
    @SuppressWarnings("unchecked")
    private void deterministicReadAnswers(String connectorId, Document doc) {
        String wantedId = ConnectorDefinition.DOC_TYPE + ":" + connectorId;
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

    @SuppressWarnings("unchecked")
    private void writesSucceed() {
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        Response<DocumentResult> postResponse = mock(Response.class);
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        when(postResponse.getResult()).thenReturn(ok);
        when(postCall.execute()).thenReturn(postResponse);
        when(cloudant.postDocument(any(PostDocumentOptions.class))).thenReturn(postCall);

        // A delete is visible to the reads that follow it, as a database's is: the post-delete
        // count was being served the row it had just removed.
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

    @Test
    @DisplayName("a legacy row is rewritten under its deterministic id, then retired")
    void aLegacyRowIsRewrittenUnderItsDeterministicId() {
        wire();
        listingAnswers(List.of(
                row("8f3a2b1c9d", connectorProps("google-drive-default", "Google Drive"), "3-r")));
        deterministicReadAnswers("google-drive-default", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("connector_definition:google-drive-default",
                written.getValue().document().getId(),
                "the copy was not written under the deterministic id, so the duplicate "
                        + "check still cannot see this connector");
        assertEquals("google-drive-default",
                written.getValue().document().getProperties().get("connectorId"),
                "the copy does not carry the original's content");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("8f3a2b1c9d", deleted.getValue().docId());
        assertEquals("3-r", deleted.getValue().rev(),
                "the retirement is not conditional on the revision the row was READ at, so "
                        + "a concurrent edit between the copy and the delete would vanish");

        assertEquals(1, result.migrated);
        assertTrue(result.clean(), "a clean migration reported problems: " + result);
    }

    @Test
    @DisplayName("an identical leftover twin — an interrupted earlier pass — is retired "
            + "without a new write")
    void anIdenticalLeftoverTwinIsRetired() {
        wire();
        Map<String, Object> props = connectorProps("box-default", "Box");
        listingAnswers(List.of(row("7c1d", props, "5-r")));
        Document deterministicTwin = mock(Document.class);
        when(deterministicTwin.getProperties()).thenReturn(new HashMap<>(props));
        deterministicReadAnswers("box-default", deterministicTwin);
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
        // This is the damage §62 describes, already done. The legacy row and the
        // deterministic row hold DIFFERENT configuration, and any automatic winner
        // silently destroys the loser's credentialRef / scope — the very loss the
        // migration exists to prevent. It reports at ERROR on every startup instead.
        wire();
        listingAnswers(List.of(row("2a9e", connectorProps("onedrive-default", "OneDrive"), "2-r")));
        Document divergent = mock(Document.class);
        when(divergent.getProperties())
                .thenReturn(connectorProps("onedrive-default", "OneDrive (edited)"));
        deterministicReadAnswers("onedrive-default", divergent);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.divergent.size(),
                "the disagreement was not reported: " + result);
        assertTrue(result.divergent.get(0).contains("onedrive-default"));
        assertFalse(result.clean(),
                "a migration that left a disagreement behind reported itself clean");
    }

    @Test
    @DisplayName("a retirement that conflicts is reported, not swallowed — the edit wins")
    void aConflictedRetirementIsReportedNotSwallowed() {
        wire();
        listingAnswers(List.of(row("4b8f", connectorProps("s3-default", "S3"), "1-r")));
        deterministicReadAnswers("s3-default", null);
        // the copy succeeds; the conditional delete conflicts (a concurrent edit moved
        // the legacy row past the revision this pass read)
        writesSucceed();
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class)))
                .thenThrow(new RuntimeException("409 document update conflict"));

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertEquals(0, result.migrated,
                "a migration whose retirement failed still counted itself migrated");
        assertEquals(1, result.failures.size(),
                "the conflicted retirement was swallowed: " + result);
        assertTrue(result.failures.get(0).contains("s3-default"));
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("a listing that does not answer refuses — it is not 'nothing to migrate'")
    void anUnansweredListingRefuses() {
        wire();
        pages.add(null);

        assertThrows(IllegalStateException.class, () -> service.migrateLegacyGeneratedIds(),
                "the enumeration did not answer and the migration reported itself complete "
                        + "— the failure-as-absence this migration exists to close, one "
                        + "layer up");
    }

    @Test
    @DisplayName("a row that cannot be classified is a loud failure, not a silent skip")
    void anUnclassifiableRowIsALoudFailure() {
        wire();
        listingAnswers(List.of(row("odd-row", null, null)));

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.failures.size(),
                "a row with no body was silently skipped — if it IS a legacy connector, it "
                        + "stays invisible to the duplicate check with nothing saying so");
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("deterministic rows, foreign docs and design docs are left alone — the control")
    void everythingElseIsLeftAlone() {
        wire();
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        config.put("key", "config_sso_oidc_enabled");
        listingAnswers(List.of(
                row("_design/_repo", null, null),
                row("config-1", config, "1-a"),
                row("connector_definition:google-drive-default",
                        connectorProps("google-drive-default", "Google Drive"), "9-z")));
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(0, result.migrated);
        assertTrue(result.clean(), "an already-clean database was reported dirty: " + result);
    }

    @Test
    @DisplayName("the walk pages past a full first page — a legacy row on page two is found")
    void theWalkPagesPastTheFirstPage() {
        wire();
        List<DocsResultRow> firstPage = new ArrayList<>();
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        for (int i = 0; i < ConnectorDefinitionServiceImpl.MIGRATION_PAGE; i++) {
            firstPage.add(row(String.format("config-%04d", i), config, "1-a"));
        }
        listingAnswers(firstPage);
        listingAnswers(List.of(
                row("zz-legacy", connectorProps("late-connector", "Late"), "2-b")));
        deterministicReadAnswers("late-connector", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostAllDocsOptions> listings =
                ArgumentCaptor.forClass(PostAllDocsOptions.class);
        verify(cloudant, org.mockito.Mockito.times(2)).postAllDocs(listings.capture());
        assertEquals("config-0199", listings.getAllValues().get(1).startKey(),
                "the second page does not continue from the last id seen, so rows between "
                        + "pages are skipped or re-read");
        // No server-side skip(1). The continuation key can be a row this walk just DELETED
        // (a migrated legacy row at the page boundary); CouchDB then starts at the first
        // key AFTER it and a server-side skip discards a LIVE row — a legacy connector at
        // position page+1 was silently missed and the pass reported clean. The re-served
        // case is dropped client-side by id instead.
        assertEquals(null, listings.getAllValues().get(1).skip(),
                "the walk skips server-side again, so a deletion at the page boundary "
                        + "swallows the first live row of the next page");
        assertEquals(1, result.migrated,
                "a legacy row past the first page was never reached — a database with more "
                        + "than one page of config rows silently keeps its window open");
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("the selector listing pages past a full first page — a connector on page two "
            + "is listed")
    void theSelectorListingPagesPastTheFirstPage() {
        // list() asked the selector for one page of 200 and returned it as the whole answer:
        // the 201st connector was never listed. The profile service's twin; locked on both
        // sides because "fixed one service, forgot the other" is the shape this batch keeps
        // producing.
        wire();
        List<Document> firstPage = new ArrayList<>();
        for (int i = 0; i < NemakiConfFind.PAGE; i++) {
            firstPage.add(selectorDoc(connectorProps(String.format("c-%04d", i), "Early")));
        }
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> first =
                findCallOf(firstPage, "page-2");
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> second =
                findCallOf(List.of(selectorDoc(connectorProps("c-late", "Late"))), "page-3");
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenAnswer(call -> {
                    com.ibm.cloud.cloudant.v1.model.PostFindOptions options = call.getArgument(0);
                    return "page-2".equals(options.bookmark()) ? second : first;
                });

        List<ConnectorDefinition> all = service.list();

        assertEquals(NemakiConfFind.PAGE + 1, all.size(),
                "the listing stopped at its first page — a connector past it is not listed");
        assertTrue(all.stream().anyMatch(c -> "c-late".equals(c.getConnectorId())),
                "the connector on page two is missing: " + all.size() + " listed");
    }

    @Test
    @DisplayName("a full selector page with no bookmark to continue from is refused with the "
            + "typed 503, not a bare IllegalStateException")
    void theSelectorListingRefusesAFullPageWithoutABookmark() {
        wire();
        List<Document> fullPage = new ArrayList<>();
        for (int i = 0; i < NemakiConfFind.PAGE; i++) {
            fullPage.add(selectorDoc(connectorProps(String.format("c-%04d", i), "Early")));
        }
        // Built BEFORE the stubbing (findCallOf stubs mocks of its own; inside thenReturn it
        // leaves this stubbing unfinished and the class dies on UnfinishedStubbingException).
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> noBookmark = findCallOf(fullPage, null);
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(noBookmark);

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.list(),
                "a full page with nothing to continue from was returned as complete, or "
                        + "refused with the type the create path answers 400 for");
    }

    /** One raw document as the selector serves it. */
    private static Document selectorDoc(Map<String, Object> props) {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(ConnectorDefinition.DOC_TYPE + ":" + props.get("connectorId"));
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
    @DisplayName("the migration reads no view and no Mango selector — the property that "
            + "makes running it UNGATED sound")
    void theMigrationConsultsNoIndex() throws Exception {
        // The §62 window opens while an index is rebuilding, so the migration must run
        // exactly then — which is only safe because everything it reads (_all_docs, an
        // id-addressed get) is answered by the primary index. A postFind or queryView
        // creeping in here would make the ungated always-run pattern the hole, and the
        // patch's own class comment would become the next false justification.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionServiceImpl.java"));
        // The walk was extracted to NemakiConfAllDocs when the import-profile service
        // needed it; the pager assertion follows it there.
        String confSource = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/NemakiConfAllDocs.java"));
        // The public walk delegates to one private loop (a ranged variant shared it for two
        // rounds and was then withdrawn; this lock was red on a healthy tree until a review
        // noticed it still read forEachRow's one-line body).
        String pager = jp.aegif.nemaki.util.test.JavaSource.methodBody(confSource,
                "private static void walk(");
        assertTrue(jp.aegif.nemaki.util.test.JavaSource.methodBody(confSource,
                        "static void forEachRow(").contains("walk("),
                "the public walk no longer runs through the one shared loop");
        String walk = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "public LegacyIdMigrationResult migrateLegacyGeneratedIds()");
        String perRow = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private void migrateOneLegacyRow(");
        String scan = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private int countConnectorRowsIndexFree(");
        assertTrue(pager.contains("postAllDocs("),
                "the shared walk no longer reads _all_docs — whatever replaced it, the "
                        + "burden is on it to answer while indexes rebuild: " + pager);
        assertTrue(walk.contains("NemakiConfAllDocs.forEachRow(")
                        && scan.contains("NemakiConfAllDocs.forEachRow("),
                "the migration and the create-scan no longer share the fail-closed walk — "
                        + "split copies are how the one-arm defects of this batch happened");
        for (String body : new String[] {pager, walk, perRow, scan}) {
            assertFalse(body.contains("postFind(") || body.contains("findBySelector(")
                            || body.contains("queryView"),
                    "the migration consults an index that can be rebuilding, which is the "
                            + "state it exists to run in: " + body);
        }

        String patch = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/patch/Patch_ConnectorDefinitionDeterministicIds.java"));
        for (String viewRead : new String[] {"cmisViewsAreAnswering", "isApplied(",
                "createPathHistory("}) {
            assertFalse(patch.contains(viewRead),
                    "the ungated patch performs the view-based call '" + viewRead + "' — "
                            + "the exact reads the gate exists to protect, now running "
                            + "ungated");
        }
    }

    @Test
    @DisplayName("a legacy row at the PAGE BOUNDARY is neither skipped nor migrated twice")
    void aBoundaryRowIsHandledExactlyOnce() {
        // The two failure modes of continuation, both found by review before first contact:
        // skip(1) against a key this walk just DELETED discards the first LIVE row of the
        // next page (a legacy connector there was silently missed and the pass reported
        // clean); no dedup at all processes a re-served continuation row twice. The fix is
        // startKey without skip plus a client-side id comparison — this drives the
        // re-served case, the paging test's skip assertion pins the deleted case.
        wire();
        List<DocsResultRow> firstPage = new ArrayList<>();
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        for (int i = 0; i < ConnectorDefinitionServiceImpl.MIGRATION_PAGE - 1; i++) {
            firstPage.add(row(String.format("config-%04d", i), config, "1-a"));
        }
        firstPage.add(row("m-legacy", connectorProps("edge-connector", "Edge"), "1-r"));
        listingAnswers(firstPage);
        listingAnswers(List.of(
                row("m-legacy", connectorProps("edge-connector", "Edge"), "1-r"),
                row("zz-config", config, "1-a")));
        deterministicReadAnswers("edge-connector", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, org.mockito.Mockito.times(1))
                .postDocument(any(PostDocumentOptions.class));
        verify(cloudant, org.mockito.Mockito.times(1))
                .deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.migrated,
                "the boundary row was processed twice or not at all: " + result);
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("the ingest entry point maps BOTH connector refusals — retryable and "
            + "ambiguous")
    void theIngestEntryPointMapsTheConnectorRefusals() throws Exception {
        // findBySystemAndArchetype decides WHICH connector an import uses and can now refuse
        // two ways; its caller caught neither, so both surfaced as unexplained failures. The
        // profile arm beside it had been mapped a round earlier — the same one-arm shape.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java"));
        String resolve = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "public ExternalIngestResult executeWithAutoResolve(");
        assertTrue(resolve.contains("catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException"),
                "the retryable connector refusal escapes the ingest entry point: " + resolve);
        assertTrue(resolve.contains("findBySystemsAndArchetype("),
                "the alias keys are resolved one walk per key again — a full walk of the "
                        + "config database per key: " + resolve);
        assertTrue(resolve.contains("connector resolution is"),
                "the refusal does not say what happened: " + resolve);
        String execute = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "CaptureScope captureScope, BeforeEmitHook beforeEmitHook)");
        assertTrue(execute.contains("connectorDefinitionService.existsIndexFree("),
                "\"Connector not found\" is answered from a selector read alone, so a "
                        + "rebuilding index reports a connector that IS there as absent");
    }

    @Test
    @DisplayName("every ingest entry point that says 'not found' asks index-free first")
    void everyIngestEntryPointAsksIndexFreeBeforeSayingNotFound() throws Exception {
        // The import service's split is not the whole story: the non-admin ingest gate and
        // the DLQ retry answer BEFORE it, and both used to report a rebuilding index as
        // absence. A review found them still open after the service was closed. Asserted on
        // the source because these are controllers with no unit fixture of their own; what
        // has to hold is that no "not found" is answered from an index-backed read alone.
        String gate = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestController.java"));
        assertTrue(gate.contains("existsIndexFree(profileId, repositoryId)")
                        && gate.contains("connectorHiddenOrAbsent("),
                "the non-admin ingest gate answers 'not found' from the index alone again");
        int applied = gate.split("HiddenOrAbsent\\(", -1).length - 1;
        assertTrue(applied >= 5,
                "one of the gate's three not-found branches stopped asking index-free "
                        + "(helpers + call sites seen: " + applied + ")");
        String dlq = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java"));
        assertTrue(dlq.contains("connectorDefinitionService.existsIndexFree("),
                "the DLQ retry answers 'connector not found' from the index alone again");
        String canonical = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java"));
        String mail = jp.aegif.nemaki.util.test.JavaSource.methodBody(canonical,
                "private ExternalIngestResult executeMailImportInternal(");
        assertTrue(mail.contains("profileHiddenOrAbsent(") && mail.contains("connectorHiddenOrAbsent("),
                "the mail entry point's early validation reports absence again: " + mail);
        assertTrue(mail.contains("resolveProfileForRepository("),
                "the mail entry point decides the profile from whichever row the selector "
                        + "returned, so a shared profileId ends in a repository mismatch for "
                        + "an import whose own repository has the profile: " + mail);
        String resolve = jp.aegif.nemaki.util.test.JavaSource.methodBody(canonical,
                "private ImportProfileDefinition resolveProfileForRepository(");
        assertTrue(resolve.contains("getForRepository("),
                "the shared resolver skipped the index-free walk, so a same-repository "
                        + "twin pair is chosen by selector order again: " + resolve);
        String folder = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/rest/ingest/FolderConnectorController.java"));
        assertTrue(!folder.contains("importProfileDefinitionService.get(profileId)"),
                "the folder run/credential paths answer 'no runnable profile' from the "
                        + "selector alone again");
    }

    @Test
    @DisplayName("auto-resolution finds a connector the selector cannot show")
    void theConnectorResolverSeesAHiddenConnector() {
        // WHICH connector an auto-resolved import uses was decided by a Mango selector: while
        // its index rebuilt it answered "no enabled connector found" for a connector that is
        // there, and the import was refused. The profile half was closed first; a review
        // refused "out of scope" for this one.
        wire();
        selectorAnswersNothing();
        Map<String, Object> props = connectorProps("box-1", "Box");
        props.put("enabled", true);
        props.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        listingAnswers(List.of(row("generated-box", props, "1-a")));

        ConnectorDefinition found = service.findBySystemAndArchetype("google",
                SourceArchetype.FILE_SHARE);

        assertTrue(found != null && "box-1".equals(found.getConnectorId()),
                "the connector the selector could not show was not resolved: " + found);
    }

    @Test
    @DisplayName("auto-resolution refuses when several connectors match — nobody picks by "
            + "storage order")
    void theConnectorResolverRefusesAnAmbiguousMatch() {
        wire();
        selectorAnswersNothing();
        Map<String, Object> a = connectorProps("box-1", "Box A");
        a.put("enabled", true); a.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        Map<String, Object> b = connectorProps("box-2", "Box B");
        b.put("enabled", true); b.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        listingAnswers(List.of(row("connector_definition:box-1", a, "1-a"),
                row("connector_definition:box-2", b, "1-b")));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE),
                "one of two matching connectors was chosen by nothing but row order");
        assertTrue(refused.getMessage().contains("Ambiguous auto-resolve"), refused.getMessage());

        // But NOT across alias keys: the key order is a preference the caller declares (the
        // spelling the request used, then its alias), and an installation may legitimately
        // hold one connector saved as "google" and another as "google_drive". Refusing that
        // pair would break a working configuration — the over-throw twin of this rule.
        Map<String, Object> alias = connectorProps("drive-1", "Drive");
        alias.put("enabled", true);
        alias.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        alias.put("sourceSystem", "google_drive");
        Map<String, Object> exact = connectorProps("box-1", "Box");
        exact.put("enabled", true);
        exact.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row("connector_definition:box-1", exact, "1-a"),
                row("connector_definition:drive-1", alias, "1-c")));
        ConnectorDefinition preferred = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.findBySystemsAndArchetype(List.of("google", "google_drive"),
                        SourceArchetype.FILE_SHARE),
                "a legitimate pair (one per alias spelling) was refused as ambiguous");
        assertTrue(preferred != null && "box-1".equals(preferred.getConnectorId()),
                "the caller's first key no longer wins: " + preferred);
    }

    @Test
    @DisplayName("an UNRELATED connector that cannot be read does not stop the resolution")
    void anUnrelatedUnreadableConnectorDoesNotStopTheResolution() {
        // Over-throwing: a newer node writing a newer sourceArchetype makes that row
        // undeserialisable on an older one. Refusing the whole resolution because of a
        // connector that could not have been the answer takes the whole ingest path down
        // during a rolling upgrade. A review named it.
        wire();
        selectorAnswersNothing();
        Map<String, Object> wanted = connectorProps("box-1", "Box");
        wanted.put("enabled", true);
        wanted.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        Map<String, Object> unrelated = connectorProps("future-1", "From a newer node");
        unrelated.put("enabled", true);
        unrelated.put("sourceSystem", "some_new_system");
        unrelated.put("sourceArchetype", "A_VALUE_THIS_VERSION_DOES_NOT_KNOW");
        // A DISABLED row that DOES match the request and cannot be read: it can be neither
        // the answer nor half of an ambiguity, so reading it cannot change the outcome —
        // and reading it was refusing every import. The other arm (a matching ENABLED
        // unreadable row) still refuses; that is the test below.
        // A row with NO sourceSystem at all: the filter compares against a List.of(...), and
        // List.of(...).contains(null) THROWS — so this row took the whole resolution down
        // with an NPE that nothing on the way out converts. A review caught the regression
        // the filter itself introduced.
        Map<String, Object> noSystem = connectorProps("headless-1", "No system");
        noSystem.remove("sourceSystem");
        noSystem.put("enabled", true);
        // The STRING "false", as a hand-written or legacy row carries it: Jackson reads it as
        // disabled, so skipping only the Boolean left this row able to refuse the whole
        // resolution by failing to deserialise. A review found the arm unmeasured.
        Map<String, Object> disabled = connectorProps("retired-1", "Retired");
        disabled.put("enabled", "false");
        disabled.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        disabled.put("allowedPrincipalIds", Map.of("not", "a list"));
        listingAnswers(List.of(row("connector_definition:box-1", wanted, "1-a"),
                row("connector_definition:future-1", unrelated, "1-b"),
                row("connector_definition:retired-1", disabled, "1-c"),
                row("connector_definition:headless-1", noSystem, "1-d")));

        ConnectorDefinition found = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE),
                "an unrelated row this version cannot read stopped the resolution");
        assertTrue(found != null && "box-1".equals(found.getConnectorId()),
                "resolved to: " + found);
    }

    @Test
    @DisplayName("a MATCHING connector that cannot be read still refuses")
    void aMatchingUnreadableConnectorStillRefuses() {
        // The other arm: a row whose system and archetype match could be the answer, so it
        // is not skippable. Fail-closed stays where it belongs.
        wire();
        selectorAnswersNothing();
        Map<String, Object> broken = connectorProps("box-1", "Box");
        broken.put("enabled", true);
        broken.put("sourceArchetype", SourceArchetype.FILE_SHARE.name());
        // A REAL field with a value that cannot be coerced. The first version used a field
        // name the class does not have, and @JsonIgnoreProperties(ignoreUnknown = true) made
        // it deserialise cleanly — the lock would have been red on a healthy tree because
        // nothing was thrown. Caught by tracing the fixture against the class.
        broken.put("allowedPrincipalIds", Map.of("not", "a list"));
        listingAnswers(List.of(row("connector_definition:box-1", broken, "1-a")));

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE),
                "a row that matches the request and cannot be read was skipped");
    }

    @Test
    @DisplayName("auto-resolution refuses retryably on a row it cannot read")
    void theConnectorResolverRefusesAnUnreadableRow() {
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row("no-body", null, null)));

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE),
                "a row that could not be read was answered as 'no such connector'");
    }

    @Test
    @DisplayName("existsIndexFree sees a hidden row and REFUSES an unreadable one")
    void existsIndexFreeSeesHiddenRowsAndRefusesUnreadableOnes() {
        // The profile twin of this lock existed from the start; the connector one did not,
        // and the implementation let the raw IllegalStateException through — so the
        // controller's 503 branch was dead code and an unreadable row answered 500.
        wire();
        listingAnswers(List.of(row("generated-1", connectorProps("hidden-one", "Hidden"), "1-a")));
        assertTrue(service.existsIndexFree("hidden-one"),
                "a row the selector cannot show was reported as absent");

        wire();
        listingAnswers(List.of(row("no-body", null, null)));
        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.existsIndexFree("hidden-one"),
                "a row that could not be classified was answered as 'no such connector'");
    }

    @Test
    @DisplayName("a re-served boundary row is COUNTED once — no phantom twin")
    void aBoundaryRowIsCountedExactlyOnce() {
        // The migration stopped measuring the walk's dedup when it moved to collect-then-act
        // (its map is keyed by row id, so a row served twice lands once whatever the walk
        // does). The dedup is still load-bearing for every OTHER consumer of the walk: the
        // count scan would see ONE row as two and refuse the write as a standing twin (409),
        // the uniqueness listing would report a false duplicate, and the delete would try to
        // remove the same row twice. A review found the lock measuring nothing.
        wire();
        List<DocsResultRow> firstPage = new ArrayList<>();
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        for (int i = 0; i < ConnectorDefinitionServiceImpl.MIGRATION_PAGE - 1; i++) {
            firstPage.add(row(String.format("config-%04d", i), config, "1-a"));
        }
        firstPage.add(row("connector_definition:edge", connectorProps("edge", "Edge"), "4-r"));
        listingAnswers(firstPage);
        listingAnswers(List.of(
                row("connector_definition:edge", connectorProps("edge", "Edge"), "4-r"),
                row("zz-config", config, "1-a")));
        selectorShows(row("connector_definition:edge", connectorProps("edge", "Edge"), "4-r"));
        writesSucceed();

        // One row defines "edge". Counted twice it becomes a standing pair and the update is
        // refused with 409 for a twin that does not exist.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.update(validDefinition("edge")),
                "the re-served boundary row was counted twice — a twin that does not exist");
        verify(cloudant, org.mockito.Mockito.times(1))
                .postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a row with NO id is a loud failure and does not stop the others")
    void aRowWithNoIdIsALoudFailure() {
        wire();
        listingAnswers(List.of(
                row(null, null, null),
                row("real-legacy", connectorProps("survivor", "Survivor"), "1-r")));
        deterministicReadAnswers("survivor", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertEquals(1, result.failures.size(),
                "an id-less row was silently skipped — if it IS a legacy connector, it "
                        + "stays invisible with nothing saying so: " + result);
        assertEquals(1, result.migrated,
                "one odd row stopped the rows after it from migrating");
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("a legacy row carrying ATTACHMENTS is refused, not migrated incompletely")
    void anAttachmentBearingRowIsRefused() {
        // getProperties() does not carry attachments, so the copy would silently drop them
        // and the retirement would destroy the only holder. A review caught this before
        // first contact; the row is reported and left in place.
        wire();
        Map<String, Object> props = connectorProps("filer", "Filer");
        DocsResultRow legacy = row("att-legacy", props, "2-r");
        when(legacy.getDoc().getAttachments()).thenReturn(Map.of("cert.pem",
                mock(com.ibm.cloud.cloudant.v1.model.Attachment.class)));
        listingAnswers(List.of(legacy));
        deterministicReadAnswers("filer", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(1, result.failures.size());
        assertTrue(result.failures.get(0).contains("attachments"),
                "the refusal does not say WHY the row was left: " + result.failures);
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("a copy blocked by a TOMBSTONE purges it and retries once")
    @SuppressWarnings("unchecked")
    void aTombstoneBlockedCopyIsPurgedAndRetried() {
        // A previously deleted deterministic id leaves a tombstone; CouchDB answers 409
        // for a create against it while the id-addressed read says "absent" (it reads
        // live documents). Without this arm the row retried on every startup for ever,
        // with a message that never named the cause.
        wire();
        listingAnswers(List.of(row("t-legacy", connectorProps("ghosted", "Ghosted"), "1-r")));
        deterministicReadAnswers("ghosted", null);

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
        when(wrapper.purgeTombstone("connector_definition:ghosted")).thenReturn(true);

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(wrapper).purgeTombstone("connector_definition:ghosted");
        verify(cloudant, org.mockito.Mockito.times(2))
                .postDocument(any(PostDocumentOptions.class));
        assertEquals(1, result.migrated,
                "the tombstone-blocked copy did not recover: " + result);
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("when no tombstone explains the 409, the failure is reported as before")
    @SuppressWarnings("unchecked")
    void aNonTombstone409IsStillAFailure() {
        // The boundary of the purge arm: purgeTombstone answers false when no tombstone
        // exists (a concurrent CREATION, say) — the purge must not be treated as having
        // fixed anything, and the row lands in failures for the next pass to re-examine.
        wire();
        listingAnswers(List.of(row("c-legacy", connectorProps("raced", "Raced"), "1-r")));
        deterministicReadAnswers("raced", null);
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        when(postCall.execute()).thenThrow(new RuntimeException("409 document update conflict"));
        when(cloudant.postDocument(any(PostDocumentOptions.class))).thenReturn(postCall);
        when(wrapper.purgeTombstone("connector_definition:raced")).thenReturn(false);

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
        assertEquals(0, result.migrated);
        assertEquals(1, result.failures.size());
        assertFalse(result.clean());
    }

    // ────────────────────────────────────────────────────────────────────
    // The index-free duplicate scan inside CREATE
    // ────────────────────────────────────────────────────────────────────

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

    private static ConnectorDefinition validDefinition(String connectorId) {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setConnectorId(connectorId);
        def.setDisplayName("New Connector");
        def.setSourceArchetype(SourceArchetype.FILE_SHARE);
        def.setSourceSystem("google");
        def.setAuthType("oauth2");
        def.setEnabled(true);
        return def;
    }

    @Test
    @DisplayName("a CREATE refuses when the index-free scan finds a legacy row the selector "
            + "and the deterministic id both missed")
    void aCreateRefusesWhenTheScanFindsALegacyRow() {
        // The residual §62 window, closed at the point of damage: the startup migration
        // usually removes legacy rows, but a create must not bet on the migration having
        // run or succeeded — a review showed the failed-pass path recreating the exact
        // divergent twin the migration exists to prevent. _all_docs cannot under-report.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("ghost", null);
        listingAnswers(List.of(row("old-gen-id", connectorProps("ghost", "Ghost"), "1-r")));
        // Unused on the healthy tree — the scan refuses first. Here for the control (PA):
        // with the scan removed, an unstubbed postDocument NPEs INSIDE assertThrows, which
        // JUnit wraps into an AssertionFailedError ("Unexpected exception type") — the
        // runner then scores a harness NPE as a clean firing. Stubbed, the sabotaged create
        // completes and assertThrows fails on "nothing was thrown": the lock's own claim.
        // The third occurrence of the ON-lesson in this batch; a convergence review traced
        // the laundering path through the runner's classifier before anything ran.
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validDefinition("ghost")),
                "the create wrote a second definition while a legacy row existed — the §62 "
                        + "duplicate, now from the CREATE side");
        assertTrue(refused.getMessage().contains("index-free"),
                "refused by some other guard than the scan: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a CREATE refuses when the scan cannot READ a row — uniqueness unprovable")
    void aCreateRefusesWhenTheScanCannotRead() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("newbie", null);
        listingAnswers(List.of(row(null, null, null)));
        // Same PA-shape stub as the sibling above, for symmetry under the sabotage.
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validDefinition("newbie")),
                "the scan skipped a row it could not read and blessed the create — a claim "
                        + "of uniqueness that skipped an unreadable row is not a claim");
        assertTrue(refused.getMessage().contains("cannot be established"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a CREATE still works when the scan finds nothing — the control")
    void aCreateStillWorksWhenTheScanFindsNothing() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("fresh", null);
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        listingAnswers(List.of(row("config-1", config, "1-a")));
        writesSucceed();

        ConnectorDefinition created = service.create(validDefinition("fresh"));

        assertEquals("fresh", created.getConnectorId());
        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("connector_definition:fresh", written.getValue().document().getId(),
                "the scan broke the ordinary create, or the deterministic id was lost");
    }

    @Test
    @DisplayName("a twin differing ONLY in storage fields (_id/_rev) is identical, not "
            + "divergent")
    void aTwinDifferingOnlyInStorageFieldsIsIdentical() {
        // findBySelector in the same class strips _id/_rev before mapping — recorded
        // evidence those keys can surface inside getProperties() — and the migration's
        // first comparison took the maps raw: two rows identical in every content field
        // then stood as DIVERGENT for ever, reported at ERROR on every startup, with the
        // prescribed resolution pointing at two rows that actually agree. The first
        // version of the identical-twin test used maps WITHOUT those keys, so it stayed
        // green over the defect. A review caught the asymmetry.
        wire();
        Map<String, Object> legacyProps = connectorProps("box-default", "Box");
        legacyProps.put("_id", "7c1d");
        legacyProps.put("_rev", "5-r");
        listingAnswers(List.of(row("7c1d", legacyProps, "5-r")));
        Map<String, Object> twinProps = connectorProps("box-default", "Box");
        twinProps.put("_id", "connector_definition:box-default");
        twinProps.put("_rev", "2-z");
        Document deterministicTwin = mock(Document.class);
        when(deterministicTwin.getProperties()).thenReturn(twinProps);
        deterministicReadAnswers("box-default", deterministicTwin);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        assertEquals(0, result.divergent.size(),
                "two rows that agree in every content field were reported divergent — a "
                        + "standing false ERROR whose prescribed resolution deletes a row "
                        + "that disagrees with nothing: " + result);
        assertEquals(1, result.sweptDuplicates);
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("the copy never carries storage fields, whatever getProperties surfaced")
    void aCopyNeverCarriesStorageFields() {
        wire();
        Map<String, Object> legacyProps = connectorProps("filer2", "Filer2");
        legacyProps.put("_id", "gen-999");
        legacyProps.put("_rev", "9-r");
        listingAnswers(List.of(row("gen-999", legacyProps, "9-r")));
        deterministicReadAnswers("filer2", null);
        writesSucceed();

        ConnectorDefinitionService.LegacyIdMigrationResult result =
                service.migrateLegacyGeneratedIds();

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        Map<String, Object> copied = written.getValue().document().getProperties();
        assertFalse(copied.containsKey("_rev"),
                "the copy carries the LEGACY row's _rev, which corrupts the create — the "
                        + "write is either rejected or versioned against a foreign row");
        assertFalse(copied.containsKey("_id"),
                "the copy carries the legacy _id inside its body");
        assertEquals(1, result.migrated);
        assertTrue(result.clean());
    }

    @Test
    @DisplayName("a deterministic row the index cannot show is still found by get()")
    void aDeterministicRowTheIndexCannotShowIsStillFoundByGet() {
        wire();
        selectorAnswersNothing();
        Document stored = mock(Document.class);
        when(stored.getProperties()).thenReturn(connectorProps("c-hidden-det", "Hidden"));
        deterministicReadAnswers("c-hidden-det", stored);

        ConnectorDefinition found = service.get("c-hidden-det");

        assertTrue(found != null && "c-hidden-det".equals(found.getConnectorId()),
                "a row under its deterministic id read as absent: " + found);
    }

    @Test
    @DisplayName("a selector that THROWS falls through to the deterministic id, it does not "
            + "escape as a 500")
    void aFailingSelectorDoesNotEscape() {
        // The profile twin of this wrap was locked last round; the connector get() got the
        // same try/catch and nothing measured it. A review counted that as the sixth
        // one-armed fix.
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("500 internal server error from the index"));
        Document row = mock(Document.class);
        when(row.getProperties()).thenReturn(connectorProps("c-wrapped", "Wrapped"));
        when(row.getRev()).thenReturn("1-a");
        deterministicReadAnswers("c-wrapped", row);

        ConnectorDefinition found = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.get("c-wrapped"),
                "a failed selector escaped instead of falling through to the id-addressed read");

        assertEquals("c-wrapped", found == null ? null : found.getConnectorId(),
                "the deterministic id did not answer after the selector failed");
    }

    @Test
    @DisplayName("a deterministic row whose body names another connector is not returned as that id")
    void aDeterministicRowThatNamesAnotherConnectorIsNotReturned() {
        wire();
        selectorAnswersNothing();
        Document stored = mock(Document.class);
        when(stored.getProperties()).thenReturn(connectorProps("c-other", "Other"));
        deterministicReadAnswers("c-asked", stored);

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.get("c-asked"),
                "a row of c-asked whose body named c-other was returned as c-asked");
    }

    @Test
    @DisplayName("a missing conf client on the id fallback does not escape as a 500")
    void aMissingConfClientOnTheIdFallbackDoesNotEscape() {
        // The profile twin of this wrap was locked last round; getConfClient() still sat
        // outside the fallback try. A review counted the leak.
        wire();
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenThrow(new RuntimeException("500 internal server error from the index"));
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper).thenReturn(null);

        ConnectorDefinition found = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.get("c-no-client"),
                "a missing conf client on the id fallback escaped");
        assertEquals(null, found, "a failed fallback must not invent a row");
    }

    @Test
    @DisplayName("a transport failure on the _all_docs walk is typed not-ready, not a 500")
    void aTransportFailureOnTheWalkIsTypedNotReady() {
        wire();
        when(cloudant.postAllDocs(any(PostAllDocsOptions.class))).thenAnswer(call -> {
            ServiceCall<AllDocsResult> failed = mock(ServiceCall.class);
            when(failed.execute()).thenThrow(new RuntimeException("connection reset by peer"));
            return failed;
        });

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.existsIndexFree("c-reset"),
                "a transport failure escaped as a raw runtime exception");
        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.countIndexFree("c-reset"),
                "countIndexFree leaked a transport failure");
    }

    @Test
    @DisplayName("two legacy rows for one connectorId are BOTH left standing")
    void twoLegacyRowsForOneIdAreBothLeftStanding() {
        wire();
        selectorAnswersNothing();
        listingAnswers(List.of(row("generated-a", connectorProps("c-dup", "A"), "1-a"),
                row("generated-b", connectorProps("c-dup", "B"), "1-b")));
        writesSucceed();

        var result = service.migrateLegacyGeneratedIds();

        assertEquals(0, result.migrated, "one of two legacy rows was made canonical by "
                + "nothing but enumeration order");
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
        assertTrue(result.divergent.stream().anyMatch(d -> d.contains("c-dup")),
                "the pair was not reported: " + result.divergent);
        assertTrue(!result.clean(), "a pass that left two rows standing reported clean");
    }

    @Test
    @DisplayName("a CREATE whose selector out-reports the scan refuses too — not only UPDATE")
    void aCreateWhoseSelectorOutReportsTheWalkRefuses() {
        wire();
        selectorAnswersNothingThenShows(row("connector_definition:c-ghost2",
                connectorProps("c-ghost2", "Ghost"), "1-a"));
        listingAnswers(List.of());
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                        () -> service.create(validDefinition("c-ghost2")),
                        "the create wrote to a row the index-free scan says is not there");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("a CREATE never adopts a row the scan found — the concurrent-create race")
    void aCreateNeverAdoptsARowTheScanFound() {
        // The profile twin of this lock, mirrored: create() checks existence through the
        // selector, then upsertDocument looks again — two requests, not one snapshot. Two
        // concurrent creates both passed the check and the slower one overwrote the other's
        // configuration with a 201.
        wire();
        DocsResultRow theirs = row("connector_definition:c-race",
                connectorProps("c-race", "Theirs"), "1-a");
        selectorAnswersNothingThenShows(theirs);
        listingAnswers(List.of(theirs));
        writesSucceed();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validDefinition("c-race")),
                "the create overwrote a row that already defined this connector");
        assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the selector must not out-report the index-free scan — the write refuses")
    void theSelectorMustNotOutReportTheWalk() {
        wire();
        selectorShows(row("connector_definition:c-ghost",
                connectorProps("c-ghost", "Ghost"), "1-a"));
        listingAnswers(List.of());
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                        () -> service.update(validDefinition("c-ghost")),
                        "the update wrote to a row the index-free scan says is not there");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE over a VISIBLE twin pair does not write — nobody chose a winner")
    void anUpdateWithTwoVisibleTwinsDoesNotWrite() {
        // The connector twin of the profile lock: 2 rows, the selector shows both, and the
        // count-versus-selector arm is satisfied — the write went to existing.get(0).
        wire();
        DocsResultRow twinA = row("generated-a", connectorProps("twin", "A"), "1-a");
        DocsResultRow twinB = row("generated-b", connectorProps("twin", "B"), "1-b");
        selectorShows(twinA, twinB);
        listingAnswers(List.of(twinA, twinB));
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorHasTwinRowsException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorHasTwinRowsException.class,
                        () -> service.update(validDefinition("twin")),
                        "the update wrote to one of two visible twins");
        assertTrue(refused.getMessage().contains("?docId="),
                "the refusal does not name the resolver: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE over an invisible legacy row refuses retryably — the scan's "
            + "other arm")
    void anUpdateOverAnInvisibleLegacyRowRefusesRetryably() {
        // The one-arm the create-side scan left standing, named by a review: a real-value
        // PUT while the selector rebuilds and only a legacy row defines the connector.
        // Without this arm the update wrote a NEW deterministic row beside the invisible
        // legacy one — the divergent twin, created with a 200 that looks like success.
        // RELEASE_NOTES already claimed the 503; the code did not deliver it.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("veteran", null);
        listingAnswers(List.of(row("old-vet-id", connectorProps("veteran", "Veteran"), "1-r")));
        // Unused on the healthy tree; here for the control (PG) so the sabotaged flow
        // completes and assertThrows fails on its own claim, not on a laundered NPE.
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                        () -> service.update(validDefinition("veteran")),
                        "the update wrote a second definition beside a legacy row the "
                                + "index cannot show");
        // "rebuilding index shows 0": the selector saw nothing and the walk saw the legacy
        // row. Distinct from the hidden-twin arm ("shows 1"), so the two locks cannot be
        // satisfied by each other's refusal.
        assertTrue(refused.getMessage().contains("rebuilding index shows 0"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE whose scan cannot READ a row refuses retryably too — not a 500")
    void anUpdateWhoseScanCannotReadRefusesRetryablyToo() {
        // The closure-time record: the scan's unprovable-uniqueness refusal is an
        // IllegalStateException, which the create controller maps to 400 (locked) and the
        // update controller does not catch — so an update during that moment answered 500.
        // No twin was written, but the condition is as transient as every other
        // rebuilding-index refusal, and 500 is what opens tickets. The service now
        // re-types it for updates; the controller's existing 503 mapping (already locked
        // behaviourally) carries it out.
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("murky", null);
        listingAnswers(List.of(row(null, null, null)));
        // Unused on the healthy tree — the scan refuses first. Here for PA/PG: under those
        // sabotages the flow completes to a write, and an unstubbed postDocument would NPE
        // inside assertThrows and be laundered into a passable failure — the shape this
        // file's own PA/PE comments forbid. Same one-liner as the three siblings above.
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                        () -> service.update(validDefinition("murky")),
                        "an update whose uniqueness scan could not read a row escaped as "
                                + "some other type — the controller answers 500 for it");
        assertTrue(refused.getMessage().contains("cannot be established"),
                "re-typed by some other arm: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("an UPDATE with a clean scan still writes — the upsert semantics survive")
    void anUpdateWithACleanScanStillWrites() {
        wire();
        selectorAnswersNothing();
        deterministicReadAnswers("brand-new", null);
        Map<String, Object> config = new HashMap<>();
        config.put("type", "configuration");
        listingAnswers(List.of(row("config-1", config, "1-a")));
        writesSucceed();

        service.update(validDefinition("brand-new"));

        ArgumentCaptor<PostDocumentOptions> written =
                ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("connector_definition:brand-new",
                written.getValue().document().getId(),
                "the update-side scan broke the ordinary upsert");
    }

    /** The selector (postFind) shows exactly these raw rows — a PARTIAL view of the DB. */
    @SuppressWarnings("unchecked")
    private void selectorShows(DocsResultRow... visibleRows) {
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
        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(findCall);
    }

    /**
     * The selector answers NOTHING first and shows {@code laterRows} from the second call on
     * — two concurrent requests: the existence check misses, the write's own look-up finds.
     */
    @SuppressWarnings("unchecked")
    private void selectorAnswersNothingThenShows(DocsResultRow... laterRows) {
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> empty = mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.FindResult> emptyResponse = mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.FindResult none =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(none.getDocs()).thenReturn(List.of());
        when(emptyResponse.getResult()).thenReturn(none);
        when(empty.execute()).thenReturn(emptyResponse);

        List<Document> docs = new ArrayList<>();
        for (DocsResultRow r : laterRows) {
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
        ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> shown = mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.FindResult> shownResponse = mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.FindResult found =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(found.getDocs()).thenReturn(docs);
        when(shownResponse.getResult()).thenReturn(found);
        when(shown.execute()).thenReturn(shownResponse);

        when(cloudant.postFind(any(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class)))
                .thenReturn(empty).thenReturn(shown);
    }

    @Test
    @DisplayName("an UPDATE while a second twin is hidden refuses retryably — the connector "
            + "twin of the profile finding")
    void anUpdateWithAHiddenTwinIsAStandingPairNotARetry() {
        // Found on the profile side in review round 2 and mirrored here: consulting the walk
        // only when the selector returned NOTHING let an update adopt the visible twin
        // while another stayed hidden, and the pair diverged with a 200. Round 3: the count
        // has established a PAIR whatever the selector shows, so the answer is the standing-
        // twin refusal (409), not "retry" — a retry could only end in that same 409.
        wire();
        DocsResultRow visible = row("connector_definition:c-pair",
                connectorProps("c-pair", "Pair"), "4-r");
        DocsResultRow hidden = row("legacy-pair", connectorProps("c-pair", "Pair (old)"), "2-r");
        selectorShows(visible);
        listingAnswers(List.of(visible, hidden));
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorHasTwinRowsException refused =
                assertThrows(ConnectorDefinitionServiceImpl.ConnectorHasTwinRowsException.class,
                        () -> service.update(validDefinition("c-pair")),
                        "a pair with one twin hidden was written to, or answered 'retry' — "
                                + "the count had already established the pair");
        assertTrue(refused.getMessage().contains("2 definition row"),
                "refused by some other guard: " + refused.getMessage());
        verify(cloudant, never()).postDocument(any(PostDocumentOptions.class));
    }

    @Test
    @DisplayName("the plain delete removes the twin the selector hid")
    void thePlainDeleteRemovesHiddenTwinsToo() {
        // "消したつもりが残る" — recorded at closure time as a sibling, closed here: the
        // selector-based delete removed only what the rebuilding index showed and then
        // reported success.
        wire();
        DocsResultRow visible = row("connector_definition:c-gone",
                connectorProps("c-gone", "Gone"), "4-r");
        DocsResultRow hidden = row("legacy-gone", connectorProps("c-gone", "Gone (old)"), "2-r");
        listingAnswers(List.of(visible, hidden));
        writesSucceed();

        service.delete("c-gone");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant, org.mockito.Mockito.times(2)).deleteDocument(deleted.capture());
        assertTrue(deleted.getAllValues().stream().map(DeleteDocumentOptions::docId)
                        .toList().contains("legacy-gone"),
                "the twin the selector could not show survived a 'successful' delete");
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
        when(legacyRow.getProperties()).thenReturn(connectorProps("twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        // TWO pages: this fixture's queue is consumptive, and the delete counts BEFORE and
        // AFTER — the second count would have received null and answered -1, so the lock was
        // red on a healthy tree. (The profile fixture is sticky and needs only one.)
        // THREE rows, so the healthy answer after one delete is 2. With two rows the healthy
        // answer was 1 — the same constant the control substitutes, so the control could not
        // tell a healthy tree from its own sabotage. A review caught the collision after the
        // fixture started reflecting deletions.
        List<DocsResultRow> page = List.of(row("legacy-abc", connectorProps("twin", "Twin"), "7-r"),
                row("connector_definition:twin", connectorProps("twin", "Twin"), "1-d"),
                row("legacy-def", connectorProps("twin", "Twin"), "2-d"));
        listingAnswers(page);
        listingAnswers(page);
        writesSucceed();

        int remaining = service.delete("twin", "legacy-abc");

        assertEquals(2, remaining,
                "the delete does not report the surviving rows, so a concurrent delete of "
                        + "the other twin leaves the definition gone and unreported");
        // Connectors are global, so there is no confined-versus-global distinction to make
        // here; what this measures is that the count runs AFTER the delete.
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
        when(only.getProperties()).thenReturn(connectorProps("c-solo-row", "Solo"));
        when(only.getRev()).thenReturn("1-a");
        stubGetDocument("only-row", only);
        listingAnswers(List.of());
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused = assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class, () -> service.delete("c-solo-row", "only-row"),
                "a scan that counted no rows answered 'this is the only row'");
        assertTrue(refused.getMessage().contains("disagree"), refused.getMessage());
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("a post-delete count that cannot answer reports -1, not a survivor")
    void aPostDeleteCountThatCannotAnswerReportsMinusOne() {
        // The row IS deleted; what is unknown is what remains. Reporting a survivor there
        // would let the caller skip the work a zero demands, and reporting zero would stop a
        // scheduler on a guess. No test asserted this arm — a review found the gap.
        wire();
        Document legacyRow = mock(Document.class);
        when(legacyRow.getProperties()).thenReturn(connectorProps("twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        // ONE page: the pre-delete count consumes it, and the post-delete walk finds the
        // queue empty — the listing does not answer.
        listingAnswers(List.of(row("legacy-abc", connectorProps("twin", "Twin"), "7-r"),
                row("connector_definition:twin", connectorProps("twin", "Twin"), "1-d")));
        writesSucceed();

        int remaining = service.delete("twin", "legacy-abc");

        assertEquals(-1, remaining,
                "a count that could not answer was reported as a survivor count");
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
        when(only.getProperties()).thenReturn(connectorProps("c-solo-row", "Solo"));
        when(only.getRev()).thenReturn("1-a");
        stubGetDocument("only-row", only);
        listingAnswers(List.of(row("only-row", connectorProps("c-solo-row", "Solo"), "1-a")));
        writesSucceed();

        ConnectorDefinitionServiceImpl.ConnectorHasNoTwinException refused = assertThrows(ConnectorDefinitionServiceImpl.ConnectorHasNoTwinException.class,
                () -> service.delete("c-solo-row", "only-row"),
                "the last definition row was removed through the divergent-pair resolver");
        assertTrue(refused.getMessage().contains("only definition row"), refused.getMessage());
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
        DocsResultRow first = row("connector_definition:c-half",
                connectorProps("c-half", "Half"), "4-r");
        DocsResultRow second = row("legacy-half", connectorProps("c-half", "Half (old)"), "2-r");
        listingAnswers(List.of(first, second));
        writesSucceed();
        // the first delete succeeds, the second fails
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

        ConnectorDefinitionServiceImpl.ConnectorPartiallyDeletedException partly = assertThrows(ConnectorDefinitionServiceImpl.ConnectorPartiallyDeletedException.class, () -> service.delete("c-half"),
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
        DocsResultRow first = row("connector_definition:c-half",
                connectorProps("c-half", "Half"), "4-r");
        DocsResultRow second = row("legacy-half", connectorProps("c-half", "Half (old)"), "2-r");
        listingAnswers(List.of(first, second));
        writesSucceed();
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenAnswer(inv -> {
            ServiceCall<DocumentResult> call = mock(ServiceCall.class);
            when(call.execute()).thenThrow(new RuntimeException("forbidden"));
            return call;
        });

        RuntimeException refused = assertThrows(RuntimeException.class, () -> service.delete("c-half"));
        assertTrue(!(refused instanceof ConnectorDefinitionServiceImpl.ConnectorPartiallyDeletedException),
                "a delete that removed nothing was reported as partly deleted, so the caller "
                        + "is told to retry an operation the store refused: " + refused);
    }

    @Test
    @DisplayName("the plain delete refuses when a row cannot be read")
    void thePlainDeleteRefusesAnUnreadableRow() {
        wire();
        listingAnswers(List.of(row(null, null, null),
                row("connector_definition:c-x", connectorProps("c-x", "X"), "1-r")));
        writesSucceed();

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.delete("c-x"),
                "a delete that could not read every row reported success");
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    // ────────────────────────────────────────────────────────────────────
    // The divergent-twin resolver: delete ONE row by document id
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the one-row delete removes exactly the addressed row")
    void theOneRowDeleteRemovesTheAddressedRow() {
        // The migration's ERROR prescribes deleting the unwanted twin — and the plain
        // delete removes EVERY selector match, so following the old instruction destroyed
        // both configurations. A review caught the impossible prescription; this is the
        // operation that makes it possible.
        wire();
        Document legacyRow = mock(Document.class);
        when(legacyRow.getProperties()).thenReturn(connectorProps("twin", "Twin"));
        when(legacyRow.getRev()).thenReturn("7-r");
        stubGetDocument("legacy-abc", legacyRow);
        // The resolver now counts the rows first: it refuses to remove the LAST one, because
        // that would delete the connector through a path that assumes it survives. Two rows,
        // so this really is a divergent pair.
        listingAnswers(List.of(row("legacy-abc", connectorProps("twin", "Twin"), "7-r"),
                row("connector_definition:twin", connectorProps("twin", "Twin"), "1-d")));
        // the post-delete count consumes a second page from this queue
        listingAnswers(List.of(row("connector_definition:twin", connectorProps("twin", "Twin"), "1-d")));
        writesSucceed();

        service.delete("twin", "legacy-abc");

        ArgumentCaptor<DeleteDocumentOptions> deleted =
                ArgumentCaptor.forClass(DeleteDocumentOptions.class);
        verify(cloudant).deleteDocument(deleted.capture());
        assertEquals("legacy-abc", deleted.getValue().docId());
        assertEquals("7-r", deleted.getValue().rev());
    }

    @Test
    @DisplayName("the one-row delete refuses a row that defines a DIFFERENT connector")
    void theOneRowDeleteRefusesAMismatchedRow() {
        wire();
        Document foreignRow = mock(Document.class);
        when(foreignRow.getProperties()).thenReturn(connectorProps("someone-else", "Other"));
        when(foreignRow.getRev()).thenReturn("2-r");
        stubGetDocument("foreign-row", foreignRow);
        // For the control (PE), same reason as the PA stub above: with the verification
        // narrowed, the flow reaches an unstubbed deleteDocument and the NPE would be
        // laundered through assertThrows into a passable failure.
        // The narrowing controls make the flow reach the COUNT and then the delete, so the
        // count needs rows to see — otherwise the control fires on an unanswered listing
        // instead of on the claim. A review caught the new walk changing why they fire.
        listingAnswers(List.of(row("row-a", connectorProps("twin", "Twin"), "7-r"),
                row("row-b", connectorProps("twin", "Twin"), "1-d")));
        writesSucceed();

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("twin", "foreign-row"),
                "an id-addressed delete removed a row of a DIFFERENT connector — worse "
                        + "than the divergence it was resolving");
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

    @Test
    @DisplayName("the one-row delete refuses a row that does not exist")
    void theOneRowDeleteRefusesAMissingRow() {
        wire();
        stubGetDocument("gone-row", null);

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("twin", "gone-row"));
        verify(cloudant, never()).deleteDocument(any(DeleteDocumentOptions.class));
    }

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
    @DisplayName("the migration patch runs BEFORE the default-connector patch")
    void theMigrationRunsBeforeTheDefaultConnectorPatch() throws Exception {
        // Ordering is load-bearing: Patch_DefaultCloudDriveConnectorProfile's existence
        // check is a Mango selector. With the legacy row migrated first, even a selector
        // whose index is rebuilding cannot lead to a duplicate in the same startup — the
        // id-addressed check inside create() sees the deterministic row. Reversed, the
        // default patch runs against the unmigrated state once per upgrade.
        // BEAN TAGS, not bare class names. The first version searched for the class name
        // and found it first inside this change's own explanatory XML comment ("MUST come
        // before Patch_DefaultCloudDriveConnectorProfile") — which sits above the migration
        // bean, so the lock was RED on the healthy tree while the order it checks was
        // correct. The comment sabotaged the lock that checks the comment's claim; a
        // review caught it before first contact.
        String xml = jp.aegif.nemaki.util.test.JavaSource.read(
                "src/main/webapp/WEB-INF/classes/patchContext.xml");
        int migration = xml.indexOf(
                "<bean class=\"jp.aegif.nemaki.patch.Patch_ConnectorDefinitionDeterministicIds\"");
        int defaultConnector = xml.indexOf(
                "<bean class=\"jp.aegif.nemaki.patch.Patch_DefaultCloudDriveConnectorProfile\"");
        assertTrue(migration >= 0,
                "the migration patch is not registered in the patch chain — legacy rows are "
                        + "never rewritten and §62 stays open on every upgraded installation");
        assertTrue(migration < defaultConnector,
                "the migration is registered AFTER the default-connector patch, so the one "
                        + "startup that creates defaults still runs against unmigrated rows");
    }

    @Test
    @DisplayName("the migration is visible to the FALLBACK patch path too")
    void theMigrationIsOnTheFallbackPathToo() throws Exception {
        // The chain entry alone is an anonymous inline bean, and the fallback listener
        // (NemakiPatchInitializationListener — live in every deployment via web.xml)
        // collects patches with getBeansOfType, which cannot see inline beans. The
        // default-connector patch HAS a top-level bean, so on exactly the degraded
        // startups the fallback exists for, it would run against unmigrated rows with the
        // migration silently absent — the RC4 (R1) trap the listener's own javadoc
        // documents. A review caught this before first contact.
        String xml = jp.aegif.nemaki.util.test.JavaSource.read(
                "src/main/webapp/WEB-INF/classes/patchContext.xml");
        assertTrue(xml.contains(
                "<bean id=\"patch_ConnectorDefinitionDeterministicIds\""),
                "the migration has no top-level bean, so the fallback patch path cannot "
                        + "see it while it CAN see the default-connector patch");

        String listener = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/init/NemakiPatchInitializationListener.java"));
        int seed = listener.indexOf("\"patch_ConnectorDefinitionDeterministicIds\"");
        int defaultSeedOrRemainder = listener.indexOf(
                "\"patch_DefaultCloudDriveConnectorProfile\"");
        assertTrue(seed >= 0,
                "the migration is not pinned in ORDERED_SEED_PATCHES — its place before "
                        + "the default-connector patch rests on alphabetical chance");
        assertTrue(defaultSeedOrRemainder < 0 || seed < defaultSeedOrRemainder,
                "the migration is pinned AFTER the default-connector patch in the seeds");
    }
}
