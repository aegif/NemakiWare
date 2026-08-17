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
package jp.aegif.nemaki.util.cache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * Publishes and reads the cache generations through a shared CouchDB document.
 *
 * <h2>Why the write can be sloppy and the read cannot</h2>
 *
 * <p>The document holds one entry per replica. Each replica publishes its own and reads everyone
 * else's — never a maximum across them, because the counters are independent per-process write
 * counts and comparing one replica's against another's says nothing. That makes a lost update
 * harmless in one direction and not the other:
 *
 * <ul>
 * <li>A <b>lost publish</b> (two replicas writing at once, one losing the revision race) only
 *     delays the news by one poll — the next cycle republishes. So the write is best-effort with
 *     no CAS loop, deliberately: a retry storm here would cost more than the delay it saves.</li>
 * <li>A <b>misread</b> would be silent under-invalidation, which is the failure this whole
 *     mechanism exists to prevent. So a value that cannot be parsed as a non-negative integer is
 *     treated as "unknown" and ignored rather than coerced to zero — coercion would make a corrupt
 *     entry look like "nothing has changed".</li>
 * </ul>
 *
 * <p>The counters are JVM-local and reset to zero when a replica restarts, so a restarted replica
 * publishes a lower number than it did before. The reader treats any CHANGE to a node's value as
 * news, not only an increase, so a reset causes one redundant clear rather than a missed one.
 */
public class CouchGenerationStore implements CrossReplicaCacheInvalidator.GenerationStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchGenerationStore.class);

    static final String DOC_TYPE = "cacheGeneration";
    private static final String DOC_ID = "cache-generation";

    /**
     * How long a replica may go without publishing before its entry is removed. Comfortably more
     * than the poll interval, so a temporarily unreachable replica is not evicted mid-hiccup.
     */
    private static final long STALE_REPLICA_MS = 60 * 60 * 1000L;

    private CloudantClientPool connectorPool;
    private RepositoryInfoMap repositoryInfoMap;

    /** Identifies this replica's entry. Any stable-per-process value works. */
    private final String nodeId = java.util.UUID.randomUUID().toString();

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    @Override
    public Collection<String> repositoryIds() {
        return repositoryInfoMap == null ? java.util.List.of() : repositoryInfoMap.keys();
    }

    @SuppressWarnings("unchecked")
    @Override
    public CrossReplicaCacheInvalidator.ReplicaGenerations publishAndRead(String repositoryId,
            long localAcl, long localPrincipal) {
        if (connectorPool == null) {
            return null;
        }
        CloudantClientWrapper client = connectorPool.getClient(repositoryId);
        Map<String, Object> doc = readDoc(client);

        Map<String, Object> replicas = doc.get("replicas") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) doc.get("replicas"))
                : new LinkedHashMap<>();

        // OTHER replicas only. Excluding ourselves here — rather than compensating for it later
        // with a comparison against our own counter — is what lets the invalidator both ignore our
        // own writes and never miss anyone else's. The counters are independent per-process write
        // counts, so any comparison BETWEEN replicas' values is meaningless.
        Map<String, Long> aclByNode = new LinkedHashMap<>();
        Map<String, Long> principalByNode = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : replicas.entrySet()) {
            if (nodeId.equals(e.getKey()) || !(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            // Omit the node entirely on a bad value rather than substituting 0. Substituting
            // would read as "that replica has done nothing", which is the one interpretation
            // that produces no invalidation; omitting makes it look departed, so its next valid
            // publish is treated as new and clears. Fail towards an extra clear.
            Long acl = generationOrNull(entry.get("acl"));
            Long principal = generationOrNull(entry.get("principal"));
            if (acl != null) {
                aclByNode.put(e.getKey(), acl);
            }
            if (principal != null) {
                principalByNode.put(e.getKey(), principal);
            }
        }

        // Publish, and RETRY ONCE against a re-read revision.
        //
        // "A lost publish only costs one poll" was not true as written. update(Map) swallows the
        // conflict and returns null, so the catch below never saw a failure; and with fixed-delay
        // pollers the same replica can lose the revision race every cycle, which makes the delay
        // unbounded rather than one poll. One retry against a fresh revision breaks that pattern,
        // and a persistent failure is reported at WARN so a replica whose changes are never
        // reaching the others is visible instead of silent.
        // Prune BEFORE writing. Pruning the in-memory map afterwards changes nothing that is
        // stored — the write has already happened — so the entries survive every cycle. (Which is
        // exactly what the first version of this did, and what checking the document revealed.)
        pruneStaleReplicas(replicas);
        if (!publish(client, doc, replicas, localAcl, localPrincipal)) {
            Map<String, Object> fresh = readDoc(client);
            @SuppressWarnings("unchecked")
            Map<String, Object> freshReplicas = fresh.get("replicas") instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) fresh.get("replicas"))
                    : new LinkedHashMap<>();
            pruneStaleReplicas(freshReplicas);
            if (!publish(client, fresh, freshReplicas, localAcl, localPrincipal)) {
                logger.warn("Could not publish cache generations for {} — this replica's ACL and"
                        + " principal changes are not yet visible to the others", repositoryId);
            }
        }
        return new CrossReplicaCacheInvalidator.ReplicaGenerations(aclByNode, principalByNode);
    }

    /** @return true when the write landed. update(Map) returns null on any failure. */
    private boolean publish(CloudantClientWrapper client, Map<String, Object> doc,
            Map<String, Object> replicas, long localAcl, long localPrincipal) {
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("acl", localAcl);
        mine.put("principal", localPrincipal);
        mine.put("heartbeatMs", System.currentTimeMillis());
        replicas.put(nodeId, mine);
        doc.put("_id", DOC_ID);
        doc.put("type", DOC_TYPE);
        doc.put("replicas", replicas);
        try {
            return client.update(doc) != null;
        } catch (Exception e) {
            logger.debug("Cache generation publish failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Drops entries whose replica has not published for a long time.
     *
     * <p>The node id is a fresh UUID per process, so without this every restart adds an entry
     * that is never removed. That is not only untidy: the document is read and rewritten on every
     * poll by every replica, so an ever-growing map eventually makes those writes fail on size
     * limits — at which point invalidation stops propagating altogether.
     *
     * <p>Entries with no heartbeat are pruned too. They can only be leftovers from a build that
     * did not publish one, and every live replica republishes within a poll interval, so the
     * worst case is that a still-running replica loses its entry and is then re-observed as a new
     * node — one extra cache clear during the upgrade, in exchange for not carrying dead entries
     * for the life of the deployment.
     */
    @SuppressWarnings("unchecked")
    private void pruneStaleReplicas(Map<String, Object> replicas) {
        long cutoff = System.currentTimeMillis() - STALE_REPLICA_MS;
        replicas.entrySet().removeIf(e -> {
            if (nodeId.equals(e.getKey()) || !(e.getValue() instanceof Map)) {
                return false;
            }
            Object hb = ((Map<String, Object>) e.getValue()).get("heartbeatMs");
            Long at = generationOrNull(hb);
            return at == null || at < cutoff;
        });
    }

    private Map<String, Object> readDoc(CloudantClientWrapper client) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document existing = client.get(DOC_ID);
            if (existing != null) {
                Map<String, Object> map = new LinkedHashMap<>(existing.getProperties());
                map.put("_id", DOC_ID);
                if (existing.getRev() != null) {
                    map.put("_rev", existing.getRev());
                }
                return map;
            }
        } catch (Exception e) {
            logger.debug("Cache generation document not readable yet: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    /**
     * A generation value, or {@code null} when it is absent or unusable.
     *
     * <p>Unusable is logged rather than swallowed: it means some replica is publishing garbage,
     * and the visible symptom would otherwise be "invalidation quietly stopped working".
     */
    private static Long generationOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long v = new java.math.BigDecimal(value.toString()).longValueExact();
            return v < 0 ? null : v;
        } catch (ArithmeticException | NumberFormatException e) {
            logger.warn("Ignoring a non-integral cache generation value: {}", value);
            return null;
        }
    }
}
