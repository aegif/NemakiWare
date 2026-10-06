package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.couch.CouchRelationship;

/**
 * A relationship the store no longer holds is an absence, not an error.
 *
 * <p>{@code CloudantClientWrapper.get(Class, String)} answers null for a document that is not
 * there — its one legitimate null — and {@code getRelationship} dereferenced it. The service's
 * rule "an edge another delete removed first is not a survivor"
 * ({@code ContentServiceImpl.deleteRelationshipsBatch}) branches on that null, and its lock
 * stubbed the DAO to answer it; in production the DAO threw instead, the object delete aborted
 * with an NPE, and the object stayed undeletable — the 9-6 P1 the rule was written for (c96
 * confirmation review, P1). The seam is measured here from the DAO's side; the service's lock
 * measures the other side.
 */
class GoneRelationshipIsAbsentNotAnErrorTest {

    private static ContentDaoServiceImpl daoWhoseClientAnswers(CouchRelationship stored) {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(eq(CouchRelationship.class), anyString())).thenReturn(stored);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    @Test
    @DisplayName("a relationship the store no longer holds is null — the wrapper's null passed on, not dereferenced")
    void aGoneRelationshipIsNull() {
        ContentDaoServiceImpl dao = daoWhoseClientAnswers(null);

        Relationship answer = assertDoesNotThrow(() -> dao.getRelationship("bedroom", "rel-gone"),
                "the wrapper answered null for a relationship that is not there and getRelationship "
                        + "dereferenced it. The service's 'an edge already gone is not a survivor' "
                        + "never saw that null in production: the NPE aborted the delete and the "
                        + "object stayed undeletable (c96 confirmation review, P1)");
        assertNull(answer);
    }

    @Test
    @DisplayName("a relationship the store holds is converted as before")
    void aHeldRelationshipIsConverted() {
        CouchRelationship stored = new CouchRelationship();
        stored.setSourceId("source-1");
        stored.setTargetId("target-1");

        Relationship answer = daoWhoseClientAnswers(stored).getRelationship("bedroom", "rel-1");

        assertEquals("source-1", answer.getSourceId());
        assertEquals("target-1", answer.getTargetId());
    }
}
