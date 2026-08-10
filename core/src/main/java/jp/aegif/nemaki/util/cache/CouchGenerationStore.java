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
 * <p>The document holds a per-replica entry, and the value each replica cares about is the maximum
 * across all of them. That makes a lost update harmless in one direction and not the other:
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
 * publishes a lower number than it did before. That is why every replica reads the MAXIMUM and
 * tracks what it has already acted on: a number going backwards can never cause a missed
 * invalidation, only a redundant one that the acted-on watermark then suppresses.
 */
public class CouchGenerationStore implements CrossReplicaCacheInvalidator.GenerationStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchGenerationStore.class);

    static final String DOC_TYPE = "cacheGeneration";
    private static final String DOC_ID = "cache-generation";

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
    public CrossReplicaCacheInvalidator.Generations publishAndRead(String repositoryId,
            long localAcl, long localPrincipal) {
        if (connectorPool == null) {
            return null;
        }
        CloudantClientWrapper client = connectorPool.getClient(repositoryId);
        Map<String, Object> doc = readDoc(client);

        Map<String, Object> replicas = doc.get("replicas") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) doc.get("replicas"))
                : new LinkedHashMap<>();

        long maxAcl = localAcl;
        long maxPrincipal = localPrincipal;
        for (Map.Entry<String, Object> e : replicas.entrySet()) {
            if (nodeId.equals(e.getKey()) || !(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            maxAcl = Math.max(maxAcl, nonNegative(entry.get("acl")));
            maxPrincipal = Math.max(maxPrincipal, nonNegative(entry.get("principal")));
        }

        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("acl", localAcl);
        mine.put("principal", localPrincipal);
        replicas.put(nodeId, mine);
        doc.put("_id", DOC_ID);
        doc.put("type", DOC_TYPE);
        doc.put("replicas", replicas);
        try {
            client.update(doc);
        } catch (Exception e) {
            // Best effort by design — see the class comment. A lost publish costs one poll.
            logger.debug("Cache generation publish lost the revision race for {}: {}",
                    repositoryId, e.getMessage());
        }
        return new CrossReplicaCacheInvalidator.Generations(maxAcl, maxPrincipal);
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
     * A generation value, or 0 when it is absent or unusable.
     *
     * <p>An unusable value is logged rather than swallowed: it means some replica is publishing
     * garbage, and the visible symptom would otherwise be "invalidation quietly stopped working".
     */
    private static long nonNegative(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            long v = new java.math.BigDecimal(value.toString()).longValueExact();
            return v < 0 ? 0L : v;
        } catch (ArithmeticException | NumberFormatException e) {
            logger.warn("Ignoring a non-integral cache generation value: {}", value);
            return 0L;
        }
    }
}
