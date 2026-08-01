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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link CouchLineageEventV2} and {@link LineageEventCodec} — the stored form of a v2 row and the
 * one entry point that reads either version.
 *
 * <p>The properties pinned here are the ones §6-a leans on: the distinct document type (old
 * binaries' views must be structurally unable to see v2), stored-identity re-verification on
 * every decode, and round-trip fidelity through real JSON — including the Integer/Long narrowing
 * Jackson performs, which is exactly the kind of drift a codec test that never leaves Java would
 * miss.
 */
public class LineageEventCodecTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    private static LineageEventV2Builder builder() {
        return new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview", "atlas")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L))
                .sequenceNumber(7L)
                .correlationId("corr-1");
    }

    private static LineageEventV2 original() {
        return builder().build();
    }

    private static LineageEventV2 replayOf(LineageEventV2 original) {
        return builder()
                .delivery(new LineageDelivery.Replay(original.deliveryId(), "purview", 2L))
                .build();
    }

    private static LineageEventV2 repair() {
        return builder()
                .delivery(new LineageDelivery.Repair("lineage_dl:abc", 1L))
                .repairTargets(List.of("purview"))
                .build();
    }

    // ------------------------------------------------------------------ round trips

    @Test
    public void everyDeliveryKindSurvivesTheRoundTrip() {
        LineageEventV2 original = original();
        for (LineageEventV2 event : List.of(original, replayOf(original), repair())) {
            assertEquals(event, CouchLineageEventV2.fromMap(CouchLineageEventV2.toMap(event)),
                    event.delivery().kind().name());
        }
    }

    /**
     * Through real JSON, not just Java maps. Jackson narrows small whole numbers to Integer, so a
     * COUNT attribute stored as Long comes back as Integer — the canonical hash treats the two
     * identically, meaning the digest check passes while record equality fails. The codec
     * normalises; this is the test that fails if it stops.
     */
    @Test
    public void theRoundTripSurvivesJacksonsNumberNarrowing() throws Exception {
        // objectCount=3 is small enough that Jackson parses it back as Integer.
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.exportArtifact(REPO, "op-1", "ZIP", "out.zip", 3L))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(CouchLineageEventV2.toMap(event));
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(json, Map.class);

        LineageEventV2 decoded = CouchLineageEventV2.fromMap(parsed);
        assertEquals(event, decoded);
        assertEquals(3L, decoded.outputs().get(0).attributes().get("objectCount"),
                "the small COUNT came back Integer from JSON and must be Long again");
    }

    @Test
    public void theDispatcherReturnsAConsistentEntryForV2() {
        LineageEventV2 event = original();
        LineageJournalEntry entry = LineageEventCodec.decode(CouchLineageEventV2.toMap(event));

        assertInstanceOf(LineageJournalEntry.V2.class, entry.envelope());
        assertEquals(event, ((LineageJournalEntry.V2) entry.envelope()).event());
        assertEquals(event.deliveryId(), entry.record().recordId());
        assertEquals(event.processKey(), entry.record().processIdentity());
    }

    @Test
    public void theDispatcherReadsAV1DocumentExactlyAsTheV1CodecDoes() {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .targets(List.of("purview"))
                .build();
        Map<String, Object> doc = new CouchLineageEvent(v1).toMap();

        LineageJournalEntry entry = LineageEventCodec.decode(doc);
        assertInstanceOf(LineageJournalEntry.V1.class, entry.envelope());
        assertEquals(v1, ((LineageJournalEntry.V1) entry.envelope()).event());
        assertEquals(v1.eventId(), entry.record().recordId());
    }

    /** Rows predating the schemaVersion field are v1 by definition: every v2 row carries a 2. */
    @Test
    public void aDocumentWithoutASchemaVersionDecodesAsV1() {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .targets(List.of("purview"))
                .build();
        Map<String, Object> doc = new CouchLineageEvent(v1).toMap();
        doc.remove("schemaVersion");

        assertInstanceOf(LineageJournalEntry.V1.class, LineageEventCodec.decode(doc).envelope());
    }

    /** Reading a newer version leniently is §6-a's stopping condition violated in reverse. */
    @Test
    public void aNewerSchemaVersionIsRefusedNotGuessedAt() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("schemaVersion", 3);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LineageEventCodec.decode(doc));
        assertTrue(thrown.getMessage().contains("newer binary"), thrown.getMessage());
    }

    // ------------------------------------------------------------------ the distinct type

    /**
     * The safety property from the review: old binaries select their views with
     * {@code doc.type === 'lineage_event'} and nothing else, so a v2 row under a distinct type is
     * structurally invisible to them — it cannot be claimed, published, or cursor-advanced by a
     * binary that predates v2.
     */
    @Test
    public void aV2DocumentNeverCarriesTheTypeOldBinariesSelectOn() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        assertEquals("lineage_event_v2", doc.get("type"));
        assertFalse("lineage_event".equals(doc.get("type")));
    }

    @Test
    public void aV2PayloadSmuggledUnderTheV1TypeIsRefused() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("type", "lineage_event");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc));
        assertTrue(thrown.getMessage().contains("old"), thrown.getMessage());
    }

    // ------------------------------------------------------------------ stored identity

    @Test
    public void aTamperedPayloadFailsDecodeBecauseTheDigestIsReVerified() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("operationId", "op-TAMPERED");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc));
        assertTrue(thrown.getMessage().contains("processKey")
                        || thrown.getMessage().contains("digest"),
                thrown.getMessage());
    }

    @Test
    public void aRowStoredUnderTheWrongKeyIsRefused() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("_id", "lineage:not-the-delivery-id");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc));
        assertTrue(thrown.getMessage().contains("_id"), thrown.getMessage());
    }

    /**
     * deliveryKind and the delivery sub-object must agree. Dispatching on the tag and then
     * requiring the tag's own fields makes the disagreement fail structurally: REPLAY's fields
     * are simply absent from an ORIGINAL's sub-object.
     */
    @Test
    public void aDeliveryKindDisagreeingWithItsSubObjectIsRefused() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("deliveryKind", "REPLAY");
        assertThrows(IllegalArgumentException.class, () -> CouchLineageEventV2.fromMap(doc));
    }

    @Test
    public void anUnknownDeliveryKindIsRefused() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("deliveryKind", "RESHIPMENT");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc));
        assertTrue(thrown.getMessage().contains("RESHIPMENT"), thrown.getMessage());
    }

    // ------------------------------------------------------------------ mutable-state corruption

    /**
     * v1's codec silently turns an unknown status into PENDING, which can re-publish an
     * already-published event. v2 fails closed — and the message classifies the damage: the
     * status map is outside creationPayloadDigest, so the immutable payload may be intact and the
     * row is repairable, not disposable.
     */
    @Test
    public void anUnknownPublishStatusFailsClosedAndSaysTheRowIsRepairable() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        @SuppressWarnings("unchecked")
        Map<String, String> status = (Map<String, String>) doc.get("publishStatusByTarget");
        status.put("purview", "PUBLISHED_MAYBE");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CouchLineageEventV2.fromMap(doc));
        assertTrue(thrown.getMessage().contains("PUBLISHED_MAYBE"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("repair"), thrown.getMessage());
    }

    /**
     * A status map can legitimately be absent (the field is optional in the document), and absent
     * means empty — not null, and not an error. Distinct from the unknown-VALUE case above.
     */
    @Test
    public void anAbsentStatusMapDecodesAsEmpty() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.remove("publishStatusByTarget");
        assertEquals(Map.of(), CouchLineageEventV2.fromMap(doc).publishStatusByTarget());
    }

    @Test
    public void aStatusMapThatIsNotAnObjectIsRefused() {
        Map<String, Object> doc = CouchLineageEventV2.toMap(original());
        doc.put("publishStatusByTarget", "PENDING");
        assertThrows(IllegalArgumentException.class, () -> CouchLineageEventV2.fromMap(doc));
    }

    @Test
    public void byContrastTheV1CodecStillDefaultsUnknownStatusToPending() {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .targets(List.of("purview"))
                .build();
        Map<String, Object> doc = new CouchLineageEvent(v1).toMap();
        @SuppressWarnings("unchecked")
        Map<String, String> status = (Map<String, String>) doc.get("publishStatusByTarget");
        status.put("purview", "PUBLISHED_MAYBE");

        LineageEvent decoded = new CouchLineageEvent(doc).toLineageEvent();
        assertEquals(LineagePublishStatus.PENDING,
                decoded.publishStatusByTarget().get("purview"),
                "the v1 leniency this codec deliberately does not inherit — kept as a fact here"
                        + " so the difference is visible in one place");
    }

    // ------------------------------------------------------------------ document shape

    /**
     * The exact key set of a v2 document, pinned. A key added or renamed here is a persistence
     * format change and has to be a deliberate act — stored rows outlive binaries.
     */
    @Test
    public void theV2DocumentShapeIsFrozen() {
        LineageEventV2 event = original();
        Map<String, Object> doc = CouchLineageEventV2.toMap(event);

        assertEquals(Set.of("_id", "type", "schemaVersion", "idempotencyKeyVersion", "eventId",
                        "processKey", "deliveryId", "deliveryKind", "delivery", "repositoryId",
                        "processType", "operationId", "occurredAt", "chunkIndex", "chunkCount",
                        "sequenceNumber", "correlationId", "inputs", "outputs",
                        "publishStatusByTarget", "creationPayloadDigest"),
                doc.keySet());
        assertEquals("lineage:" + event.deliveryId(), doc.get("_id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> delivery = (Map<String, Object>) doc.get("delivery");
        assertEquals(Set.of("targets"), delivery.keySet());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) doc.get("inputs");
        assertEquals(Set.of("kind", "repositoryId", "catalogQualifiedName", "objectId",
                "attributes"), inputs.get(0).keySet());
    }

    @Test
    public void absentAuditFieldsAreAbsentKeysNotNulls() {
        LineageEventV2 event = builder().correlationId(null).build();
        Map<String, Object> doc = CouchLineageEventV2.toMap(event);
        assertFalse(doc.containsKey("correlationId"));
        assertFalse(doc.containsKey("spoolRecordId"));
        assertFalse(doc.containsKey("legacyEventKey"));
    }

    @Test
    public void theRevAppearsOnlyWhenSupplied() {
        LineageEventV2 event = original();
        assertFalse(CouchLineageEventV2.toMap(event).containsKey("_rev"));
        assertEquals("3-abc", CouchLineageEventV2.toMap(event, "3-abc").get("_rev"));
        assertFalse(CouchLineageEventV2.toMap(event, " ").containsKey("_rev"));
    }

    // ------------------------------------------------------------------ the entry's invariant

    @Test
    public void anEntryPairingAProjectionWithTheWrongEnvelopeIsRefused() {
        LineageEventV2 a = original();
        LineageEventV2 b = builder().operationId("op-2").build();
        assertThrows(IllegalArgumentException.class,
                () -> new LineageJournalEntry(LineageRecord.fromV2(a),
                        new LineageJournalEntry.V2(b)));
    }

    @Test
    public void anEntryPairingAcrossVersionsIsRefused() {
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .targets(List.of("purview"))
                .build();
        LineageEventV2 v2 = original();
        assertThrows(IllegalArgumentException.class,
                () -> new LineageJournalEntry(LineageRecord.fromV1(v1),
                        new LineageJournalEntry.V2(v2)));
    }

    @Test
    public void theFactoriesProduceConsistentPairs() {
        LineageEventV2 event = original();
        LineageJournalEntry entry = LineageJournalEntry.ofV2(event);
        assertEquals(entry.record().recordId(),
                ((LineageJournalEntry.V2) entry.envelope()).event().deliveryId());
    }
}
