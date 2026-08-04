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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.HttpPurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * The historical publisher and its read-back against a real Apache Atlas.
 *
 * <p>What only a real catalog can settle: that the entity the factory builds is one Atlas
 * accepts, that reading it back returns a shape the digest projection can handle, and that the
 * projection actually ignores the fields Atlas adds on its own. Every one of those is a place
 * where a mock agrees with itself and a real server does not.
 *
 * <p>Enabled by {@code NEMAKI_LINEAGE_IT_ATLAS_URL}; the dedicated CI job sets
 * {@code -Dlineage.it.required=true}, where a missing URL is a failure rather than a silent skip.
 */
public class LineageHistoricalAdapterAtlasIT {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";

    private static MetadataCatalogConnectionResolver connectionResolver;
    private static PurviewEntityRegistryClient client;

    @BeforeAll
    static void connect() {
        String url = System.getenv("NEMAKI_LINEAGE_IT_ATLAS_URL");
        if (url == null || url.isBlank()) {
            if (Boolean.getBoolean("lineage.it.required")) {
                throw new IllegalStateException("lineage.it.required=true but"
                        + " NEMAKI_LINEAGE_IT_ATLAS_URL is not set — the CI gate must run");
            }
            org.junit.jupiter.api.Assumptions.abort(
                    "NEMAKI_LINEAGE_IT_ATLAS_URL not set — Atlas adapter IT skipped locally");
        }
        String user = System.getenv("NEMAKI_LINEAGE_IT_ATLAS_USER");
        String password = System.getenv("NEMAKI_LINEAGE_IT_ATLAS_PASSWORD");
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                url.replaceAll("/+$", ""), "api/atlas/v2", "basic", "", "", "",
                user == null ? "admin" : user, password == null ? "admin" : password,
                5_000, 30_000);
        connectionResolver = new MetadataCatalogConnectionResolver(null, null) {
            @Override
            public PurviewConnectionRequest buildConnectionRequest() {
                return request;
            }
        };
        client = new HttpPurviewEntityRegistryClient();
    }

    /**
     * A historical entity, end to end.
     *
     * <p>Publish, then read back with the plan's own digest. MATCH is the only outcome that may
     * be reported as PUBLISHED, and this is where it is established that a real Atlas round trip
     * can produce it — the projection has to survive whatever Atlas adds.
     */
    @Test
    @DisplayName("a historical entity publishes and reads back as MATCH on real Atlas")
    public void publishAndReadBack() {
        HistoricalEntitySnapshot snapshot = historical("hist-" + UUID.randomUUID());
        CatalogHistoricalEntityPublisher publisher =
                new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client);

        LineageHistoricalPublishReceipt receipt = publisher.publishHistorical(snapshot);

        assertEquals(LineageHistoricalEntityPublisher.Outcome.PUBLISHED, receipt.outcome(),
                "a real Atlas round trip must be able to reach PUBLISHED");
        assertEquals(LineageCatalogEntityProbe.Presence.PRESENT, receipt.readBackVerdict());
        assertNotNull(receipt.operationDigest());

        // And again, with the digest the receipt reported: idempotent, and still a MATCH.
        assertEquals(LineageHistoricalReadBack.MATCH,
                publisher.readBackHistorical(snapshot, receipt.operationDigest()));
    }

    /**
     * Atlas's own attributes must not turn a correct write into a conflict.
     *
     * <p>A real entity comes back with a guid, timestamps, a status and whatever else the
     * server keeps. If the projection did not exclude them, every correctly published entity
     * would read back CONFLICT and no obligation would ever resolve.
     */
    @Test
    @DisplayName("the read-back ignores the attributes Atlas adds by itself")
    public void readBackIgnoresServerAttributes() throws Exception {
        HistoricalEntitySnapshot snapshot = historical("extras-" + UUID.randomUUID());
        CatalogHistoricalEntityPublisher publisher =
                new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client);
        LineageHistoricalPublishReceipt receipt = publisher.publishHistorical(snapshot);
        assertEquals(LineageHistoricalEntityPublisher.Outcome.PUBLISHED, receipt.outcome());

        Map<String, Object> read = client.getEntityByUniqueAttribute(
                connectionResolver.buildConnectionRequest(),
                snapshot.snapshot().endpointKind().atlasTypeName(), "qualifiedName",
                snapshot.snapshot().catalogQualifiedName());
        assertNotNull(read, "the entity must be readable");
        Map<String, Object> normalised = LineageHistoricalEntityFactory.normaliseRead(read);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
                (Map<String, Object>) normalised.get("attributes");
        // The point of the test: Atlas really does return more than was written.
        assertTrue(attributes.size()
                        > ((Map<?, ?>) LineageHistoricalEntityFactory.entityFor(snapshot)
                                .get("attributes")).size(),
                "Atlas is expected to add attributes of its own");
        assertEquals(receipt.operationDigest(), LineageHistoricalEntityFactory.readBackDigest(
                read, LineageHistoricalEntityFactory.entityFor(snapshot)));
    }

    /** An entity that was never written must read ABSENT, not UNKNOWN. */
    @Test
    @DisplayName("an unwritten entity reads back ABSENT")
    public void unwrittenIsAbsent() {
        HistoricalEntitySnapshot snapshot = historical("never-written-" + UUID.randomUUID());
        assertEquals(LineageHistoricalReadBack.ABSENT,
                new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client)
                        .readBackHistorical(snapshot,
                                LineageHistoricalEntityFactory.plannedOperationDigest(snapshot)));
    }

    /** Someone else's content at the same qualified name is a CONFLICT, not a match. */
    @Test
    @DisplayName("a different plan's digest reads back CONFLICT")
    public void differentPlanIsConflict() {
        HistoricalEntitySnapshot snapshot = historical("conflict-" + UUID.randomUUID());
        CatalogHistoricalEntityPublisher publisher =
                new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client);
        assertEquals(LineageHistoricalEntityPublisher.Outcome.PUBLISHED,
                publisher.publishHistorical(snapshot).outcome());

        assertEquals(LineageHistoricalReadBack.CONFLICT,
                publisher.readBackHistorical(snapshot,
                        "0".repeat(64)),
                "a plan whose digest is not what is there must not be told it matched");
    }

    /**
     * The purged marker really lands: this is the tombstone a consumer reads.
     *
     * <p>Under the name the type declares. Atlas silently drops an attribute a type does not
     * have, so a marker written under the wrong name produces an entity indistinguishable from
     * a live object's — which no mock can detect, because a mock stores whatever it is given.
     */
    @Test
    @DisplayName("the published entity carries the purged marker its type declares")
    public void carriesThePurgedMarker() throws Exception {
        HistoricalEntitySnapshot snapshot = historical("purged-" + UUID.randomUUID());
        new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client)
                .publishHistorical(snapshot);

        Map<String, Object> read = client.getEntityByUniqueAttribute(
                connectionResolver.buildConnectionRequest(),
                snapshot.snapshot().endpointKind().atlasTypeName(), "qualifiedName",
                snapshot.snapshot().catalogQualifiedName());
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>)
                LineageHistoricalEntityFactory.normaliseRead(read).get("attributes");
        String marker = LineageHistoricalEntityFactory.tombstoneMarkerAttribute(
                EndpointKind.CMIS_DOCUMENT);
        assertEquals("lifecycleState", marker,
                "nemaki_document declares lifecycleState, not sourceState");
        assertEquals(jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory
                .SOURCE_STATE_PURGED, attributes.get(marker),
                "the marker must survive the round trip — Atlas drops undeclared attributes");
    }

    /**
     * A type with nowhere to record the purge must not receive an entity at all.
     *
     * <p>{@code nemaki_external_asset} declares neither marker, so a historical entity for it
     * would sit in the catalog looking exactly like a live asset. SNAPSHOT_INCOMPLETE is the
     * honest answer, and it is terminal — retrying cannot make the type grow an attribute.
     */
    @Test
    @DisplayName("a type with no tombstone marker is SNAPSHOT_INCOMPLETE, not a silent write")
    public void aTypeWithNoMarkerIsRefused() {
        String stableKey = "s3://bucket/obj-" + UUID.randomUUID();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("externalStableKey", stableKey);
        attributes.put("sourceSystem", "s3");
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO,
                EndpointKind.EXTERNAL_ASSET, stableKey, attributes,
                LineageSourceDisposition.SOURCE_PURGED,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        HistoricalEntitySnapshot historical = new HistoricalEntitySnapshot(snapshot,
                LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.EXTERNAL_ASSET,
                        stableKey),
                LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.EXTERNAL_ASSET, stableKey,
                        LineageSourceDisposition.SOURCE_PURGED, "inc-1", "rev-1", null, 1_000L));

        assertEquals(LineageHistoricalEntityPublisher.Outcome.SNAPSHOT_INCOMPLETE,
                new CatalogHistoricalEntityPublisher(TARGET, connectionResolver, client)
                        .publishHistorical(historical).outcome());
    }

    /**
     * The Atlas sink can verify, and says so.
     *
     * <p>A sink that answers {@code false} makes D-rest refuse to sequence a single v2 row for
     * its target: a finalized v2 row is an ordered barrier, and a barrier no sink can drain
     * strands everything behind it. So this is not a nicety — without it the target cannot be
     * activated at all.
     */
    @Test
    @DisplayName("the Atlas sink verifies a published Process, and RETRYABLE for an absent one")
    public void atlasSinkVerifies() {
        AtlasConfig config = new AtlasConfig();
        config.setEnabled(true);
        config.setEndpoint(System.getenv("NEMAKI_LINEAGE_IT_ATLAS_URL"));
        config.setUsername(System.getenv().getOrDefault("NEMAKI_LINEAGE_IT_ATLAS_USER", "admin"));
        config.setPassword(System.getenv()
                .getOrDefault("NEMAKI_LINEAGE_IT_ATLAS_PASSWORD", "admin"));
        AtlasLineageSink sink = new AtlasLineageSink();
        org.springframework.test.util.ReflectionTestUtils.setField(sink, "atlasConfig", config);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sink, "init");
        try {
            assertTrue(sink.supportsVerification(),
                    "a sink that cannot verify blocks every v2 row for its target");
            // A Process nobody wrote is not visible. RETRYABLE rather than MISMATCH: Atlas
            // indexes asynchronously, and a record written correctly must not be burned.
            assertEquals(LineageTargetSink.VerifyResult.RETRYABLE,
                    sink.verify(absentRecord(), java.time.Duration.ofSeconds(5)));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(sink, "destroy");
        }
    }

    /** A record whose Process was never published. */
    private static LineageRecord absentRecord() {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("evt-verify-" + UUID.randomUUID())
                .occurredAt("2026-08-05T00:00:00Z")
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-verify-" + UUID.randomUUID())
                .delivery(new LineageDelivery.Original(java.util.List.of(TARGET)))
                .addInput(LineageEndpoint.document(REPO, "doc-verify", "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "doc-verify", "doc-verify", 1L))
                .build();
        return LineageRecord.fromV2(event);
    }

    // ------------------------------------------------------------------

    private static HistoricalEntitySnapshot historical(String objectId) {
        String qualifiedName = "nemaki://" + REPO + "/objects/" + objectId;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", objectId + ".txt");
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO,
                EndpointKind.CMIS_DOCUMENT, qualifiedName, attributes,
                LineageSourceDisposition.SOURCE_PURGED,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        LineageSourceDispositionResolver.SourceEvidence evidence =
                LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.CMIS_DOCUMENT, qualifiedName,
                        LineageSourceDisposition.SOURCE_PURGED, objectId, "rev-1", null,
                        1_000L);
        return new HistoricalEntitySnapshot(snapshot,
                LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_DOCUMENT,
                        qualifiedName), evidence);
    }
}
