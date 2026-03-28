package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CouchDB CAS-based lightweight leader election for multi-node deployments.
 *
 * <p>Uses a CouchDB document with optimistic locking (_rev) to elect a leader.
 * Only one node at a time holds the leader lock for a given role.
 * TTL-based: if the leader's heartbeat expires, other nodes can claim leadership.
 *
 * <p>Default disabled — enable via {@code lineage.leader-election.enabled=true}.
 */
@Component
public class LeaderElection {

    private static final Logger logger = LoggerFactory.getLogger(LeaderElection.class);

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired
    private CloudantClientPool connectorPool;

    private final String nodeId = UUID.randomUUID().toString();
    private final Set<String> registeredRoles = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    public void init() {
        if (!isEnabled()) {
            logger.info("Leader election disabled (lineage.leader-election.enabled=false)");
            return;
        }

        int heartbeatSeconds = lineageConfig.getLeaderHeartbeatSeconds();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeaderElection-heartbeat");
            t.setDaemon(true);
            return t;
        });
        registerRole("projection");
        heartbeatScheduler.scheduleWithFixedDelay(
                this::heartbeatAllRoles,
                0, heartbeatSeconds, TimeUnit.SECONDS);

        logger.info("Leader election initialized: nodeId={}, heartbeat={}s, ttl={}s",
                nodeId, heartbeatSeconds, lineageConfig.getLeaderTtlSeconds());
    }

    /**
     * Registers a role for heartbeat tracking.
     */
    public void registerRole(String role) {
        registeredRoles.add(role);
    }

    /**
     * Returns whether this node is the leader for the given role.
     * If leader election is disabled, always returns true (single-node compatible).
     * Auto-registers the role on first call.
     */
    public boolean isLeader(String role) {
        if (!isEnabled()) return true;
        registerRole(role);

        try {
            return tryAcquireOrVerify(role);
        } catch (Exception e) {
            logger.debug("Leader check failed for role '{}': {}", role, e.getMessage());
            return false;
        }
    }

    /**
     * Sends heartbeat for all registered roles.
     */
    private void heartbeatAllRoles() {
        for (String role : registeredRoles) {
            heartbeat(role);
        }
    }

    public boolean isEnabled() {
        return lineageConfig.isLeaderElectionEnabled();
    }

    public String getNodeId() {
        return nodeId;
    }

    @SuppressWarnings("unchecked")
    boolean tryAcquireOrVerify(String role) {
        CloudantClientWrapper client = getConfClient();
        if (client == null) return false;

        String docId = "lineage_leader:" + role;

        try {
            Map<String, Object> doc = client.get(Map.class, docId, null);

            if (doc == null) {
                // No leader doc — try to create
                Map<String, Object> newDoc = new LinkedHashMap<>();
                newDoc.put("_id", docId);
                newDoc.put("type", "lineage_leader");
                newDoc.put("nodeId", nodeId);
                newDoc.put("lastHeartbeat", Instant.now().toString());
                newDoc.put("ttlSeconds", lineageConfig.getLeaderTtlSeconds());
                client.create(newDoc);
                logger.info("Acquired leadership for role '{}': nodeId={}", role, nodeId);
                return true;
            }

            String currentLeader = (String) doc.get("nodeId");
            String lastHeartbeat = (String) doc.get("lastHeartbeat");
            int ttl = doc.containsKey("ttlSeconds") ? ((Number) doc.get("ttlSeconds")).intValue()
                    : lineageConfig.getLeaderTtlSeconds();

            // Check if we are already the leader
            if (nodeId.equals(currentLeader)) {
                return true;
            }

            // Check if the current leader's TTL has expired
            if (lastHeartbeat != null) {
                Instant lastBeat = Instant.parse(lastHeartbeat);
                if (Instant.now().isAfter(lastBeat.plusSeconds(ttl))) {
                    // TTL expired — try to take over
                    doc.put("nodeId", nodeId);
                    doc.put("lastHeartbeat", Instant.now().toString());
                    doc.put("ttlSeconds", lineageConfig.getLeaderTtlSeconds());
                    client.update(doc);
                    logger.info("Took over leadership for role '{}': nodeId={} (previous expired)", role, nodeId);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            // CAS conflict or other error — we are not the leader
            logger.debug("Failed to acquire/verify leadership for role '{}': {}", role, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    void heartbeat(String role) {
        if (!isEnabled()) return;

        CloudantClientWrapper client = getConfClient();
        if (client == null) return;

        String docId = "lineage_leader:" + role;
        try {
            Map<String, Object> doc = client.get(Map.class, docId, null);
            if (doc != null && nodeId.equals(doc.get("nodeId"))) {
                doc.put("lastHeartbeat", Instant.now().toString());
                client.update(doc);
                logger.debug("Heartbeat updated for role '{}': nodeId={}", role, nodeId);
            }
        } catch (Exception e) {
            logger.debug("Heartbeat failed for role '{}': {}", role, e.getMessage());
        }
    }

    private CloudantClientWrapper getConfClient() {
        try {
            return connectorPool.getClient("nemaki_conf");
        } catch (Exception e) {
            logger.debug("Failed to get nemaki_conf client: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
    }
}
