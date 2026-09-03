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
    private ConnectorDefinitionServiceImpl service;
    private final List<AllDocsResult> pages = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private void wire() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        wrapper = mock(CloudantClientWrapper.class);
        cloudant = mock(Cloudant.class);
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        when(wrapper.getClient()).thenReturn(cloudant);
        service = new ConnectorDefinitionServiceImpl();
        service.setConnectorPool(pool);

        ServiceCall<AllDocsResult> allDocsCall = mock(ServiceCall.class);
        when(cloudant.postAllDocs(any(PostAllDocsOptions.class))).thenReturn(allDocsCall);
        when(allDocsCall.execute()).thenAnswer(inv -> {
            Response<AllDocsResult> response = mock(Response.class);
            AllDocsResult next = pages.isEmpty() ? null : pages.remove(0);
            when(response.getResult()).thenReturn(next);
            return response;
        });
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

        ServiceCall<DocumentResult> deleteCall = mock(ServiceCall.class);
        Response<DocumentResult> deleteResponse = mock(Response.class);
        when(deleteResponse.getResult()).thenReturn(ok);
        when(deleteCall.execute()).thenReturn(deleteResponse);
        when(cloudant.deleteDocument(any(DeleteDocumentOptions.class))).thenReturn(deleteCall);
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
        String pager = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private void forEachAllDocsRow(");
        String walk = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "public LegacyIdMigrationResult migrateLegacyGeneratedIds()");
        String perRow = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private void migrateOneLegacyRow(");
        String scan = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "private boolean aConnectorRowExistsIndexFree(");
        assertTrue(pager.contains("postAllDocs("),
                "the shared walk no longer reads _all_docs — whatever replaced it, the "
                        + "burden is on it to answer while indexes rebuild: " + pager);
        assertTrue(walk.contains("forEachAllDocsRow(") && scan.contains("forEachAllDocsRow("),
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
        assertTrue(refused.getMessage().contains("legacy row"),
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
