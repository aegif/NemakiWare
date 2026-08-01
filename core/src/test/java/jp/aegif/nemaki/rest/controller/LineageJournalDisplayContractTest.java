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
package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.LineageAssetRef;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageDelivery;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageEventV2Builder;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineagePublishStatus;
import jp.aegif.nemaki.rest.purview.journal.LineageRecord;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The JSON the admin API emits for one journal record.
 *
 * <h2>What is being held still and what is being added</h2>
 *
 * <p>The shipped React UI and the Playwright spec read this shape, so every key a v1 response had
 * still has to be there with the same meaning. That is one half.
 *
 * <p>The other half is that keeping <em>only</em> those keys would make the page lie once a v2
 * record can reach it: {@code eventKey} would hold a {@code processKey}, and an empty
 * {@code snapshotAttributes} would suggest an event with no attributes rather than one whose
 * attributes moved onto its assets. So the version-neutral names are added alongside, and the old
 * ones are v1 aliases.
 */
public class LineageJournalDisplayContractTest {

    private LineageJournalController controller;
    private LineageJournalStore store;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LineageJournalController();
        store = mock(LineageJournalStore.class);
        LineageConfig config = mock(LineageConfig.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext context = mock(CallContext.class);
        when(request.getAttribute("CallContext")).thenReturn(context);
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);

        setField(controller, "journalStore", store);
        setField(controller, "lineageConfig", config);
        controller.setHttpRequest(request);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = LineageJournalController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static LineageEvent v1Event() {
        return new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .snapshotAttribute("requestedBy", "alice")
                .targets(List.of("purview"))
                .build();
    }

    private Map<String, Object> getEventBody(LineageEvent stored) {
        when(store.findByRecordId("id")).thenReturn(row(stored));
        ResponseEntity<Map<String, Object>> response = controller.getEvent("id");
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    // ------------------------------------------------------------------ v1 keys are unchanged

    /**
     * Every key the UI's {@code LineageEventSummary} declares, with the value it had before the
     * projection was introduced.
     */
    @Test
    public void everyV1KeyKeepsItsMeaning() {
        LineageEvent event = v1Event();
        Map<String, Object> body = getEventBody(event);

        assertEquals(event.eventId(), body.get("eventId"));
        assertEquals(event.eventKey(), body.get("eventKey"));
        assertEquals("bedroom", body.get("repositoryId"));
        assertEquals("ARCHIVE_LOCAL", body.get("processType"));
        assertEquals(event.occurredAt(), body.get("occurredAt"));
        assertEquals(List.of("nemaki://bedroom/objects/doc-1"), body.get("inputs"));
        assertEquals(List.of("nemaki://bedroom/archives/doc-1"), body.get("outputs"));
        assertEquals(Map.of("requestedBy", "alice"), body.get("snapshotAttributes"));
        assertEquals(Map.of("purview", "PENDING"), body.get("publishStatusByTarget"));
    }

    @Test
    public void theV1EventKeyAndProcessIdentityAgree() {
        Map<String, Object> body = getEventBody(v1Event());
        assertEquals(body.get("eventKey"), body.get("processIdentity"));
        assertEquals(1, body.get("schemaVersion"));
        assertEquals(1, body.get("idempotencyKeyVersion"));
    }

    /** v1's journal document is keyed by eventId, so that is the record id. */
    @Test
    public void theV1RecordIdIsTheEventId() {
        Map<String, Object> body = getEventBody(v1Event());
        assertEquals(body.get("eventId"), body.get("recordId"));
    }

    // ------------------------------------------------------------------ the added structure

    @Test
    public void aV1AssetIsReportedAsALegacyNameWithNoKind() {
        Map<String, Object> body = getEventBody(v1Event());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) body.get("inputAssets");

        assertEquals(1, inputs.size());
        assertEquals("nemaki://bedroom/objects/doc-1", inputs.get(0).get("qualifiedName"));
        assertEquals("LEGACY_NAME", inputs.get(0).get("resolution"));
        assertNull(inputs.get(0).get("kind"),
                "v1 writes objects/{id} for documents and folders alike; a kind here would be a"
                        + " guess, and wrong for one of them");
        assertEquals(Map.of(), inputs.get(0).get("attributes"));
    }

    /**
     * The structured form has to say <em>which</em> asset is unresolved and why, or an operator
     * looking at a publication that failed with "unresolved asset" has nowhere to go.
     */
    @Test
    public void anUnresolvedAssetCarriesItsReason() {
        Map<String, Object> asset = assetMapOf(new LineageAssetRef.Unresolved(
                "nemaki://bedroom/objects/gone", "SOURCE_PURGED"));
        assertEquals("UNRESOLVED", asset.get("resolution"));
        assertEquals("SOURCE_PURGED", asset.get("unresolvedReason"));
        assertEquals("nemaki://bedroom/objects/gone", asset.get("qualifiedName"));
    }

    @Test
    public void aTypedAssetCarriesItsKindAndAttributes() {
        Map<String, Object> asset = assetMapOf(new LineageAssetRef.Typed(
                LineageEndpoint.document("bedroom", "doc-1", "a.txt")));
        assertEquals("TYPED", asset.get("resolution"));
        assertEquals("CMIS_DOCUMENT", asset.get("kind"));
        assertEquals("nemaki_document", asset.get("atlasTypeName"));
        assertEquals(Map.of("name", "a.txt"), asset.get("attributes"));
    }

    /** Built through the real controller so the mapper under test is the shipped one. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> assetMapOf(LineageAssetRef ref) {
        LineageRecord base = LineageRecord.fromV1(v1Event());
        LineageRecord record = new LineageRecord(base.schemaVersion(),
                base.idempotencyKeyVersion(), base.recordId(), base.eventId(),
                base.processIdentity(), base.repositoryId(), base.processType(),
                base.occurredAt(), base.sequenceNumber(), base.correlationId(),
                List.of(ref), base.outputs(), base.publishStatusByTarget(),
                base.legacyEventAttributes());
        Map<String, Object> body = invokeRecordToMap(record);
        return ((List<Map<String, Object>>) body.get("inputAssets")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeRecordToMap(LineageRecord record) {
        try {
            var m = LineageJournalController.class
                    .getDeclaredMethod("recordToMap", LineageRecord.class);
            m.setAccessible(true);
            return (Map<String, Object>) m.invoke(controller, record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ a v2 record

    /**
     * Nothing writes v2 yet, so this is the only way to see what the page would show. The point is
     * that {@code processIdentity} is the processKey and {@code recordId} is the deliveryId —
     * neither of which the v1 key names could express.
     */
    @Test
    public void aV2RecordReportsItsOwnIdentitiesUnderTheNeutralNames() {
        var event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .build();

        Map<String, Object> body = invokeRecordToMap(LineageRecord.fromV2(event));

        assertEquals(2, body.get("schemaVersion"));
        assertEquals(2, body.get("idempotencyKeyVersion"));
        assertEquals(event.processKey(), body.get("processIdentity"));
        assertEquals(event.deliveryId(), body.get("recordId"));
        assertEquals(event.eventId(), body.get("eventId"));
        assertEquals(Map.of(), body.get("snapshotAttributes"),
                "a v2 event has no event-level attributes; they are on the assets");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) body.get("inputAssets");
        assertEquals("TYPED", inputs.get(0).get("resolution"));
        assertEquals(Map.of("name", "a.txt"), inputs.get(0).get("attributes"));
    }

    /** The alias is what makes an unchanged client keep working; it is also what would lie. */
    @Test
    public void theV1AliasHoldsTheV2ProcessKeyWhichIsWhyProcessIdentityExists() {
        var event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .build();
        Map<String, Object> body = invokeRecordToMap(LineageRecord.fromV2(event));
        assertEquals(event.processKey(), body.get("eventKey"));
    }

    // ------------------------------------------------------------------ a row that cannot project

    /**
     * {@link LineageEvent} turns a null identifier into an empty string and the CouchDB decoder
     * does not check, so rows written before those fields mattered can exist.
     * {@link LineageRecord} rejects a blank record id — correctly, since it is what the projector
     * claims — which means one such row would have thrown and turned the whole list into a 500.
     * Before the projection it simply displayed with empty fields.
     */
    @Test
    public void aRowThatCannotBeProjectedDoesNotBreakTheRequest() {
        // Since 2d-2, decode failure happens in the STORE and arrives as a value; the controller
        // renders it as a diagnostic row. What identifies the row is now the document
        // coordinates, not payload fields — an undecodable row's payload is exactly the thing
        // that must not be copied around.
        when(store.findByRecordId("id")).thenReturn(brokenRow());
        ResponseEntity<Map<String, Object>> response = controller.getEvent("id");
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertEquals(Boolean.TRUE, body.get("unprojectable"));
        assertNotNull(body.get("unprojectableReason"));
        assertEquals("lineage:broken", body.get("documentId"),
                "the document coordinates are what lets an operator find the row");
        assertEquals("broken", body.get("recordId"));
        assertEquals("lineage_event", body.get("documentType"));
    }

    @Test
    public void oneBrokenRowDoesNotHideTheGoodOnesInAList() {
        LineageEvent good = v1Event();
        when(store.findAll(51, 0)).thenReturn(java.util.List.of(brokenRow(), row(good)));

        ResponseEntity<Map<String, Object>> response =
                controller.listEvents(null, null, null, null, 50, 0);
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) response.getBody().get("events");
        assertEquals(2, events.size());
        assertEquals(Boolean.TRUE, events.get(0).get("unprojectable"));
        assertFalse(events.get(1).containsKey("unprojectable"));
        assertEquals(good.eventKey(), events.get(1).get("eventKey"));
    }

    /** A healthy record must not carry the failure marker, or every row would look broken. */
    @Test
    public void aProjectableRowCarriesNoFailureMarker() {
        Map<String, Object> body = getEventBody(v1Event());
        assertFalse(body.containsKey("unprojectable"));
        assertFalse(body.containsKey("unprojectableReason"));
    }

    // ------------------------------------------------------------------ untouched paths

    /**
     * Slice 2b is display only. Replay still looks the event up as an envelope and resets the
     * original to PENDING, which is the v1 behaviour the design keeps until the replay command
     * path implements §3's compensation rule.
     */
    @Test
    public void replayStillResetsTheOriginalToPending() {
        LineageEvent event = v1Event();
        when(store.findByRecordId("id")).thenReturn(row(event));
        when(store.updatePublishStatus("id", "purview", LineagePublishStatus.PENDING))
                .thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.replayEvent("id", "purview");
        assertEquals("ok", response.getBody().get("status"));
        org.mockito.Mockito.verify(store)
                .updatePublishStatus("id", "purview", LineagePublishStatus.PENDING);
    }

    /**
     * The discard endpoint refuses an undecodable row for a harder reason than replay does:
     * discard is a terminal transition, terminal rows are purge-eligible, and the stored document
     * is the row's only evidence. The raw status flip would succeed — it needs no decode — so
     * this refusal is the only thing between one admin call and quiet evidence destruction.
     */
    @Test
    public void anUndecodableRowCannotBeDiscarded() {
        when(store.findByRecordId("broken")).thenReturn(brokenRow());

        ResponseEntity<Map<String, Object>> response = controller.discardEvent("broken", "purview");

        assertEquals(409, response.getStatusCode().value());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .discardEvent(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void anUndecodableRowCannotBeReplayed() {
        when(store.findByRecordId("broken")).thenReturn(brokenRow());

        ResponseEntity<Map<String, Object>> response = controller.replayEvent("broken", "purview");

        assertEquals(409, response.getStatusCode().value());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .updatePublishStatus(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void aMissingEventIsStillANotFound() {
        when(store.findByRecordId("nope")).thenReturn(null);
        assertEquals(404, controller.getEvent("nope").getStatusCode().value());
    }
    private static jp.aegif.nemaki.rest.purview.journal.LineageJournalRow row(LineageEvent event) {
        return new jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Decoded(
                jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry.ofV1(event));
    }

    /** What the store yields for a stored row whose identifiers are junk: a diagnostic value. */
    private static jp.aegif.nemaki.rest.purview.journal.LineageJournalRow brokenRow() {
        return new jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Undecodable(
                "lineage:broken", "lineage_event", 1, "recordId must not be null or blank");
    }

}
