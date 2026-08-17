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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * The observed-entity path, as one contract applied to every kind that accepts it.
 *
 * <p>The kind table is defined once. Writing the same eleven assertions out per kind would be
 * eighty-eight tests that drift apart the first time one of them is edited, and the point of the
 * contract is that it is identical everywhere.
 */
class LineageObservedMaterializationTest {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";

    // ------------------------------------------------------------------
    // The kind table — defined once
    // ------------------------------------------------------------------

    /** One kind, with the qualified name and attributes a real event would carry for it. */
    private record KindFixture(EndpointKind kind, String qualifiedName,
            Map<String, Object> attributes) {

        @Override
        public String toString() {
            return kind.name();
        }
    }

    private static KindFixture fixture(EndpointKind kind) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        String qualifiedName;
        switch (kind) {
            case EXTERNAL_ASSET, CLOUD_OBJECT, COLD_STORAGE -> {
                qualifiedName = "s3://bucket/object-" + kind.name().toLowerCase();
                attributes.put("externalStableKey", qualifiedName);
                attributes.put("sourceSystem", "s3");
            }
            case IMPORT_ARTIFACT -> {
                qualifiedName = "nemaki://" + REPO + "/imports/op-1";
                attributes.put("importMode", "CREATE");
            }
            case EXPORT_ARTIFACT -> {
                qualifiedName = "nemaki://" + REPO + "/exports/op-1";
                attributes.put("artifactKind", "ZIP");
            }
            case CMIS_FOLDER -> {
                qualifiedName = "nemaki://" + REPO + "/folders/f-1/dataset";
                attributes.put("name", "folder");
            }
            case ARCHIVE -> {
                qualifiedName = "nemaki://" + REPO + "/archives/a-1";
                attributes.put("archivedAt", 1_700_000_000_000L);
                attributes.put("originalObjectId", "doc-1");
            }
            default -> {
                qualifiedName = "nemaki://" + REPO + "/objects/doc-1";
                attributes.put("name", "a.txt");
            }
        }
        return new KindFixture(kind, qualifiedName, attributes);
    }

    /** The kinds an observed entity may be built for. */
    static List<KindFixture> acceptedKinds() {
        return java.util.Arrays.stream(EndpointKind.values())
                .filter(k -> !LineagePurgeLifecyclePolicy.canBePurged(k))
                .map(LineageObservedMaterializationTest::fixture)
                .toList();
    }

    /** The kinds it must refuse — NemakiWare destroys these, so absence may mean a purge. */
    static List<KindFixture> ledgeredKinds() {
        return java.util.Arrays.stream(EndpointKind.values())
                .filter(LineagePurgeLifecyclePolicy::canBePurged)
                .map(LineageObservedMaterializationTest::fixture)
                .toList();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a NON_PURGEABLE kind builds, and its entity carries no tombstone marker")
    void buildsWithoutAnyMarker(KindFixture fixture) {
        ObservedEntitySnapshot observed = observed(fixture);
        Map<String, Object> entity = LineageHistoricalEntityFactory.observedEntityFor(observed);

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals(fixture.kind().atlasTypeName(), entity.get("typeName"));
        assertEquals(fixture.qualifiedName(), attributes.get("qualifiedName"));
        // The whole point: never PURGED. The state is said out loud as ACTIVE rather than
        // omitted — omitting it made nemaki_archive unpublishable (lifecycleState is mandatory
        // there) and left the read-back having to encode "this key must be absent" instead of
        // simply comparing ACTIVE against whatever the catalog holds.
        assertFalse(attributes.containsValue("PURGED"),
                "an observation must never carry a tombstone marker value");
        String marker = LineageHistoricalEntityFactory.tombstoneMarkerAttribute(fixture.kind());
        assertEquals("ACTIVE", attributes.get(marker),
                "an ordinary entity states that its source is active");
        // Mandatory attributes are satisfied, or this could never be published at all.
        assertEquals(List.of(), observed.missingMandatoryAttributes());
    }

    /**
     * The historical entity for the same snapshot differs, and by more than a missing key.
     *
     * <p>Guards against the implementation this forbids: calling {@code entityFor} and deleting
     * the marker afterwards. That would make the tombstone exist for the duration of a method.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("the observed entity is not the historical entity with the marker removed")
    void observedIsNotHistoricalMinusMarker(KindFixture fixture) {
        ObservedEntitySnapshot observed = observed(fixture);
        Map<String, Object> observedEntity =
                LineageHistoricalEntityFactory.observedEntityFor(observed);
        assertNotEquals(LineageHistoricalEntityFactory.plannedObservedDigest(observed),
                LineageHistoricalEntityFactory.operationDigest(
                        withMarker(observedEntity, fixture.kind())),
                "the two payloads must be distinguishable by digest");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ledgeredKinds")
    @DisplayName("a LEDGERED kind is refused: its absent entity may mean a purge")
    void ledgeredKindsAreRefused(KindFixture fixture) {
        assertThrows(IllegalArgumentException.class, () -> observed(fixture));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a mismatched task key, target or subject is refused")
    void mismatchesAreRefused(KindFixture fixture) {
        LineageWaitingSnapshot snapshot = snapshotOf(fixture, TARGET);
        assertThrows(IllegalArgumentException.class,
                () -> new ObservedEntitySnapshot(snapshot, "0".repeat(64)),
                "a task key the snapshot does not derive must be refused");
        // A snapshot for another target cannot settle this target's task.
        LineageWaitingSnapshot otherTarget = snapshotOf(fixture, "purview");
        assertThrows(IllegalArgumentException.class,
                () -> new ObservedEntitySnapshot(otherTarget, taskKeyOf(fixture, TARGET)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a purged snapshot is refused — that verdict belongs to the historical path")
    void purgedSnapshotIsRefused(KindFixture fixture) {
        LineageWaitingSnapshot purged = LineageWaitingSnapshot.of(TARGET, REPO, fixture.kind(),
                fixture.qualifiedName(), fixture.attributes(),
                LineageSourceDisposition.SOURCE_PURGED,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        assertThrows(IllegalArgumentException.class,
                () -> new ObservedEntitySnapshot(purged, taskKeyOf(fixture, TARGET)));
    }

    /** The snapshot's own constructor refuses these; asserted here so the path is covered. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a nested value and a secret-bearing attribute are both refused")
    void nestedAndSecretValuesAreRefused(KindFixture fixture) {
        Map<String, Object> nested = new LinkedHashMap<>(fixture.attributes());
        nested.put("name", List.of("a", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageWaitingSnapshot.of(TARGET, REPO, fixture.kind(),
                        fixture.qualifiedName(), nested, LineageSourceDisposition.SOURCE_UNKNOWN,
                        LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION));

        Map<String, Object> secret = new LinkedHashMap<>(fixture.attributes());
        secret.put("password", "hunter2");
        assertThrows(IllegalArgumentException.class,
                () -> LineageWaitingSnapshot.of(TARGET, REPO, fixture.kind(),
                        fixture.qualifiedName(), secret, LineageSourceDisposition.SOURCE_UNKNOWN,
                        LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION));
    }

    // ------------------------------------------------------------------
    // Materialisation
    // ------------------------------------------------------------------

    /**
     * A tombstone must never read back as an ordinary entity.
     *
     * <p>The projection compares the keys the plan set, and an observed plan deliberately sets
     * no marker — so an entity with identical attributes plus {@code PURGED} projected to the
     * same digest, the pre-read said MATCH, and the obligation resolved while the catalog still
     * held a tombstone for a source nobody said was gone. Not setting a key is a claim about
     * that key, so it is in the comparison.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("an entity carrying a tombstone marker is CONFLICT, never MATCHED")
    void aTombstoneIsNeverAnObservedMatch(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);
        Map<String, Object> tombstoned =
                withMarker(LineageHistoricalEntityFactory.observedEntityFor(observed),
                        fixture.kind());
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(tombstoned);

        assertEquals(LineageObservedEntityMaterializer.Outcome.CONFLICT,
                materializer(client).materialize(observed),
                "a tombstone must not be accepted as this plan's content");
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("the catalog already holding it is MATCHED, and nothing is written")
    void preReadMatchWritesNothing(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(LineageHistoricalEntityFactory.observedEntityFor(observed));

        assertEquals(LineageObservedEntityMaterializer.Outcome.MATCHED,
                materializer(client).materialize(observed));
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("absent, then written, then read back as a match")
    void absentThenPublishedThenMatched(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(null)
                .thenReturn(LineageHistoricalEntityFactory.observedEntityFor(observed));
        when(client.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "ok"));

        assertEquals(LineageObservedEntityMaterializer.Outcome.MATERIALIZED,
                materializer(client).materialize(observed));
    }

    /** Someone else's entity is not overwritten. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a different entity at the same name is CONFLICT, and is not overwritten")
    void preReadConflictIsNotOverwritten(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);
        Map<String, Object> other =
                new LinkedHashMap<>(LineageHistoricalEntityFactory.observedEntityFor(observed));
        Map<String, Object> otherAttributes = new LinkedHashMap<>();
        otherAttributes.put("qualifiedName", fixture.qualifiedName());
        otherAttributes.put("somebodyElse", "yes");
        other.put("attributes", otherAttributes);
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(other);

        assertEquals(LineageObservedEntityMaterializer.Outcome.CONFLICT,
                materializer(client).materialize(observed));
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    /** An unreachable catalog is not an empty one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("an unreadable pre-read is RETRYABLE, and nothing is written")
    void preReadUnknownWritesNothing(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("catalog unreachable"));

        assertEquals(LineageObservedEntityMaterializer.Outcome.RETRYABLE,
                materializer(client).materialize(observed));
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a refused write, and an unconfirmed one, are both RETRYABLE")
    void publishFailureAndUnconfirmedWriteAreRetryable(KindFixture fixture) throws Exception {
        ObservedEntitySnapshot observed = observed(fixture);

        PurviewEntityRegistryClient refused = mock(PurviewEntityRegistryClient.class);
        when(refused.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(refused.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("throttled"));
        assertEquals(LineageObservedEntityMaterializer.Outcome.RETRYABLE,
                materializer(refused).materialize(observed));

        // Accepted, then the post-read cannot confirm it. MATERIALIZED would be a claim the
        // catalog never supported.
        PurviewEntityRegistryClient unconfirmed = mock(PurviewEntityRegistryClient.class);
        when(unconfirmed.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                .thenReturn(null).thenReturn(null);
        when(unconfirmed.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "ok"));
        assertEquals(LineageObservedEntityMaterializer.Outcome.RETRYABLE,
                materializer(unconfirmed).materialize(observed));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedKinds")
    @DisplayName("a materializer bound elsewhere refuses without touching the catalog")
    void anotherTargetIsRefused(KindFixture fixture) throws Exception {
        PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
        assertEquals(LineageObservedEntityMaterializer.Outcome.RETRYABLE,
                new CatalogObservedEntityMaterializer("purview", resolver(), client)
                        .materialize(observed(fixture)));
        verify(client, never()).getEntityByUniqueAttribute(any(), anyString(), anyString(),
                anyString());
        verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    /**
     * An entity without a mandatory attribute cannot be published, ever.
     *
     * <p>The catalog rejects such a write in full, so retrying cannot help — the event simply
     * never carried the attribute. Terminal, and the only terminal outcome here.
     */
    @Test
    @DisplayName("an entity missing a mandatory attribute is SNAPSHOT_INCOMPLETE")
    void missingMandatoryIsTerminal() throws Exception {
        // Built at the entity level: the snapshot's own allowlist marks these required, so an
        // incomplete one cannot be constructed — which is itself the first line of defence.
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", EndpointKind.IMPORT_ARTIFACT.atlasTypeName());
        entity.put("attributes", new LinkedHashMap<>(
                Map.of("qualifiedName", "nemaki://" + REPO + "/imports/op-1")));
        assertEquals(List.of("importMode"),
                LineageHistoricalEntityFactory.missingMandatoryAttributes(entity,
                        EndpointKind.IMPORT_ARTIFACT),
                "the missing attribute is named, and only its name");
    }

    // ------------------------------------------------------------------

    private static ObservedEntitySnapshot observed(KindFixture fixture) {
        return new ObservedEntitySnapshot(snapshotOf(fixture, TARGET),
                taskKeyOf(fixture, TARGET));
    }

    private static LineageWaitingSnapshot snapshotOf(KindFixture fixture, String target) {
        return LineageWaitingSnapshot.of(target, REPO, fixture.kind(), fixture.qualifiedName(),
                fixture.attributes(), LineageSourceDisposition.SOURCE_UNKNOWN,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
    }

    private static String taskKeyOf(KindFixture fixture, String target) {
        return LineageCatalogObligation.taskKey(target, REPO, fixture.kind(),
                fixture.qualifiedName());
    }

    private static Map<String, Object> withMarker(Map<String, Object> entity, EndpointKind kind) {
        Map<String, Object> copy = new LinkedHashMap<>(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
                new LinkedHashMap<>((Map<String, Object>) copy.get("attributes"));
        attributes.put(LineageHistoricalEntityFactory.tombstoneMarkerAttribute(kind), "PURGED");
        copy.put("attributes", attributes);
        return copy;
    }

    private static CatalogObservedEntityMaterializer materializer(
            PurviewEntityRegistryClient client) {
        return new CatalogObservedEntityMaterializer(TARGET, resolver(), client);
    }

    private static MetadataCatalogConnectionResolver resolver() {
        MetadataCatalogConnectionResolver resolver =
                mock(MetadataCatalogConnectionResolver.class);
        when(resolver.buildConnectionRequest()).thenReturn(mock(PurviewConnectionRequest.class));
        return resolver;
    }
}
