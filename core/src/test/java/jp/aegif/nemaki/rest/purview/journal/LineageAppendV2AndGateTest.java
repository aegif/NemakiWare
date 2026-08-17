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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.sdk.core.service.exception.ConflictException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * Slice 3: {@code appendV2}'s exact-match idempotency, {@code REJECTED}'s purge exemption, and
 * the §7 pre-sink gate.
 */
public class LineageAppendV2AndGateTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    private static LineageEventV2 v2Event() {
        return new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1L))
                .build();
    }

    // ================================================================== appendV2

    private CouchLineageJournalStore store;
    private CloudantClientWrapper wrapper;
    private Cloudant rawClient;

    @BeforeEach
    void setUpStore() throws Exception {
        store = new CouchLineageJournalStore();
        wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_lineage");
        rawClient = mock(Cloudant.class, RETURNS_DEEP_STUBS);
        when(wrapper.getClient()).thenReturn(rawClient);
        set(store, "lineageClient", wrapper);
        set(store, "dbProvisioned", new AtomicBoolean(true));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void putConflicts() {
        // The exception is mocked (not constructed): ConflictException's constructor dissects a
        // real HTTP response, and building one inside the when() chain trips Mockito's
        // unfinished-stubbing detection anyway.
        ConflictException conflict = mock(ConflictException.class);
        when(rawClient.putDocument(any(com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.class))
                .execute()).thenThrow(conflict);
    }

    /**
     * Stubs the STRICT raw read (v2.3.18: 404/409/infrastructure are distinct answers) the
     * conflict path now uses: a map becomes an SDK Document; null becomes NotFoundException
     * ("the occupant vanished"), which is how CouchDB actually reports absence.
     */
    private void storedDocIs(LineageEventV2 event, Map<String, Object> doc) {
        String documentId = CouchLineageEventV2.documentId(event.deliveryId());
        if (doc == null) {
            when(rawClient.getDocument(org.mockito.ArgumentMatchers.argThat(
                    (com.ibm.cloud.cloudant.v1.model.GetDocumentOptions o) ->
                            o != null && documentId.equals(o.docId())))
                    .execute())
                    .thenThrow(org.mockito.Mockito.mock(
                            com.ibm.cloud.sdk.core.service.exception.NotFoundException.class));
            return;
        }
        com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                new com.ibm.cloud.cloudant.v1.model.Document();
        Map<String, Object> withoutMeta = new HashMap<>(doc);
        Object id = withoutMeta.remove("_id");
        Object rev = withoutMeta.remove("_rev");
        sdkDoc.setProperties(withoutMeta);
        sdkDoc.setId(id instanceof String i ? i : documentId);
        sdkDoc.setRev(rev instanceof String r ? r : "1-x");
        when(rawClient.getDocument(org.mockito.ArgumentMatchers.argThat(
                (com.ibm.cloud.cloudant.v1.model.GetDocumentOptions o) ->
                        o != null && documentId.equals(o.docId())))
                .execute().getResult())
                .thenReturn(sdkDoc);
    }

    @Test
    public void aFreshRowIsCreatedUnderItsDeliveryDerivedKey() {
        LineageEventV2 event = v2Event();
        store.appendV2(event); // deep-stubbed put succeeds by default
        verify(rawClient).putDocument(org.mockito.ArgumentMatchers.argThat(
                (com.ibm.cloud.cloudant.v1.model.PutDocumentOptions o) ->
                        o.docId().equals("lineage:" + event.deliveryId())));
    }

    /** §8-a stores the row explicitly unsequenced; the fenced sequencer (D-rest) owns the rest. */
    @Test
    public void theStoredRowCarriesAnExplicitUnsequencedState() {
        LineageEventV2 event = v2Event();
        store.appendV2(event);
        verify(rawClient).putDocument(org.mockito.ArgumentMatchers.argThat(
                (com.ibm.cloud.cloudant.v1.model.PutDocumentOptions o) ->
                        "UNSEQUENCED".equals(o.document().getProperties().get("state"))));
    }

    /** v2.3.18 ②: sequences are the fenced sequencer's alone — never accepted at append. */
    @Test
    public void aPreSequencedEventIsRefusedBeforeAnyWrite() {
        LineageEventV2 preSequenced = new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1L))
                .sequenceNumber(1)
                .build();
        assertThrows(IllegalArgumentException.class, () -> store.appendV2(preSequenced));
        verify(rawClient, org.mockito.Mockito.never()).putDocument(
                org.mockito.ArgumentMatchers.any(
                        com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.class));
    }

    /**
     * v2.3.18 ②: the occupant is DECODED, never digest-string-compared — a corrupt row whose
     * digest field happens to match must still be a collision, not an idempotent success.
     */
    @Test
    public void aCorruptOccupantWithAMatchingDigestStringIsStillAnIntegrityException() {
        LineageEventV2 event = v2Event();
        putConflicts();
        Map<String, Object> corrupt = new HashMap<>(CouchLineageEventV2.toMap(event));
        corrupt.put("operationId", "op-TAMPERED"); // content differs; digest string still "matches"
        storedDocIs(event, corrupt);

        assertThrows(LineageIntegrityException.class, () -> store.appendV2(event));
    }

    /** §3: a conflict whose stored digest matches is this event's own earlier attempt. */
    @Test
    public void aConflictWithTheSameDigestIsIdempotentSuccess() {
        LineageEventV2 event = v2Event();
        putConflicts();
        storedDocIs(event, new HashMap<>(CouchLineageEventV2.toMap(event)));

        store.appendV2(event); // no exception
    }

    @Test
    public void aConflictWithADifferentDigestIsAnIntegrityException() {
        LineageEventV2 event = v2Event();
        putConflicts();
        Map<String, Object> other = new HashMap<>(CouchLineageEventV2.toMap(event));
        other.put("creationPayloadDigest", "0".repeat(64));
        storedDocIs(event, other);

        LineageIntegrityException thrown = assertThrows(LineageIntegrityException.class,
                () -> store.appendV2(event));
        assertEquals(event.deliveryId(), thrown.recordId());
        assertEquals(event.creationPayloadDigest(), thrown.expectedDigest());
        assertEquals("0".repeat(64), thrown.storedDigest());
    }

    /** A v1 row under the key has no digest and is not the same record. */
    @Test
    public void aConflictWithAV1RowUnderTheSameKeyIsAnIntegrityException() {
        LineageEventV2 event = v2Event();
        putConflicts();
        Map<String, Object> v1Doc = new HashMap<>();
        v1Doc.put("type", "lineage_event");
        v1Doc.put("schemaVersion", 1);
        storedDocIs(event, v1Doc);

        LineageIntegrityException thrown = assertThrows(LineageIntegrityException.class,
                () -> store.appendV2(event));
        assertEquals(null, thrown.storedDigest());
    }

    /**
     * Conflict, then the conflicting document is gone before it can be read: the world changed —
     * retry once; if it happens again, fail as transient contention (NOT as an integrity error:
     * absence proves nothing about content).
     */
    @Test
    public void aConflictWhoseDocumentVanishesTwiceFailsAsTransientNotIntegrity() {
        LineageEventV2 event = v2Event();
        putConflicts();
        storedDocIs(event, null);

        // assertThrows with the exact class already proves it is not the integrity exception:
        // LineageIntegrityException extends RuntimeException, not IllegalStateException.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> store.appendV2(event));
        assertTrue(thrown.getMessage().contains("vanished"), thrown.getMessage());
    }

    @Test
    public void aNullEventIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.appendV2(null));
    }

    // ================================================================== REJECTED

    @Test
    public void rejectedIsTerminalButNotPurgeEligible() {
        assertTrue(LineagePublishStatus.REJECTED.isTerminal(),
                "the projector owes this target nothing further");
        assertFalse(LineagePublishStatus.REJECTED.isPurgeEligible(),
                "but the document is evidence until increment E's durable record exists");
    }

    @Test
    public void everyOtherTerminalStatusRemainsPurgeEligible() {
        // The evidence states: terminal for the projector, but their documents carry the only
        // durable record of a violation/mismatch (REJECTED since A-1; UNPROJECTABLE and
        // UNRESOLVED since D-rest-2's §8-b machine).
        var evidenceStates = java.util.Set.of(LineagePublishStatus.REJECTED,
                LineagePublishStatus.UNPROJECTABLE, LineagePublishStatus.UNRESOLVED);
        for (LineagePublishStatus status : LineagePublishStatus.values()) {
            if (evidenceStates.contains(status)) {
                assertEquals(true, status.isTerminal(), status + " is terminal");
                assertEquals(false, status.isPurgeEligible(),
                        status + " is evidence and must not purge");
                continue;
            }
            assertEquals(status.isTerminal(), status.isPurgeEligible(),
                    status + ": terminal and purge-eligible part ways only at the evidence"
                            + " states");
        }
    }

    // ================================================================== the pre-sink gate

    private LineageProjectionLoop loop;
    private LineageJournalStore journalStore;
    private LineageTargetSink sink;
    private LineageDeadLetterStore deadLetters;
    private ProjectionCursorStore cursorStore;

    private void setUpLoop(boolean ordered) throws Exception {
        loop = new LineageProjectionLoop();
        journalStore = mock(LineageJournalStore.class);
        sink = mock(LineageTargetSink.class);
        deadLetters = mock(LineageDeadLetterStore.class);
        LineageConfig config = mock(LineageConfig.class);

        when(journalStore.isActive()).thenReturn(true);
        when(config.getTargets()).thenReturn(List.of("purview"));
        when(config.getProjectionBatchSize()).thenReturn(50);
        when(sink.targetName()).thenReturn("purview");
        when(sink.isAvailable()).thenReturn(true);
        when(journalStore.findByTargetAndStatus(anyString(), any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        set(loop, "lineageConfig", config);
        set(loop, "journalStore", journalStore);
        set(loop, "targetSinks", List.of(sink));
        LineageDeadLetterSink.setStore(deadLetters);

        if (ordered) {
            cursorStore = mock(ProjectionCursorStore.class);
            set(loop, "cursorStore", cursorStore);
            when(cursorStore.isActive()).thenReturn(true);
            when(cursorStore.getAllCursors()).thenReturn(List.of());
            when(journalStore.findDistinctNonTerminalRepositoryIds("purview"))
                    .thenReturn(List.of(REPO));
            when(cursorStore.getCursor("purview", REPO)).thenReturn(null);
        }
    }

    /**
     * An entry whose record claims a different repository than its envelope's endpoints — the
     * pairing {@link LineageJournalEntry}'s constructor does not cross-check, and exactly what a
     * last gate exists to stop.
     */
    private static LineageJournalEntry inconsistentEntry() {
        LineageEventV2 event = v2Event();
        LineageRecord honest = LineageRecord.fromV2(event);
        LineageRecord tampered = new LineageRecord(honest.schemaVersion(),
                honest.idempotencyKeyVersion(), honest.recordId(), honest.eventId(),
                honest.processIdentity(), "canopy", honest.processType(), honest.occurredAt(),
                honest.sequenceNumber(), honest.correlationId(), honest.inputs(),
                honest.outputs(), honest.publishStatusByTarget(),
                honest.legacyEventAttributes());
        return new LineageJournalEntry(tampered, new LineageJournalEntry.V2(event));
    }

    @Test
    public void theGateRejectsAfterTheClaimAndTheSinkNeverSeesTheRecord() throws Exception {
        setUpLoop(false);
        LineageJournalEntry entry = inconsistentEntry();
        String recordId = entry.record().recordId();
        when(journalStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(new LineageJournalRow.Decoded(entry)));
        when(journalStore.updatePublishStatus(recordId, "purview",
                LineagePublishStatus.PROJECTING)).thenReturn(1);
        when(journalStore.updatePublishStatus(recordId, "purview",
                LineagePublishStatus.REJECTED)).thenReturn(1);

        loop.pollAndProject();

        verify(sink, never()).publish(any());
        verify(journalStore).updatePublishStatus(recordId, "purview",
                LineagePublishStatus.PROJECTING);
        verify(journalStore).updatePublishStatus(recordId, "purview",
                LineagePublishStatus.REJECTED);
        // REJECTED, not FAILED and not dead-lettered: the failure is the data's, and retrying
        // cannot change the data.
        verify(journalStore, never()).updatePublishStatus(recordId, "purview",
                LineagePublishStatus.FAILED);
        verify(deadLetters, never()).record(any(), anyString());
    }

    @Test
    public void aConsistentV2EntryPassesTheGateUntouched() throws Exception {
        setUpLoop(false);
        LineageEventV2 event = v2Event();
        when(journalStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(new LineageJournalRow.Decoded(LineageJournalEntry.ofV2(event))));
        when(journalStore.updatePublishStatus(event.deliveryId(), "purview",
                LineagePublishStatus.PROJECTING)).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        verify(sink).publish(any(LineageRecord.class));
        verify(journalStore, never()).updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.REJECTED));
    }

    /**
     * Ordered: a confirmed REJECTED is a terminal decision, so the cursor advances over it and
     * the repository continues to the next row.
     */
    @Test
    public void inOrderedProjectionAConfirmedRejectionAdvancesTheCursorAndContinues()
            throws Exception {
        setUpLoop(true);
        LineageJournalEntry bad = inconsistentEntry();
        LineageEventV2 next = new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-2")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(REPO, "doc-2", "b.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "arc-2", "doc-2", 1L))
                .sequenceNumber(8L)
                .build();
        when(journalStore.findByRepositoryAndSequenceRange(REPO, 0L, 50))
                .thenReturn(List.of(new LineageJournalRow.Decoded(bad),
                        new LineageJournalRow.Decoded(LineageJournalEntry.ofV2(next))));
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.PROJECTING))).thenReturn(1);
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.REJECTED))).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        // The bad row was rejected, its sequence position released, and the next row published.
        verify(cursorStore, org.mockito.Mockito.times(2)).updateCursor(any());
        verify(journalStore).updatePublishStatus(next.deliveryId(), "purview",
                LineagePublishStatus.PUBLISHED);
    }

    /** An unconfirmed rejection is an uncertainty, and the ordered path halts on uncertainty. */
    @Test
    public void inOrderedProjectionAnUnconfirmedRejectionHalts() throws Exception {
        setUpLoop(true);
        LineageJournalEntry bad = inconsistentEntry();
        when(journalStore.findByRepositoryAndSequenceRange(REPO, 0L, 50))
                .thenReturn(List.of(new LineageJournalRow.Decoded(bad)));
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.PROJECTING))).thenReturn(1);
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.REJECTED))).thenReturn(0);

        loop.pollAndProject();

        verify(sink, never()).publish(any());
        verify(cursorStore, never()).updateCursor(any());
    }

    /** v1 rows carry no typed endpoints; the gate is a v2 contract and lets them through. */
    @Test
    public void aV1EntryIsNotGated() throws Exception {
        setUpLoop(false);
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1")
                .targets(List.of("purview"))
                .build();
        when(journalStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(v1))));
        when(journalStore.updatePublishStatus(v1.eventId(), "purview",
                LineagePublishStatus.PROJECTING)).thenReturn(1);
        when(sink.publish(any())).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        verify(sink).publish(any(LineageRecord.class));
        verify(journalStore, never()).updatePublishStatus(anyString(), anyString(),
                eq(LineagePublishStatus.REJECTED));
    }
}
