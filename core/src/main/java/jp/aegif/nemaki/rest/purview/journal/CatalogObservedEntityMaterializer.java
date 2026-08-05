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

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * The observed-entity materializer over the catalog client, bound to one target.
 *
 * <p>Shares {@link LineageHistoricalEntityFactory}'s digest and read-back projection with the
 * historical publisher, so "did this plan's content land" is the same question either way. What
 * it does not share is the payload: {@code observedEntityFor} never produces a tombstone marker.
 */
public final class CatalogObservedEntityMaterializer implements LineageObservedEntityMaterializer {

    private static final Logger logger =
            LoggerFactory.getLogger(CatalogObservedEntityMaterializer.class);

    private final String boundTarget;
    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public CatalogObservedEntityMaterializer(String boundTarget,
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityRegistryClient entityRegistryClient) {
        if (boundTarget == null || boundTarget.isBlank()) {
            throw new IllegalArgumentException("a materializer must name its target");
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
    public Outcome materializeCurrent(VerifiedCurrentEntitySnapshot current,
            Map<String, Object> projection) {
        if (current == null || !boundTarget.equals(current.target())) {
            return Outcome.RETRYABLE;
        }
        if (projection == null || projection.isEmpty()) {
            // No projection, no write. The snapshot's own attributes are not a substitute: they
            // are what the event saw, possibly at an earlier revision than the verdict that
            // licensed this write, and the schema records no revision to compare them against.
            return Outcome.RETRYABLE;
        }
        // The entity this execution's own repository read produced, published and confirmed by
        // the same pre-read / write / post-read shape as an observation. What differs is
        // upstream — the snapshot's constructor demanded a positive live-source verdict — so
        // the write itself needs no second policy branch.
        return publishAndConfirm(projection, current.snapshot().catalogQualifiedName(),
                current.snapshot().endpointKind());
    }

    @Override
    public Outcome materialize(ObservedEntitySnapshot observed) {
        if (observed == null || !boundTarget.equals(observed.target())) {
            // Answering for another target would attribute a write to a catalog never reached.
            return Outcome.RETRYABLE;
        }
        if (connectionResolver == null || entityRegistryClient == null) {
            return Outcome.RETRYABLE;
        }

        return publishAndConfirm(
                LineageHistoricalEntityFactory.observedEntityFrom(observed.snapshot()),
                observed.snapshot().catalogQualifiedName(),
                observed.snapshot().endpointKind());
    }

    /**
     * Pre-read, publish only if absent, then read back exactly.
     *
     * <p>Shared by both routes because the confirmation is the same confirmation. What differs
     * is where the entity came from — the event's observation for a source NemakiWare never
     * destroys, this execution's own repository read for a live LEDGERED source — and that has
     * been decided before this runs.
     *
     * @param qualifiedName the subject's own name, from the obligation rather than the payload
     */
    private Outcome publishAndConfirm(Map<String, Object> planned, String qualifiedName,
            EndpointKind kind) {
        if (connectionResolver == null || entityRegistryClient == null) {
            return Outcome.RETRYABLE;
        }
        if (planned == null || planned.isEmpty()) {
            return Outcome.RETRYABLE;
        }
        if (!namesSubject(planned, qualifiedName)) {
            // The payload is for some other object. Writing it would put content under a name
            // this obligation does not own, and resolve the obligation on the strength of it.
            logger.warn("A planned {} entity does not name the subject it would settle; nothing"
                    + " written", kind);
            return Outcome.CONFLICT;
        }
        List<String> missing =
                LineageHistoricalEntityFactory.missingMandatoryAttributes(planned, kind);
        if (!missing.isEmpty()) {
            // The catalog rejects a write missing these in full, so retrying cannot help — the
            // event simply never carried them. Names only: a value here is the identity the
            // secret boundary exists to keep out of records.
            logger.warn("An observed {} snapshot cannot be materialised: mandatory attribute(s)"
                    + " {} are absent", kind, missing);
            return Outcome.SNAPSHOT_INCOMPLETE;
        }
        // Includes the assertion that no tombstone marker is present: an entity carrying one
        // must not read back as this plan's content.
        String plannedDigest =
                LineageHistoricalEntityFactory.observedPlannedDigest(planned, kind);

        // 1. Read BEFORE writing. A crash between a successful write and the obligation being
        // resolved leaves the next pass here, and without this it would write again over
        // whatever is now there.
        switch (readBack(qualifiedName, planned, plannedDigest, kind)) {
            case MATCH -> {
                return Outcome.MATCHED;
            }
            case CONFLICT -> {
                return Outcome.CONFLICT;
            }
            case UNKNOWN -> {
                // Not ABSENT. Treating an unreachable catalog as empty is how a second write
                // lands on top of a first.
                return Outcome.RETRYABLE;
            }
            default -> {
                // ABSENT: nothing is there, so this plan may write it.
            }
        }

        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    LineageHistoricalEntityFactory.bulkPayload(planned));
            if (result == null || !result.isSuccess()) {
                return Outcome.RETRYABLE;
            }
        } catch (PurviewClientException | RuntimeException e) {
            // Class name only: a catalog error body echoes the qualified name, and an external
            // asset's qualified name contains its stable key.
            logger.warn("Observed materialisation to '{}' failed: {}", boundTarget,
                    e.getClass().getSimpleName());
            return Outcome.RETRYABLE;
        }

        // 2. Read AFTER writing. A 2xx says the request was accepted, not that it is there.
        return readBack(qualifiedName, planned, plannedDigest, kind)
                == LineageHistoricalReadBack.MATCH ? Outcome.MATERIALIZED : Outcome.RETRYABLE;
    }

    /** Whether a planned entity is about the subject this obligation names. */
    private static boolean namesSubject(Map<String, Object> planned, String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return false;
        }
        Object attributes = planned.get("attributes");
        return attributes instanceof Map<?, ?> attrs
                && qualifiedName.equals(attrs.get("qualifiedName"));
    }

    /** Whether the catalog holds exactly this plan's content, projected onto its own keys. */
    private LineageHistoricalReadBack readBack(String qualifiedName,
            Map<String, Object> planned, String plannedDigest, EndpointKind kind) {
        try {
            Map<String, Object> read = entityRegistryClient.getEntityByUniqueAttribute(
                    connectionResolver.buildConnectionRequest(),
                    kind.atlasTypeName(), "qualifiedName", qualifiedName);
            if (read == null || read.isEmpty()) {
                return LineageHistoricalReadBack.ABSENT;
            }
            String actual = LineageHistoricalEntityFactory.readBackDigest(read, planned,
                    LineageHistoricalEntityFactory.markerAbsenceAssertion(kind));
            if (actual == null) {
                // A response this code cannot project is an unread, not a mismatch.
                return LineageHistoricalReadBack.UNKNOWN;
            }
            return actual.equals(plannedDigest) ? LineageHistoricalReadBack.MATCH
                    : LineageHistoricalReadBack.CONFLICT;
        } catch (PurviewClientException | RuntimeException e) {
            logger.warn("Observed read-back from '{}' could not answer: {}", boundTarget,
                    e.getClass().getSimpleName());
            return LineageHistoricalReadBack.UNKNOWN;
        }
    }
}
