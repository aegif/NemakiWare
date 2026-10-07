package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.AllDocsResult;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocsResultRow;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * A CREATE answers 400 for a row the index-free walk could not CLASSIFY (standing; an
 * administrator repairs the row) and used to answer the same 400 for a walk that did not
 * ANSWER at all — a transport failure, which a retry fixes. Both arrived as one
 * {@code IllegalStateException}. The walk now types its own refusals and a create maps them
 * to the retryable 503 the update path already used (R26).
 */
class DefinitionCreateWalkFailuresAreRetriesTest {

    private Cloudant cloudant;

    private CloudantClientPool poolOn(Cloudant cloudant) {
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        when(wrapper.getClient()).thenReturn(cloudant);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(wrapper);
        return pool;
    }

    @SuppressWarnings("unchecked")
    private void selectorAnswersNothing() {
        FindResult empty = mock(FindResult.class);
        when(empty.getDocs()).thenReturn(List.of());
        Response<FindResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(empty);
        ServiceCall<FindResult> call = mock(ServiceCall.class);
        when(call.execute()).thenReturn(resp);
        when(cloudant.postFind(any())).thenReturn(call);
    }

    /** Every walk page fails on transport. */
    @SuppressWarnings("unchecked")
    private void walkDoesNotAnswer() {
        ServiceCall<AllDocsResult> call = mock(ServiceCall.class);
        when(call.execute()).thenThrow(new RuntimeException("connection reset by peer"));
        when(cloudant.postAllDocs(any())).thenReturn(call);
    }

    /** The first walk answers {@code rows}; every later one fails on transport. */
    @SuppressWarnings("unchecked")
    private void walkAnswersOnceThenFails(List<DocsResultRow> rows) {
        AllDocsResult page = mock(AllDocsResult.class);
        when(page.getRows()).thenReturn(rows);
        Response<AllDocsResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(page);
        ServiceCall<AllDocsResult> first = mock(ServiceCall.class);
        when(first.execute()).thenReturn(resp);
        ServiceCall<AllDocsResult> later = mock(ServiceCall.class);
        when(later.execute()).thenThrow(new RuntimeException("connection reset by peer"));
        when(cloudant.postAllDocs(any())).thenReturn(first, later);
    }

    /** A row the walk serves and no definition can be read from. */
    private static DocsResultRow unclassifiableRow() {
        DocsResultRow row = mock(DocsResultRow.class);
        when(row.getId()).thenReturn("connector_definition:broken");
        when(row.getError()).thenReturn("internal_server_error");
        return row;
    }

    private static ConnectorDefinition validConnector() {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setConnectorId("c-new");
        def.setDisplayName("New");
        def.setSourceSystem("box");
        def.setSourceArchetype(SourceArchetype.FILE_SHARE);
        return def;
    }

    private static ImportProfileDefinition validProfile() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setProfileId("p-new");
        def.setRepositoryId("bedroom");
        def.setTargetFolderId("folder-1");
        def.setEnabled(true);
        return def;
    }

    @Test
    @DisplayName("a connector create whose uniqueness walk did not answer is a retry, not a 400")
    void aConnectorCreateWhoseWalkDidNotAnswerIsARetry() {
        cloudant = mock(Cloudant.class);
        selectorAnswersNothing();
        walkDoesNotAnswer();
        ConnectorDefinitionServiceImpl service = new ConnectorDefinitionServiceImpl();
        service.setConnectorPool(poolOn(cloudant));

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> service.create(validConnector()),
                "a walk that never ran answered as the request's fault");
    }

    @Test
    @DisplayName("a row the walk could not classify still answers the create's own 400")
    void aRowTheWalkCouldNotClassifyStillAnswers400OnCreate() {
        // The over-throw guard: the standing case keeps its contract.
        cloudant = mock(Cloudant.class);
        selectorAnswersNothing();
        walkAnswersOnceThenFails(List.of(unclassifiableRow()));
        ConnectorDefinitionServiceImpl service = new ConnectorDefinitionServiceImpl();
        service.setConnectorPool(poolOn(cloudant));

        // ConnectorIndexNotReadyException is not an IllegalStateException, so this assertion
        // fails if the create answers the transient refusal instead of its own 400.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.create(validConnector()),
                "a row that cannot be classified was reported as a transient refusal");
        assertTrue(refused.getMessage().contains("cannot be"),
                "the refusal does not say uniqueness could not be established: " + refused.getMessage());
    }

    @Test
    @DisplayName("a profile create whose uniqueness-rule walk did not answer is a retry, not a 400")
    void aProfileCreateWhoseRuleWalkDidNotAnswerIsARetry() {
        // validateAutoResolveUniqueness walks first (enabled profile).
        cloudant = mock(Cloudant.class);
        selectorAnswersNothing();
        walkDoesNotAnswer();
        ImportProfileDefinitionServiceImpl service = new ImportProfileDefinitionServiceImpl();
        service.setConnectorPool(poolOn(cloudant));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.create(validProfile()),
                "a walk that never ran answered as the request's fault");
    }

    @Test
    @DisplayName("a profile create whose row-count walk did not answer is a retry, not a 400")
    void aProfileCreateWhoseCountWalkDidNotAnswerIsARetry() {
        // The rule walk answers (no rows); the count walk that guards the write fails.
        cloudant = mock(Cloudant.class);
        selectorAnswersNothing();
        walkAnswersOnceThenFails(List.of());
        ImportProfileDefinitionServiceImpl service = new ImportProfileDefinitionServiceImpl();
        service.setConnectorPool(poolOn(cloudant));

        assertThrows(ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                () -> service.create(validProfile()),
                "a walk that never ran answered as the request's fault");
    }
}
