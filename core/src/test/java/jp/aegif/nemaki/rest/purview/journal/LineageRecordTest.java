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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link LineageRecord} and {@link LineageAssetRef} — the version-neutral projection.
 *
 * <p>The properties worth pinning are the ones that make the eventual write flip inert: both
 * versions land in the same shape, direction survives, nothing is invented from a v1 string, and
 * the projection does not pretend to be a recovery payload.
 */
public class LineageRecordTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    private static LineageEvent v1() {
        return new LineageEvent(
                1, EVENT_ID, "bedroom:ARCHIVE_LOCAL:123:456", 7L, OCCURRED, REPO,
                LineageProcessType.ARCHIVE_LOCAL,
                List.of("nemaki://bedroom/objects/doc-1"),
                List.of("nemaki://bedroom/archives/doc-1"),
                "run-1", "corr-1", 1,
                Map.of("requestedBy", "alice", "reason", "retention"),
                Map.of("purview", LineagePublishStatus.PENDING));
    }

    private static LineageEventV2 v2() {
        return new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L))
                .sequenceNumber(7L)
                .correlationId("corr-1")
                .build();
    }

    // ------------------------------------------------------------------ both versions, one shape

    @Test
    public void bothVersionsProjectIntoTheSameType() {
        LineageRecord fromV1 = LineageRecord.fromV1(v1());
        LineageRecord fromV2 = LineageRecord.fromV2(v2());

        for (LineageRecord record : List.of(fromV1, fromV2)) {
            assertEquals(REPO, record.repositoryId());
            assertEquals(LineageProcessType.ARCHIVE_LOCAL, record.processType());
            assertEquals(OCCURRED, record.occurredAt());
            assertEquals(EVENT_ID, record.eventId());
            assertEquals(7L, record.sequenceNumber());
            assertEquals("corr-1", record.correlationId());
            assertEquals(1, record.inputs().size());
            assertEquals(1, record.outputs().size());
        }
    }

    /**
     * The whole point of the slice. A consumer written against this type runs the same code for
     * both versions, so the v2 path is exercised by v1 traffic from the day it lands rather than
     * first executing at the flip.
     */
    @Test
    public void theProjectionExposesNoVersionSpecificSubtype() {
        assertFalse(LineageRecord.class.isSealed(),
                "one record with two adapters, not a V1/V2 hierarchy — a hierarchy invites"
                        + " instanceof in the consumers, which is a v2 branch nothing runs"
                        + " until the flip");
        assertEquals(LineageRecord.class, LineageRecord.fromV1(v1()).getClass());
        assertEquals(LineageRecord.class, LineageRecord.fromV2(v2()).getClass());
    }

    // ------------------------------------------------------------------ direction

    /**
     * Every sink distinguishes the two sides, and Dataplex builds the input × output product. One
     * flat asset list could not reproduce that.
     */
    @Test
    public void directionSurvivesTheProjection() {
        LineageRecord record = LineageRecord.fromV2(v2());
        assertEquals(EndpointKind.CMIS_DOCUMENT,
                ((LineageAssetRef.Typed) record.inputs().get(0)).kind());
        assertEquals(EndpointKind.ARCHIVE,
                ((LineageAssetRef.Typed) record.outputs().get(0)).kind());
    }

    @Test
    public void allAssetsPutsInputsFirstAndKeepsBothSides() {
        LineageRecord record = LineageRecord.fromV1(v1());
        assertEquals(List.of(record.inputs().get(0), record.outputs().get(0)),
                record.allAssets());
    }

    /** Sides of unequal size, because a selected-objects export is many inputs to one artifact. */
    @Test
    public void allAssetsHandlesSidesOfDifferentSizes() {
        LineageEvent manyToOne = new LineageEvent(1, EVENT_ID, "k", 0L, OCCURRED, REPO,
                LineageProcessType.EXPORT_SELECTED_OBJECTS,
                List.of("nemaki://bedroom/objects/d1", "nemaki://bedroom/objects/d2",
                        "nemaki://bedroom/objects/d3"),
                List.of("nemaki://bedroom/exports/op-1"),
                "", "", 1, Map.of(), Map.of());
        LineageRecord record = LineageRecord.fromV1(manyToOne);
        assertEquals(4, record.allAssets().size());
        assertEquals(record.inputs(), record.allAssets().subList(0, 3));
        assertEquals(record.outputs(), record.allAssets().subList(3, 4));
    }

    // ------------------------------------------------------------------ v1 keeps its own shape

    @Test
    public void aV1AssetKeepsItsNameAndClaimsNoKind() {
        LineageRecord record = LineageRecord.fromV1(v1());
        LineageAssetRef input = record.inputs().get(0);
        assertInstanceOf(LineageAssetRef.LegacyName.class, input);
        assertEquals("nemaki://bedroom/objects/doc-1", input.qualifiedName());
        assertEquals(Map.of(), input.attributes());
    }

    @Test
    public void theV1ProcessIdentityIsTheEventKey() {
        LineageRecord record = LineageRecord.fromV1(v1());
        assertEquals("bedroom:ARCHIVE_LOCAL:123:456", record.processIdentity());
        assertEquals(1, record.idempotencyKeyVersion());
        assertFalse(record.isV2Identity());
    }

    @Test
    public void theV2ProcessIdentityIsTheProcessKey() {
        LineageEventV2 event = v2();
        LineageRecord record = LineageRecord.fromV2(event);
        assertEquals(event.processKey(), record.processIdentity());
        assertEquals(LineageIdentity.IDEMPOTENCY_KEY_VERSION, record.idempotencyKeyVersion());
        assertTrue(record.isV2Identity());
    }

    /** v1's journal document is keyed by eventId; v2's by deliveryId. */
    @Test
    public void theRecordIdIsWhateverIdentifiesTheJournalDocument() {
        assertEquals(EVENT_ID, LineageRecord.fromV1(v1()).recordId());
        LineageEventV2 event = v2();
        assertEquals(event.deliveryId(), LineageRecord.fromV2(event).recordId());
    }

    // ------------------------------------------------------------------ nothing is invented

    /**
     * v1 writes {@code objects/{id}} for documents and folders alike, so a kind derived from the
     * string would be wrong for one of them. Asserted rather than left to review, because
     * "classify it, it looks like a document" is the tempting change.
     */
    @Test
    public void noKindIsGuessedFromAV1QualifiedName() {
        LineageEvent folderShaped = new LineageEvent(1, EVENT_ID, "k", 0L, OCCURRED, REPO,
                LineageProcessType.EXPORT_ZIP_FOLDER,
                List.of("nemaki://bedroom/objects/folder-1"), List.of(),
                "", "", 1, Map.of(), Map.of());
        assertInstanceOf(LineageAssetRef.LegacyName.class,
                LineageRecord.fromV1(folderShaped).inputs().get(0));
    }

    /**
     * §2 v2.1 replaced the event-level snapshot precisely because Purview copies it onto every
     * asset. Carrying it as a named legacy field keeps the data without reproducing that.
     */
    @Test
    public void theV1EventSnapshotIsKeptAsideNotSpreadOntoTheAssets() {
        LineageRecord record = LineageRecord.fromV1(v1());
        assertEquals(Map.of("requestedBy", "alice", "reason", "retention"),
                record.legacyEventAttributes());
        for (LineageAssetRef ref : record.allAssets()) {
            assertEquals(Map.of(), ref.attributes(),
                    "an event-level fact is not an attribute of any one asset");
        }
    }

    @Test
    public void aV2RecordHasNoLegacyEventAttributes() {
        assertEquals(Map.of(), LineageRecord.fromV2(v2()).legacyEventAttributes());
    }

    @Test
    public void aV2AssetCarriesItsOwnAttributes() {
        LineageRecord record = LineageRecord.fromV2(v2());
        assertEquals(Map.of("name", "a.txt"), record.inputs().get(0).attributes());
        assertEquals("doc-1", record.outputs().get(0).attributes().get("originalObjectId"));
    }

    /** Copied, not recomputed: LineageEventV2 already verified them against A-1's formulas. */
    @Test
    public void v2IdentitiesAreCopiedVerbatim() {
        LineageEventV2 event = v2();
        LineageRecord record = LineageRecord.fromV2(event);
        assertEquals(event.processKey(), record.processIdentity());
        assertEquals(event.deliveryId(), record.recordId());
        assertEquals(event.inputs().get(0).catalogQualifiedName(),
                record.inputs().get(0).qualifiedName());
    }

    // ------------------------------------------------------------------ asset ref invariants

    @Test
    public void aTypedRefNeedsAnEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new LineageAssetRef.Typed(null));
    }

    @Test
    public void aLegacyNameNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> new LineageAssetRef.LegacyName(null));
        assertThrows(IllegalArgumentException.class, () -> new LineageAssetRef.LegacyName(" "));
    }

    /** An unresolved asset with no reason gives the operator nothing to act on. */
    @Test
    public void anUnresolvedRefNeedsAReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageAssetRef.Unresolved("nemaki://bedroom/objects/x", null));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageAssetRef.Unresolved("nemaki://bedroom/objects/x", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageAssetRef.Unresolved(null, "SOURCE_PURGED"));
        assertEquals("SOURCE_PURGED",
                new LineageAssetRef.Unresolved("nemaki://bedroom/objects/x", "SOURCE_PURGED")
                        .reason());
    }

    @Test
    public void everyAssetRefCaseIsAccountedFor() {
        assertTrue(LineageAssetRef.class.isSealed());
        assertEquals(3, LineageAssetRef.class.getPermittedSubclasses().length,
                "Typed / LegacyName / Unresolved — a fourth case needs a decision, not a default");
    }

    /**
     * An external asset's qualified name is reversible base64 of its stable key (§4), so no
     * rendering of a reference may contain it.
     *
     * <p>Asserting only the absence would be satisfied by rendering nothing at all, and a
     * reference that prints nothing is useless in the log it exists for. So both halves are
     * asserted: the secret is gone, and something that identifies the reference is there.
     */
    @Test
    public void noAssetRefPrintsItsQualifiedNameAndAllOfThemStillPrintSomething() {
        LineageEndpoint external =
                LineageEndpoint.externalAsset(REPO, "slack:super-secret-file-id", "slack");
        String secret = external.catalogQualifiedName();

        for (LineageAssetRef ref : List.of(
                new LineageAssetRef.Typed(external),
                new LineageAssetRef.LegacyName(secret),
                new LineageAssetRef.Unresolved(secret, "SOURCE_ERROR"))) {
            assertFalse(ref.toString().contains(secret), ref.getClass() + ": " + ref);
            assertFalse(ref.toString().contains("super-secret-file-id"), ref.toString());
        }

        assertEquals("Typed[" + external.describeQualifiedName() + "]",
                new LineageAssetRef.Typed(external).toString());
        assertEquals("LegacyName[" + LineageEndpoint.shortDigest(secret) + "]",
                new LineageAssetRef.LegacyName(secret).toString());
        assertEquals("Unresolved[" + LineageEndpoint.shortDigest(secret)
                        + ", reason=SOURCE_ERROR]",
                new LineageAssetRef.Unresolved(secret, "SOURCE_ERROR").toString());
    }

    // ------------------------------------------------------------------ record invariants

    @Test
    public void aRecordNeedsSomethingToClaim() {
        assertThrows(IllegalArgumentException.class, () -> record(null, REPO));
        assertThrows(IllegalArgumentException.class, () -> record(" ", REPO));
    }

    @Test
    public void aRecordNeedsARepository() {
        assertThrows(IllegalArgumentException.class, () -> record(EVENT_ID, null));
        assertThrows(IllegalArgumentException.class, () -> record(EVENT_ID, ""));
    }

    @Test
    public void nullCollectionsBecomeEmptyOnes() {
        LineageRecord record = new LineageRecord(1, 1, EVENT_ID, EVENT_ID, "k", REPO,
                LineageProcessType.ARCHIVE_LOCAL, OCCURRED, 0L, null,
                null, null, null, null);
        assertEquals(List.of(), record.inputs());
        assertEquals(List.of(), record.outputs());
        assertEquals(Map.of(), record.publishStatusByTarget());
        assertEquals(Map.of(), record.legacyEventAttributes());
    }

    @Test
    public void collectionsAreDefensivelyCopied() {
        LineageRecord record = LineageRecord.fromV1(v1());
        assertThrows(UnsupportedOperationException.class,
                () -> record.inputs().add(new LineageAssetRef.LegacyName("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> record.outputs().add(new LineageAssetRef.LegacyName("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> record.allAssets().add(new LineageAssetRef.LegacyName("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> record.legacyEventAttributes().put("k", "v"));
    }

    @Test
    public void aNullEnvelopeIsRejectedRatherThanProjected() {
        assertThrows(IllegalArgumentException.class, () -> LineageRecord.fromV1(null));
        assertThrows(IllegalArgumentException.class, () -> LineageRecord.fromV2(null));
    }

    /**
     * The projection is not a recovery payload, and the absence of a way back is the guarantee.
     * v2's identity depends on the delivery union, operationId, chunk coordinates and the creation
     * digest, none of which is here; a {@code toEvent()} would have to invent them.
     */
    @Test
    public void thereIsNoWayBackToAnEnvelope() {
        for (var method : LineageRecord.class.getDeclaredMethods()) {
            assertFalse(LineageEvent.class.equals(method.getReturnType())
                            || LineageEventV2.class.equals(method.getReturnType()),
                    "LineageRecord." + method.getName() + " returns an envelope; recovery must use"
                            + " a lossless version-tagged payload, not this projection");
        }
    }

    private static LineageRecord record(String recordId, String repositoryId) {
        return new LineageRecord(1, 1, recordId, EVENT_ID, "k", repositoryId,
                LineageProcessType.ARCHIVE_LOCAL, OCCURRED, 0L, null,
                List.of(), List.of(), Map.of(), Map.of());
    }
}
