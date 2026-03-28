package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LeaderElection}.
 *
 * <p>Uses mocked CouchDB client to verify leader election logic
 * without requiring a live CouchDB instance.
 */
class LeaderElectionTest {

    private LeaderElection election;
    private CloudantClientWrapper mockClient;
    private LineageConfig mockConfig;
    private CloudantClientPool mockPool;

    @BeforeEach
    void setUp() throws Exception {
        election = new LeaderElection();
        mockClient = mock(CloudantClientWrapper.class);
        mockConfig = mock(LineageConfig.class);
        mockPool = mock(CloudantClientPool.class);

        when(mockConfig.isLeaderElectionEnabled()).thenReturn(true);
        when(mockConfig.getLeaderHeartbeatSeconds()).thenReturn(15);
        when(mockConfig.getLeaderTtlSeconds()).thenReturn(60);
        when(mockPool.getClient("nemaki_conf")).thenReturn(mockClient);

        setField(election, "lineageConfig", mockConfig);
        setField(election, "connectorPool", mockPool);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void isLeader_returnsTrueWhenDisabled() {
        when(mockConfig.isLeaderElectionEnabled()).thenReturn(false);
        assertTrue(election.isLeader("projection"));
    }

    @Test
    void isEnabled_reflectsConfig() {
        when(mockConfig.isLeaderElectionEnabled()).thenReturn(true);
        assertTrue(election.isEnabled());

        when(mockConfig.isLeaderElectionEnabled()).thenReturn(false);
        assertFalse(election.isEnabled());
    }

    @Test
    void getNodeId_isNotNull() {
        assertNotNull(election.getNodeId());
        assertFalse(election.getNodeId().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryAcquireOrVerify_createsDocWhenNotExists() {
        // No leader doc exists
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        boolean result = election.tryAcquireOrVerify("projection");

        assertTrue(result);
        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            return "lineage_leader:projection".equals(m.get("_id"))
                    && "lineage_leader".equals(m.get("type"))
                    && election.getNodeId().equals(m.get("nodeId"));
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryAcquireOrVerify_returnsTrueWhenAlreadyLeader() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_leader:projection");
        doc.put("nodeId", election.getNodeId());
        doc.put("lastHeartbeat", Instant.now().toString());
        doc.put("ttlSeconds", 60);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        boolean result = election.tryAcquireOrVerify("projection");

        assertTrue(result);
        verify(mockClient, never()).update(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryAcquireOrVerify_returnsFalseWhenOtherNodeIsLeader() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_leader:projection");
        doc.put("nodeId", "other-node-id");
        doc.put("lastHeartbeat", Instant.now().toString());
        doc.put("ttlSeconds", 60);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        boolean result = election.tryAcquireOrVerify("projection");

        assertFalse(result);
        verify(mockClient, never()).update(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryAcquireOrVerify_takesOverWhenTtlExpired() {
        // Other node's heartbeat expired
        Instant expired = Instant.now().minusSeconds(120);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_leader:projection");
        doc.put("nodeId", "other-node-id");
        doc.put("lastHeartbeat", expired.toString());
        doc.put("ttlSeconds", 60);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        boolean result = election.tryAcquireOrVerify("projection");

        assertTrue(result);
        verify(mockClient).update(argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d;
            return election.getNodeId().equals(m.get("nodeId"));
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryAcquireOrVerify_returnsFalseOnException() {
        doThrow(new RuntimeException("CAS conflict")).when(mockClient)
                .get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        boolean result = election.tryAcquireOrVerify("projection");

        assertFalse(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void heartbeat_updatesWhenLeader() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_leader:projection");
        doc.put("nodeId", election.getNodeId());
        doc.put("lastHeartbeat", Instant.now().minusSeconds(10).toString());

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        election.heartbeat("projection");

        verify(mockClient).update(argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d;
            return election.getNodeId().equals(m.get("nodeId"))
                    && m.get("lastHeartbeat") != null;
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void heartbeat_skipsWhenNotLeader() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_leader:projection");
        doc.put("nodeId", "other-node-id");
        doc.put("lastHeartbeat", Instant.now().toString());

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_leader:projection"), isNull());

        election.heartbeat("projection");

        verify(mockClient, never()).update(any(Map.class));
    }
}
