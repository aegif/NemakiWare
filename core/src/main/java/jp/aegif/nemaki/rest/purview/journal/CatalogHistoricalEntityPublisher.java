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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * Writes and re-reads a historical entity for one target.
 *
 * <h2>One class, bound per target</h2>
 *
 * <p>Atlas and Purview speak the same Atlas API through the same client, so two classes would be
 * the same code twice. What must not be shared is the <em>binding</em>: an instance answers for
 * the target it was constructed with and refuses every other, so a result can never be recorded
 * against a catalog it did not come from.
 *
 * <h2>PUBLISHED is only ever said after reading it back</h2>
 *
 * <p>A 2xx from a bulk endpoint says the request was accepted, not that the entity is there. The
 * receipt's own contract refuses {@code PUBLISHED} without a read-back verdict, and this obtains
 * that verdict from the catalog rather than from the response it just received.
 */
public final class CatalogHistoricalEntityPublisher
        implements LineageObligationWiringConfig.TargetedHistoricalPublisher {

    private static final Logger logger =
            LoggerFactory.getLogger(CatalogHistoricalEntityPublisher.class);

    private final String boundTarget;
    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public CatalogHistoricalEntityPublisher(String boundTarget,
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityRegistryClient entityRegistryClient) {
        if (boundTarget == null || boundTarget.isBlank()) {
            throw new IllegalArgumentException("a historical publisher must name its target");
        }
        this.boundTarget = boundTarget;
        this.connectionResolver = connectionResolver;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public String targetName() {
        return boundTarget;
    }

    @Override
    public LineageHistoricalPublishReceipt publishHistorical(HistoricalEntitySnapshot snapshot) {
        String target = snapshot.snapshot().target();
        if (!boundTarget.equals(target)) {
            // Answering here would attribute a write to a catalog this publisher never reached.
            return retryable(snapshot, "this publisher is bound to a different target");
        }
        if (connectionResolver == null || entityRegistryClient == null) {
            return retryable(snapshot, "no catalog client is available");
        }

        Map<String, Object> entity = LineageHistoricalEntityFactory.entityFor(snapshot);
        String plannedDigest = LineageHistoricalEntityFactory.operationDigest(entity);
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    LineageHistoricalEntityFactory.bulkPayload(entity));
            if (result == null || !result.isSuccess()) {
                // The catalog refused it. Retryable, not terminal: a refusal is usually a
                // throttle or an outage, and burning the obligation would make a transient
                // failure permanent.
                return retryable(snapshot, "the catalog refused the write");
            }
        } catch (PurviewClientException | RuntimeException e) {
            // Class name only: a catalog error body echoes the qualified name, and an external
            // asset's qualified name contains its stable key.
            logger.warn("Historical publish to '{}' failed: {}", boundTarget,
                    e.getClass().getSimpleName());
            return retryable(snapshot, "the catalog write failed");
        }

        // Read it back before claiming anything. The receipt refuses PUBLISHED without this.
        LineageHistoricalReadBack verdict = readBackHistorical(snapshot, plannedDigest);
        if (verdict != LineageHistoricalReadBack.MATCH) {
            return retryable(snapshot, "the write was accepted but could not be confirmed");
        }
        return new LineageHistoricalPublishReceipt(
                LineageHistoricalEntityPublisher.Outcome.PUBLISHED, boundTarget,
                snapshot.sourceEvidence().subjectDigest(), plannedDigest,
                LineageCatalogEntityProbe.Presence.PRESENT);
    }

    /**
     * Whether the catalog holds exactly what this plan meant to write.
     *
     * <h2>Four answers, and why none of them collapses</h2>
     *
     * <ul>
     *   <li>{@code ABSENT} — the catalog answered and does not hold it. Safe to write again.</li>
     *   <li>{@code MATCH} — it holds this plan's content. The plan is done, whoever wrote it.</li>
     *   <li>{@code CONFLICT} — it holds something else. Another plan won, or something older is
     *       there; either way this plan must not overwrite it on its own authority.</li>
     *   <li>{@code UNKNOWN} — nothing was established. Not ABSENT: treating an unreachable
     *       catalog as empty is how a second write lands on top of a first.</li>
     * </ul>
     */
    @Override
    public LineageHistoricalReadBack readBackHistorical(HistoricalEntitySnapshot snapshot,
            String plannedOperationDigest) {
        if (!boundTarget.equals(snapshot.snapshot().target())
                || connectionResolver == null || entityRegistryClient == null
                || plannedOperationDigest == null || plannedOperationDigest.isBlank()) {
            return LineageHistoricalReadBack.UNKNOWN;
        }
        Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(snapshot);
        try {
            Map<String, Object> read = entityRegistryClient.getEntityByUniqueAttribute(
                    connectionResolver.buildConnectionRequest(),
                    snapshot.snapshot().endpointKind().atlasTypeName(), "qualifiedName",
                    snapshot.snapshot().catalogQualifiedName());
            if (read == null || read.isEmpty()) {
                // The client's 404: the catalog answered, and does not hold it.
                return LineageHistoricalReadBack.ABSENT;
            }
            String actual = LineageHistoricalEntityFactory.readBackDigest(read, planned);
            if (actual == null) {
                // A response this code cannot project is not a mismatch — it is an unread.
                return LineageHistoricalReadBack.UNKNOWN;
            }
            // Constant-time is unnecessary here (neither side is a secret) but the comparison
            // is still exact: a digest is equal or it is not.
            return actual.equals(plannedOperationDigest) ? LineageHistoricalReadBack.MATCH
                    : LineageHistoricalReadBack.CONFLICT;
        } catch (PurviewClientException | RuntimeException e) {
            logger.warn("Historical read-back from '{}' could not answer: {}", boundTarget,
                    e.getClass().getSimpleName());
            return LineageHistoricalReadBack.UNKNOWN;
        }
    }

    private LineageHistoricalPublishReceipt retryable(HistoricalEntitySnapshot snapshot,
            String why) {
        logger.debug("Historical publish to '{}' is retryable: {}", boundTarget, why);
        return new LineageHistoricalPublishReceipt(
                LineageHistoricalEntityPublisher.Outcome.RETRYABLE, boundTarget,
                snapshot.sourceEvidence().subjectDigest(), null, null);
    }
}
