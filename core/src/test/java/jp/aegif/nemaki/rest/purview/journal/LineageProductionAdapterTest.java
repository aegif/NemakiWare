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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * The production adapters' contracts.
 *
 * <p>What is being pinned here is mostly what these must <em>refuse</em> to say: PUBLISHED
 * without a read-back, MATCH without the plan's own digest, PURGED without a ledger mark. Each
 * of those, said wrongly once, writes something into a catalog that nothing later removes.
 */
class LineageProductionAdapterTest {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";
    private static final String OBJECT_ID = "doc-42";
    private static final String QUALIFIED_NAME = "nemaki://bedroom/objects/doc-42";

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static HistoricalEntitySnapshot historical() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "a.txt");
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO,
                EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME, attributes,
                LineageSourceDisposition.SOURCE_PURGED,
                LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION);
        LineageSourceDispositionResolver.SourceEvidence evidence =
                LineageSourceDispositionResolver.SourceEvidence.of(REPO,
                        EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME,
                        LineageSourceDisposition.SOURCE_PURGED, OBJECT_ID, "rev-1", null, 1_000L);
        return new HistoricalEntitySnapshot(snapshot,
                LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_DOCUMENT,
                        QUALIFIED_NAME), evidence);
    }

    private static MetadataCatalogConnectionResolver resolver() {
        MetadataCatalogConnectionResolver resolver =
                mock(MetadataCatalogConnectionResolver.class);
        when(resolver.buildConnectionRequest()).thenReturn(mock(PurviewConnectionRequest.class));
        return resolver;
    }

    // ------------------------------------------------------------------

    @Nested
    class EntityFactory {

        @Test
        @DisplayName("a historical entity says PURGED, and carries the snapshot's attributes")
        void historicalEntityShape() {
            Map<String, Object> entity = LineageHistoricalEntityFactory.entityFor(historical());
            assertEquals(EndpointKind.CMIS_DOCUMENT.atlasTypeName(), entity.get("typeName"));
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) entity.get("attributes");
            assertEquals(QUALIFIED_NAME, attrs.get("qualifiedName"));
            assertEquals("a.txt", attrs.get("name"));
            assertEquals(PurviewEntityPayloadFactory.SOURCE_STATE_PURGED,
                    attrs.get(LineageHistoricalEntityFactory.LIFECYCLE_STATE),
                    "nemaki_document declares lifecycleState, not sourceState");
            // The type's mandatory identity attributes, which the per-kind allowlist does not
            // carry. Atlas rejects the write in full without them.
            assertEquals(REPO, attrs.get("repositoryId"));
            assertEquals(OBJECT_ID, attrs.get("objectId"));
        }

        /**
         * The marker name differs per type, and three types have none.
         *
         * <p>Atlas silently drops an attribute a type does not declare, so writing one name
         * everywhere produced entities with no tombstone marker at all — indistinguishable from
         * live objects. Pinned per kind because a summary would hide exactly that.
         */
        @Test
        @DisplayName("the tombstone marker name follows the type, and is absent for three")
        void tombstoneMarkerPerType() {
            assertEquals("lifecycleState", LineageHistoricalEntityFactory
                    .tombstoneMarkerAttribute(EndpointKind.CMIS_DOCUMENT));
            assertEquals("lifecycleState", LineageHistoricalEntityFactory
                    .tombstoneMarkerAttribute(EndpointKind.ARCHIVE));
            assertEquals("sourceState", LineageHistoricalEntityFactory
                    .tombstoneMarkerAttribute(EndpointKind.CMIS_FOLDER));
            // v2.3.58: the three types that declared neither marker gained lifecycleState
            // additively, so every emittable kind can now be tombstoned. A kind with no marker
            // would send well-formed snapshots to SNAPSHOT_INCOMPLETE for ever.
            for (EndpointKind kind : EndpointKind.values()) {
                assertNotNull(LineageHistoricalEntityFactory.tombstoneMarkerAttribute(kind),
                        kind + " must have somewhere to record that its source was destroyed");
            }
        }

        /** No marker means no write — an unmarked tombstone reads as a live object. */
        @Test
        @DisplayName("a kind with no marker is reported as missing a mandatory attribute")
        void noMarkerIsIncomplete() {
            // Its mandatory identity attributes are still required; only the marker gap closed.
            assertFalse(LineageHistoricalEntityFactory.missingMandatoryAttributes(
                    Map.of("attributes", Map.of()), EndpointKind.EXTERNAL_ASSET).isEmpty());
            assertTrue(LineageHistoricalEntityFactory.missingMandatoryAttributes(
                    LineageHistoricalEntityFactory.entityFor(historical()),
                    EndpointKind.CMIS_DOCUMENT).isEmpty());
        }

        /** A snapshot that cannot supply a mandatory attribute names it, and only its name. */
        @Test
        @DisplayName("a missing mandatory attribute is named, never its value")
        void missingMandatoryIsNamed() {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("typeName", "nemaki_document");
            entity.put("attributes", new LinkedHashMap<>(Map.of("repositoryId", REPO)));
            assertEquals(java.util.List.of("objectId"),
                    LineageHistoricalEntityFactory.missingMandatoryAttributes(entity,
                            EndpointKind.CMIS_DOCUMENT));
        }

        /** The digest must not depend on how the maps were built. */
        @Test
        @DisplayName("attribute order does not change the digest")
        void digestIsOrderIndependent() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("typeName", "t");
            Map<String, Object> attrsA = new LinkedHashMap<>();
            attrsA.put("z", "1");
            attrsA.put("a", "2");
            a.put("attributes", attrsA);

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("typeName", "t");
            Map<String, Object> attrsB = new LinkedHashMap<>();
            attrsB.put("a", "2");
            attrsB.put("z", "1");
            b.put("attributes", attrsB);

            assertEquals(LineageHistoricalEntityFactory.operationDigest(a),
                    LineageHistoricalEntityFactory.operationDigest(b));
        }

        /**
         * The catalog's own fields must not make a correct write look like a conflict.
         */
        @Test
        @DisplayName("the read-back projects onto the planned keys, ignoring catalog extras")
        void readBackIgnoresCatalogExtras() {
            Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(historical());
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("typeName", planned.get("typeName"));
            Map<String, Object> readAttrs = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> plannedAttrs = (Map<String, Object>) planned.get("attributes");
            readAttrs.putAll(plannedAttrs);
            readAttrs.put("guid", "e0a1");
            readAttrs.put("createTime", 1_700_000_000L);
            read.put("attributes", readAttrs);

            assertEquals(LineageHistoricalEntityFactory.operationDigest(planned),
                    LineageHistoricalEntityFactory.readBackDigest(read, planned));
        }

        @Test
        @DisplayName("a missing planned key is not the same as a null one")
        void absentIsNotNull() {
            Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(historical());
            @SuppressWarnings("unchecked")
            Map<String, Object> plannedAttrs = (Map<String, Object>) planned.get("attributes");

            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("typeName", planned.get("typeName"));
            Map<String, Object> withoutName = new LinkedHashMap<>(plannedAttrs);
            withoutName.remove("name");
            missing.put("attributes", withoutName);

            Map<String, Object> nulled = new LinkedHashMap<>();
            nulled.put("typeName", planned.get("typeName"));
            Map<String, Object> nullName = new LinkedHashMap<>(plannedAttrs);
            nullName.put("name", null);
            nulled.put("attributes", nullName);

            assertNotEquals(LineageHistoricalEntityFactory.readBackDigest(missing, planned),
                    LineageHistoricalEntityFactory.readBackDigest(nulled, planned));
        }

        @Test
        @DisplayName("the wrong type is a difference, not a match on attributes alone")
        void typeNameIsPartOfTheDigest() {
            Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(historical());
            Map<String, Object> read = new LinkedHashMap<>(planned);
            read.put("typeName", "some_other_type");
            assertNotEquals(LineageHistoricalEntityFactory.operationDigest(planned),
                    LineageHistoricalEntityFactory.readBackDigest(read, planned));
        }

        @Test
        @DisplayName("an unprojectable response is null, which the caller reads as UNKNOWN")
        void unprojectableIsNull() {
            Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(historical());
            assertNull(LineageHistoricalEntityFactory.readBackDigest(Map.of("nonsense", 1),
                    planned));
            assertNull(LineageHistoricalEntityFactory.readBackDigest(null, planned));
        }

        @Test
        @DisplayName("the client's {\"entity\": {...}} wrapper is unwrapped, not rejected")
        void unwrapsTheClientShape() {
            Map<String, Object> planned = LineageHistoricalEntityFactory.entityFor(historical());
            Map<String, Object> wrapped = Map.of("entity", planned);
            assertEquals(LineageHistoricalEntityFactory.operationDigest(planned),
                    LineageHistoricalEntityFactory.readBackDigest(wrapped, planned));
        }
    }

    @Nested
    class HistoricalPublisher {

        @Test
        @DisplayName("PUBLISHED is only said after the catalog was read back")
        void publishedRequiresReadBack() throws Exception {
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));
            HistoricalEntitySnapshot snapshot = historical();
            Map<String, Object> written = LineageHistoricalEntityFactory.entityFor(snapshot);
            when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenReturn(written);

            LineageHistoricalPublishReceipt receipt =
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), client)
                            .publishHistorical(snapshot);

            assertEquals(LineageHistoricalEntityPublisher.Outcome.PUBLISHED, receipt.outcome());
            assertEquals(LineageCatalogEntityProbe.Presence.PRESENT, receipt.readBackVerdict());
            assertEquals(LineageHistoricalEntityFactory.operationDigest(written),
                    receipt.operationDigest());
        }

        /** A 2xx says the request was accepted, not that the entity is there. */
        @Test
        @DisplayName("an accepted write that reads back ABSENT is retryable, not published")
        void acceptedButAbsentIsRetryable() throws Exception {
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));
            when(client.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenReturn(null);

            assertEquals(LineageHistoricalEntityPublisher.Outcome.RETRYABLE,
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), client)
                            .publishHistorical(historical()).outcome());
        }

        @Test
        @DisplayName("a publisher bound to another target refuses without calling the catalog")
        void refusesAnotherTarget() throws Exception {
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
            assertEquals(LineageHistoricalEntityPublisher.Outcome.RETRYABLE,
                    new CatalogHistoricalEntityPublisher("purview", resolver(), client)
                            .publishHistorical(historical()).outcome());
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("the four read-back verdicts, and none of them collapses")
        void readBackVerdicts() throws Exception {
            HistoricalEntitySnapshot snapshot = historical();
            Map<String, Object> written = LineageHistoricalEntityFactory.entityFor(snapshot);
            String planned = LineageHistoricalEntityFactory.operationDigest(written);

            PurviewEntityRegistryClient absent = mock(PurviewEntityRegistryClient.class);
            when(absent.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenReturn(null);
            assertEquals(LineageHistoricalReadBack.ABSENT,
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), absent)
                            .readBackHistorical(snapshot, planned));

            PurviewEntityRegistryClient match = mock(PurviewEntityRegistryClient.class);
            when(match.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenReturn(written);
            assertEquals(LineageHistoricalReadBack.MATCH,
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), match)
                            .readBackHistorical(snapshot, planned));

            PurviewEntityRegistryClient other = mock(PurviewEntityRegistryClient.class);
            Map<String, Object> different = new LinkedHashMap<>(written);
            Map<String, Object> otherAttrs = new LinkedHashMap<>();
            otherAttrs.put("qualifiedName", QUALIFIED_NAME);
            otherAttrs.put("name", "somebody-elses.txt");
            different.put("attributes", otherAttrs);
            when(other.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenReturn(different);
            assertEquals(LineageHistoricalReadBack.CONFLICT,
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), other)
                            .readBackHistorical(snapshot, planned));

            PurviewEntityRegistryClient broken = mock(PurviewEntityRegistryClient.class);
            when(broken.getEntityByUniqueAttribute(any(), anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("catalog unreachable"));
            assertEquals(LineageHistoricalReadBack.UNKNOWN,
                    new CatalogHistoricalEntityPublisher(TARGET, resolver(), broken)
                            .readBackHistorical(snapshot, planned),
                    "an unreachable catalog is not an empty one");
        }
    }

    @Nested
    class SourceDisposition {

        private static LineagePurgeLedger emptyLedger() {
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(anyString(), any(), anyString())).thenReturn(Optional.empty());
            return ledger;
        }

        @Test
        @DisplayName("a live object is EXISTS, with its own revision")
        void liveObjectExists() {
            ContentService contentService = mock(ContentService.class);
            Document live = new Document();
            live.setId(OBJECT_ID);
            live.setChangeToken("ct-7");
            when(contentService.getContent(REPO, OBJECT_ID)).thenReturn(live);

            var evidence = new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                    contentService, emptyLedger(), () -> 5_000L)
                    .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);

            assertEquals(LineageSourceDisposition.SOURCE_EXISTS, evidence.disposition());
            assertEquals(OBJECT_ID, evidence.incarnation());
            assertEquals("ct-7", evidence.revision());
            assertTrue(evidence.describesSubject(REPO, EndpointKind.CMIS_DOCUMENT,
                    QUALIFIED_NAME));
        }

        /**
         * The single most important refusal in this file.
         *
         * <p>Not-found is compatible with a stale replica, a lagging index and an object that
         * exists. Reading it as PURGED writes a permanent tombstone for a live document.
         */
        @Test
        @DisplayName("not found with no ledger mark is UNKNOWN, never PURGED")
        void notFoundIsNotPurged() {
            ContentService contentService = mock(ContentService.class);
            when(contentService.getContent(anyString(), anyString())).thenReturn(null);

            var evidence = new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                    contentService, emptyLedger(), () -> 5_000L)
                    .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);

            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN, evidence.disposition());
            assertFalse(evidence.authorisesHistorical());
        }

        @Test
        @DisplayName("an unsuperseded ledger mark is PURGED")
        void ledgerMarkIsPurged() {
            ContentService contentService = mock(ContentService.class);
            when(contentService.getContent(anyString(), anyString())).thenReturn(null);
            String subject = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(REPO,
                    EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(REPO, EndpointKind.CMIS_DOCUMENT, subject))
                    .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO,
                            EndpointKind.CMIS_DOCUMENT, subject, OBJECT_ID, "rev-9", 900L,
                            null)));

            var evidence = new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                    contentService, ledger, () -> 5_000L)
                    .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);

            assertEquals(LineageSourceDisposition.SOURCE_PURGED, evidence.disposition());
            assertTrue(evidence.authorisesHistorical());
        }

        @Test
        @DisplayName("a mark superseded by a restore stops authorising a tombstone")
        void restoredMarkIsUnknown() {
            ContentService contentService = mock(ContentService.class);
            when(contentService.getContent(anyString(), anyString())).thenReturn(null);
            String subject = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(REPO,
                    EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(REPO, EndpointKind.CMIS_DOCUMENT, subject))
                    .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO,
                            EndpointKind.CMIS_DOCUMENT, subject, OBJECT_ID, "rev-9", 900L,
                            1_000L)));

            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                    new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                            contentService, ledger, () -> 5_000L)
                            .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME)
                            .disposition());
        }

        @Test
        @DisplayName("a repository read that throws is UNKNOWN and never reaches the ledger")
        void readFailureDoesNotConsultTheLedger() {
            ContentService contentService = mock(ContentService.class);
            when(contentService.getContent(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("couch is down"));
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);

            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                    new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                            contentService, ledger, () -> 5_000L)
                            .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME)
                            .disposition());
            // An old mark must not answer for an object whose live state could not be checked.
            verify(ledger, never()).find(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a ledger read that throws is UNKNOWN, not 'no mark'")
        void ledgerFailureIsUnknown() {
            ContentService contentService = mock(ContentService.class);
            when(contentService.getContent(anyString(), anyString())).thenReturn(null);
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(anyString(), any(), anyString()))
                    .thenThrow(new IllegalStateException("couch is down"));

            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                    new RepositorySourceDispositionResolver(EndpointKind.CMIS_DOCUMENT,
                            contentService, ledger, () -> 5_000L)
                            .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME)
                            .disposition());
        }

        @Test
        @DisplayName("a resolver refuses a kind it is not bound to")
        void refusesAnotherKind() {
            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                    new RepositorySourceDispositionResolver(EndpointKind.CMIS_FOLDER,
                            mock(ContentService.class), emptyLedger(), () -> 5_000L)
                            .dispositionOf(REPO, EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME)
                            .disposition());
        }

        /**
         * Every LEDGERED kind must be able to reach PURGED, or its obligations retry for ever.
         *
         * <p>Only the LEDGERED ones. A NON_PURGEABLE_BY_NEMAKI kind reaching PURGED would mean
         * acting on a mark that could only have come from a compensating cleanup — covered by
         * {@code nonPurgeableKindsNeverReturnPurged}.
         */
        @Test
        @DisplayName("every LEDGERED kind can reach PURGED through the ledger")
        void everyKindCanReachPurged() {
            for (EndpointKind kind : EndpointKind.values()) {
                if (!LineagePurgeLifecyclePolicy.canBePurged(kind)) {
                    continue;
                }
                String qualifiedName = switch (kind) {
                    case CMIS_FOLDER -> "nemaki://" + REPO + "/folders/f-1/dataset";
                    case ARCHIVE -> "nemaki://" + REPO + "/archives/a-1";
                    default -> QUALIFIED_NAME;
                };
                String subject = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(
                        REPO, kind, qualifiedName);
                LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
                when(ledger.find(REPO, kind, subject))
                        .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO, kind,
                                subject, "inc-1", "rev-1", 900L, null)));
                ContentService contentService = mock(ContentService.class);
                when(contentService.getContent(anyString(), anyString())).thenReturn(null);
                when(contentService.getArchive(anyString(), anyString())).thenReturn(null);

                assertEquals(LineageSourceDisposition.SOURCE_PURGED,
                        new RepositorySourceDispositionResolver(kind, contentService, ledger,
                                () -> 5_000L).dispositionOf(REPO, kind, qualifiedName)
                                .disposition(),
                        kind + " must be able to reach PURGED");
            }
        }

        @Test
        @DisplayName("a qualified name that is not one of ours resolves nothing")
        void foreignQualifiedName() {
            assertNull(RepositorySourceDispositionResolver.objectIdOf(REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://other-repo/objects/doc-42"));
            assertNull(RepositorySourceDispositionResolver.objectIdOf(REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://bedroom/objects/a/b"),
                    "a nested path must not resolve to its first segment");
            assertEquals("doc-42", RepositorySourceDispositionResolver.objectIdOf(REPO,
                    EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME));
            assertEquals("f-1", RepositorySourceDispositionResolver.objectIdOf(REPO,
                    EndpointKind.CMIS_FOLDER, "nemaki://bedroom/folders/f-1/dataset"));
        }
    }

    @Nested
    class Republisher {

        @Test
        @DisplayName("a repair writes the live object with sourceState ACTIVE")
        void repairsWithTheLiveObject() throws Exception {
            String subject = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(REPO,
                    EndpointKind.CMIS_DOCUMENT, QUALIFIED_NAME);
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(REPO, EndpointKind.CMIS_DOCUMENT, subject))
                    .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO,
                            EndpointKind.CMIS_DOCUMENT, subject, OBJECT_ID, "rev-1", 900L,
                            1_000L)));
            ContentService contentService = mock(ContentService.class);
            Content live = new Document();
            live.setId(OBJECT_ID);
            when(contentService.getContent(REPO, OBJECT_ID)).thenReturn(live);
            PurviewEntityPayloadFactory payloadFactory = mock(PurviewEntityPayloadFactory.class);
            Map<String, Object> built = new LinkedHashMap<>();
            built.put("typeName", "nemaki_document");
            built.put("attributes", new LinkedHashMap<>(Map.of("qualifiedName", QUALIFIED_NAME)));
            when(payloadFactory.buildDocumentEntity(REPO, live)).thenReturn(built);
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
            when(client.bulkCreateOrUpdateEntities(any(), any()))
                    .thenReturn(PurviewEntityPublishResult.success(1, "ok"));

            assertEquals(LineageCurrentEntityRepublisher.Outcome.REPUBLISHED,
                    new CatalogCurrentEntityRepublisher(resolver(), client, payloadFactory,
                            contentService, ledger)
                            .republishCurrent(TARGET, REPO, EndpointKind.CMIS_DOCUMENT, subject));
            assertEquals(PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE,
                    ((Map<?, ?>) built.get("attributes"))
                            .get(LineageHistoricalEntityFactory.SOURCE_STATE));
        }

        /** A mark that does not reproduce the digest could be about a different object. */
        @Test
        @DisplayName("a ledger mark that does not reproduce the subject digest repairs nothing")
        void refusesAMismatchedMark() throws Exception {
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO,
                            EndpointKind.CMIS_DOCUMENT, "some-digest", "a-different-object",
                            "rev-1", 900L, null)));
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);

            assertEquals(LineageCurrentEntityRepublisher.Outcome.SOURCE_UNKNOWN,
                    new CatalogCurrentEntityRepublisher(resolver(), client,
                            mock(PurviewEntityPayloadFactory.class), mock(ContentService.class),
                            ledger).republishCurrent(TARGET, REPO, EndpointKind.CMIS_DOCUMENT,
                                    "some-digest"));
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("no ledger mark repairs nothing rather than guessing")
        void noMarkRepairsNothing() throws Exception {
            LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
            when(ledger.find(anyString(), any(), anyString())).thenReturn(Optional.empty());
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);

            assertEquals(LineageCurrentEntityRepublisher.Outcome.SOURCE_UNKNOWN,
                    new CatalogCurrentEntityRepublisher(resolver(), client,
                            mock(PurviewEntityPayloadFactory.class), mock(ContentService.class),
                            ledger).republishCurrent(TARGET, REPO, EndpointKind.CMIS_DOCUMENT,
                                    "some-digest"));
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }

        @Test
        @DisplayName("an external kind is left to its own connector")
        void externalKindsAreNotRepairedHere() throws Exception {
            PurviewEntityRegistryClient client = mock(PurviewEntityRegistryClient.class);
            assertEquals(LineageCurrentEntityRepublisher.Outcome.SOURCE_UNKNOWN,
                    new CatalogCurrentEntityRepublisher(resolver(), client,
                            mock(PurviewEntityPayloadFactory.class), mock(ContentService.class),
                            mock(LineagePurgeLedger.class))
                            .republishCurrent(TARGET, REPO, EndpointKind.CLOUD_OBJECT, "d"));
            verify(client, never()).bulkCreateOrUpdateEntities(any(), any());
        }
    }

    /**
     * A kind nothing records purges for cannot be quietly emittable.
     *
     * <p>The marker attribute and the resolver bean both exist for every kind now. Neither
     * means a purge is ever written, and without one SOURCE_PURGED is unreachable — the
     * obligations retry for ever and the events waiting on them stall. Readiness must tell
     * "wireable" from "wired".
     */
    @Nested
    class LedgerLifecycleCoverage {

        /**
         * A kind NemakiWare never destroys must never produce a purge verdict.
         *
         * <p>Even with a ledger mark present. The only call sites that could write one for
         * these kinds are compensating cleanups for failed operations, so a mark that somehow
         * existed would describe a failure rather than a destruction — and acting on it would
         * tombstone an object that is still there.
         */
        @Test
        @DisplayName("a NON_PURGEABLE kind never returns PURGED, even with a ledger mark")
        void nonPurgeableKindsNeverReturnPurged() {
            for (EndpointKind kind : EndpointKind.values()) {
                if (LineagePurgeLifecyclePolicy.canBePurged(kind)) {
                    continue;
                }
                String qualifiedName = "nemaki://" + REPO + "/objects/x-" + kind;
                String subject = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(
                        REPO, kind, qualifiedName);
                LineagePurgeLedger ledger = mock(LineagePurgeLedger.class);
                when(ledger.find(anyString(), any(), anyString()))
                        .thenReturn(Optional.of(new LineagePurgeLedger.PurgeMark(REPO, kind,
                                subject, "inc-1", "rev-1", 900L, null)));
                ContentService contentService = mock(ContentService.class);
                when(contentService.getContent(anyString(), anyString())).thenReturn(null);

                assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                        new RepositorySourceDispositionResolver(kind, contentService, ledger,
                                () -> 5_000L).dispositionOf(REPO, kind, qualifiedName)
                                .disposition(),
                        kind + " is NON_PURGEABLE_BY_NEMAKI and must never say PURGED");
            }
        }

        /** Every kind is classified, and the two classes are the ones established. */
        @Test
        @DisplayName("every endpoint kind has a purge lifecycle classification")
        void everyKindIsClassified() {
            for (EndpointKind kind : EndpointKind.values()) {
                assertTrue(LineagePurgeLifecyclePolicy.of(kind).isPresent(),
                        kind + " must be classified — unclassified is red, not a default");
                assertFalse(LineagePurgeLifecyclePolicy.of(kind).get().reason().isBlank(),
                        kind + " must say why");
            }
            assertEquals(java.util.Set.of(EndpointKind.CMIS_DOCUMENT, EndpointKind.CMIS_FOLDER,
                    EndpointKind.ARCHIVE),
                    java.util.Arrays.stream(EndpointKind.values())
                            .filter(LineagePurgeLifecyclePolicy::canBePurged)
                            .collect(java.util.stream.Collectors.toSet()));
        }

        @Test
        @DisplayName("the ledger names the kinds a lifecycle actually writes marks for")
        void coveredKindsAreTheHookedOnes() {
            java.util.Set<EndpointKind> covered =
                    new CouchLineagePurgeLedger(null).lifecycleCoveredKinds();
            // ContentServiceImpl.destroyArchive / restoreArchive cover exactly these.
            assertEquals(java.util.Set.of(EndpointKind.CMIS_DOCUMENT, EndpointKind.CMIS_FOLDER,
                    EndpointKind.ARCHIVE), covered);
            // Coverage and classification must agree: every LEDGERED kind is hooked, and
            // every kind that is not hooked is one NemakiWare never destroys.
            for (EndpointKind kind : EndpointKind.values()) {
                assertEquals(LineagePurgeLifecyclePolicy.canBePurged(kind),
                        covered.contains(kind),
                        kind + ": ledger coverage must match its lifecycle classification");
            }
        }
    }

    @Nested
    class Budgets {

        @Test
        @DisplayName("each target reads its own configuration, and an unknown one gets none")
        void perTargetBudgets() {
            jp.aegif.nemaki.rest.purview.PurviewConfig purview =
                    mock(jp.aegif.nemaki.rest.purview.PurviewConfig.class);
            when(purview.getConnectTimeoutMs()).thenReturn(1_000);
            when(purview.getReadTimeoutMs()).thenReturn(9_000);
            AtlasConfig atlas = mock(AtlasConfig.class);
            when(atlas.getConnectTimeoutMs()).thenReturn(500);
            when(atlas.getReadTimeoutMs()).thenReturn(2_000);
            var provider = new ConfiguredLineageOperationBudgetProvider(purview, atlas);

            assertEquals(9_000L, provider.budgetFor("purview", EndpointKind.CMIS_DOCUMENT)
                    .orElseThrow().readTimeoutMs());
            assertEquals(2_000L, provider.budgetFor("atlas", EndpointKind.CMIS_DOCUMENT)
                    .orElseThrow().readTimeoutMs());
            assertTrue(provider.budgetFor("dataplex", EndpointKind.CMIS_DOCUMENT).isEmpty(),
                    "a target with no configuration of its own must not borrow another's");
        }

        @Test
        @DisplayName("a remote-shaped kind is budgeted as remote")
        void perKindSourceRecheck() {
            assertTrue(ConfiguredLineageOperationBudgetProvider
                    .sourceRecheckMs(EndpointKind.COLD_STORAGE)
                    > ConfiguredLineageOperationBudgetProvider
                            .sourceRecheckMs(EndpointKind.CMIS_DOCUMENT));
        }

        @Test
        @DisplayName("a configuration read that throws yields no budget, not a default")
        void unreadableConfigYieldsNoBudget() {
            AtlasConfig broken = mock(AtlasConfig.class);
            when(broken.getReadTimeoutMs()).thenThrow(new IllegalStateException("bad config"));
            assertTrue(new ConfiguredLineageOperationBudgetProvider(null, broken)
                    .budgetFor("atlas", EndpointKind.CMIS_DOCUMENT).isEmpty());
        }
    }
}
