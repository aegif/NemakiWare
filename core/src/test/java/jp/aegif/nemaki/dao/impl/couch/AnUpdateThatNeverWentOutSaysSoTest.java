package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts;
import jp.aegif.nemaki.dao.impl.couch.connector.CouchUpdateNotSentException;
import jp.aegif.nemaki.model.Document;

/**
 * An update that failed before its request went out says so by type; one the store answered does not.
 *
 * <p>Content created for a version that had none is taken back only when the store was verifiably not written. Every
 * failure of the update read the same — a document that could not be serialised, or had no revision, like a timeout — so
 * one that never went out kept its content as if the store might point at it (closing batch 39 review, Codex P2 and
 * subagent F2). And nothing drove the wrapper to show that a conflict keeps its cause, which is what the caller reads
 * (subagent F6).
 */
class AnUpdateThatNeverWentOutSaysSoTest {

    /** A call built as postDocument builds it, which goes out — and here fails — in execute(). */
    @SuppressWarnings("unchecked")
    private static com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> failingOnExecute(
            RuntimeException failure) {
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> call =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        when(call.execute()).thenThrow(failure);
        return call;
    }

    private static CloudantClientWrapper wrapperOver(com.ibm.cloud.cloudant.v1.Cloudant cloudant) {
        return new CloudantClientWrapper(cloudant, "bedroom",
                jp.aegif.nemaki.config.ObjectMapperFactory.createCouchdbObjectMapper());
    }

    @Test
    @DisplayName("a document with no revision is refused before its request goes out, and the type says so")
    void aDocumentWithNoRevisionIsNotSent() {
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(pool.getClient(anyString())).thenReturn(client);
        dao.setConnectorPool(pool);
        Document document = new Document();
        document.setId("doc-1");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        document.setSubTypeProperties(new ArrayList<>());
        document.setAcl(new jp.aegif.nemaki.model.Acl());

        assertThrows(CouchUpdateNotSentException.class, () -> dao.update("bedroom", document),
                "an update refused for having no revision did not say it never went out");
        verify(client, never()).update(any());
    }

    @Test
    @DisplayName("an update that fails before its request is made says it never went out")
    void anUpdateThatFailsBeforeItsRequestIsNotSent() {
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        // No _id: the wrapper refuses it while it builds the request.
        Map<String, Object> noId = new HashMap<>(Map.of("type", "cmis:document"));

        // As Object: the document path (ContentDaoServiceImpl.update) reaches update(Object), and a Map argument would bind
        // to the separate update(Map) overload.
        assertThrows(CouchUpdateNotSentException.class, () -> wrapperOver(cloudant).update((Object) noId),
                "an update that failed while its request was being built did not say it never went out");
        verify(cloudant, never()).postDocument(any());
    }

    @Test
    @DisplayName("an update the store answered with a conflict does not say it never went out, and keeps the conflict")
    void anAnsweredUpdateIsNotMarkedNotSent() {
        com.ibm.cloud.sdk.core.service.exception.ConflictException conflict = CouchConflicts.conflict();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> call = failingOnExecute(conflict);
        when(cloudant.postDocument(any())).thenReturn(call);
        Map<String, Object> stored = new HashMap<>(Map.of("_id", "doc-1", "_rev", "1-a", "type", "cmis:document"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> wrapperOver(cloudant).update((Object) stored));
        assertFalse(thrown instanceof CouchUpdateNotSentException,
                "an update the store answered was reported as one that never went out");
        assertSame(conflict, thrown.getCause(), "the conflict is not the failure's cause, which is what a caller reads");
    }

    @Test
    @DisplayName("an update whose request failed on the way does not say it never went out")
    void anUpdateThatFailedOnTheWayIsNotMarkedNotSent() {
        // Not a conflict: a conflict takes the wrapper's own catch, which never asks whether the request went out, so a
        // lock on it alone stayed green with the flag never set (BM6 did not fire, closing batch 40). In execute(), where the
        // request goes out: postDocument only builds the call, and a failure there is one before the request (closing
        // batch 40 review, Codex P2 — this lock threw from postDocument, and held the flag at the wrong place).
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> call =
                failingOnExecute(new RuntimeException("connection reset"));
        when(cloudant.postDocument(any())).thenReturn(call);
        Map<String, Object> stored = new HashMap<>(Map.of("_id", "doc-1", "_rev", "1-a", "type", "cmis:document"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> wrapperOver(cloudant).update((Object) stored));
        assertFalse(thrown instanceof CouchUpdateNotSentException,
                "an update whose request may have reached the store was reported as one that never went out");
    }

    @Test
    @DisplayName("an update whose call cannot be built says it never went out")
    void anUpdateWhoseCallCannotBeBuiltIsNotSent() {
        // postDocument only builds the call; nothing has gone to the store when it fails (closing batch 40 review, Codex P2).
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        when(cloudant.postDocument(any())).thenThrow(new IllegalArgumentException("the call could not be built"));
        Map<String, Object> stored = new HashMap<>(Map.of("_id", "doc-1", "_rev", "1-a", "type", "cmis:document"));

        assertThrows(CouchUpdateNotSentException.class, () -> wrapperOver(cloudant).update((Object) stored),
                "an update whose call failed while it was being built did not say it never went out");
    }
}
