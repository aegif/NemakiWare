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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.rest.purview.journal.LineageTargetLifecycle.TerminalReason;

/**
 * §8-b v2 (D-rest-2): the per-target lifecycle shapes, the strict decode, the
 * field-preserving mutations, and the store's fenced transition table.
 */
public class LineageV2TransitionMachineTest {

    private static final String TARGET = "atlas";

    private static LineageEventV2 v2Event() {
        return new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of(TARGET)))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .sequenceNumber(7L)
                .build();
    }

    /** A stored SEQUENCED v2 doc with the given lifecycle fields for {@link #TARGET}. */
    private static Map<String, Object> sequencedDoc(String status, Map<String, Object> claim,
                                                    Map<String, Object> reason) {
        Map<String, Object> doc = new LinkedHashMap<>(CouchLineageEventV2.toMap(v2Event()));
        doc.put("_rev", "3-abc");
        doc.put("state", "SEQUENCED");
        doc.put("sequencerGeneration", 1L);
        doc.put("sequencerLeaseToken", "seq-tok");
        if (status != null) {
            doc.put("publishStatusByTarget", new LinkedHashMap<>(Map.of(TARGET, status)));
        }
        if (claim != null) {
            doc.put("v2ClaimByTarget", new LinkedHashMap<>(Map.of(TARGET, claim)));
        }
        if (reason != null) {
            doc.put("v2TerminalReasonByTarget", new LinkedHashMap<>(Map.of(TARGET, reason)));
        }
        return doc;
    }

    private static Map<String, Object> liveClaim(Long verifyingSince) {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("token", "tok-1");
        claim.put("claimedAtMs", 1000L);
        claim.put("leaseExpiresAtMs", System.currentTimeMillis() + 60_000L);
        if (verifyingSince != null) {
            claim.put("verifyingSinceMs", verifyingSince);
        }
        claim.put("retryCount", 0L);
        return claim;
    }

    // ================================================================ lifecycle shapes

    @Nested
    class LifecycleShapes {

        private LineageTargetLifecycle lc(LineagePublishStatus s, String token, Long claimedAt,
                                          Long lease, Long since, Long retry,
                                          TerminalReason reason) {
            return new LineageTargetLifecycle(s, token, claimedAt, lease, since, retry, reason);
        }

        @Test
        public void theClaimBundleIsAllOrNothing() {
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.FAILED, "tok", 1L, null, null, null, null),
                    "token without retryCount is a partial bundle");
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.FAILED, null, null, null, null, 0L, null),
                    "retryCount without token is a partial bundle");
        }

        @Test
        public void liveStatesRequireTokenAndLease() {
            assertNotNull(lc(LineagePublishStatus.PROJECTING, "tok", 1L, 2L, null, 0L, null));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.PROJECTING, "tok", 1L, null, null, 0L, null));
            assertNotNull(lc(LineagePublishStatus.VERIFYING, "tok", 1L, 2L, 3L, 0L, null));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.VERIFYING, "tok", 1L, 2L, null, 0L, null),
                    "VERIFYING without verifyingSince");
        }

        @Test
        public void failedDistinguishesStageByTheVerifyMarker() {
            assertNotNull(lc(LineagePublishStatus.FAILED, "tok", 1L, null, null, 0L, null),
                    "failed in PROJECTING: no marker");
            assertNotNull(lc(LineagePublishStatus.FAILED, "tok", 1L, null, 3L, 0L, null),
                    "failed after entering VERIFYING: marker retained");
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.FAILED, "tok", 1L, 5L, null, 0L, null),
                    "FAILED must not hold a live lease");
        }

        @Test
        public void publishedCameThroughVerifying() {
            assertNotNull(lc(LineagePublishStatus.PUBLISHED, "tok", 1L, null, 3L, 0L, null));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.PUBLISHED, "tok", 1L, null, null, 0L, null),
                    "PUBLISHED without the verify marker did not verify");
        }

        @Test
        public void rejectedHasExactlyTwoProvenances() {
            TerminalReason reason = new TerminalReason("PRE_SINK_GATE", "detail", 9L);
            assertNotNull(lc(LineagePublishStatus.REJECTED, "tok", 1L, null, null, 0L, reason),
                    "gate-rejected: bundle present");
            assertNotNull(lc(LineagePublishStatus.REJECTED, null, null, null, null, null, reason),
                    "creation-time rejected: no bundle");
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.REJECTED, "tok", 1L, null, 3L, 0L, reason),
                    "REJECTED never carries the verify marker (gate fires in PROJECTING)");
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.REJECTED, "tok", 1L, null, null, 0L, null),
                    "REJECTED requires the reason");
        }

        @Test
        public void reasonIsRequiredInExactlyTheThreeReasonStatesAndForbiddenElsewhere() {
            TerminalReason reason = new TerminalReason("R", "", 9L);
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.UNPROJECTABLE, "tok", 1L, null, 3L, 0L, null));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.UNRESOLVED, null, null, null, null, null, null));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.PENDING, null, null, null, null, null, reason));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.PUBLISHED, "tok", 1L, null, 3L, 0L, reason));
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.DISCARDED, "tok", 1L, null, null, 0L, reason));
        }

        /** Round-2 fix 3: the stored detail never exceeds MAX_DETAIL_LENGTH, marker included. */
        @Test
        public void terminalReasonDetailIsBoundedWithAVisibleMarker() {
            String longDetail = "x".repeat(2000);
            TerminalReason r = new TerminalReason("R", longDetail, 1L);
            assertTrue(r.detail().length() <= TerminalReason.MAX_DETAIL_LENGTH,
                    "stored length is the contract, marker included");
            assertTrue(r.detail().endsWith("…[truncated]"), "truncation is visible");
            TerminalReason exact = new TerminalReason("R",
                    "y".repeat(TerminalReason.MAX_DETAIL_LENGTH), 1L);
            assertEquals(TerminalReason.MAX_DETAIL_LENGTH, exact.detail().length(),
                    "exactly-at-bound is stored untouched");
            assertThrows(IllegalArgumentException.class, () ->
                    new TerminalReason("r".repeat(TerminalReason.MAX_REASON_LENGTH + 1),
                            "", 1L));
        }

        @Test
        public void hasLiveClaimIsExactlyTheTwoLiveStates() {
            assertTrue(lc(LineagePublishStatus.PROJECTING, "tok", 1L, 2L, null, 0L, null)
                    .hasLiveClaim());
            assertTrue(lc(LineagePublishStatus.VERIFYING, "tok", 1L, 2L, 3L, 0L, null)
                    .hasLiveClaim());
            assertFalse(lc(LineagePublishStatus.FAILED, "tok", 1L, null, null, 0L, null)
                    .hasLiveClaim());
            assertFalse(lc(LineagePublishStatus.PENDING, null, null, null, null, null, null)
                    .hasLiveClaim());
        }

        @Test
        public void skippedIsNeverLegalOnAV2Row() {
            assertThrows(IllegalArgumentException.class, () ->
                    lc(LineagePublishStatus.SKIPPED, null, null, null, null, null, null));
        }
    }

    // ================================================================ strict decode

    @Nested
    class StrictDecode {

        @Test
        public void aFullLifecycleDecodesTyped() {
            LineageJournalRowV2 row = CouchLineageJournalRowV2.fromRaw(
                    sequencedDoc("VERIFYING", liveClaim(1500L), null));
            LineageTargetLifecycle lc = row.targetLifecycles().get(TARGET);
            assertEquals(LineagePublishStatus.VERIFYING, lc.status());
            assertEquals("tok-1", lc.claimToken());
            assertEquals(1500L, lc.verifyingSinceMs());
            assertEquals(0L, lc.retryCount());
        }

        @Test
        public void aClaimWithoutAStatusIsMalformed() {
            Map<String, Object> doc = sequencedDoc(null, liveClaim(null), null);
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(doc));
        }

        @Test
        public void aContradictoryShapeNeverBecomesAValue() {
            // VERIFYING without verifyingSince
            Map<String, Object> claim = liveClaim(null);
            Map<String, Object> doc = sequencedDoc("VERIFYING", claim, null);
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(doc));
        }

        @Test
        public void anUnknownStatusIsRefusedLoudly() {
            Map<String, Object> doc = sequencedDoc("HALF_DONE", null, null);
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(doc));
        }

        @Test
        public void projectionProgressOnAnUnsequencedRowIsMalformed() {
            Map<String, Object> doc = sequencedDoc("PROJECTING", liveClaim(null), null);
            doc.put("state", "UNSEQUENCED");
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(doc),
                    "claims exist only past the sequencer's finalize");
        }

        @Test
        public void fractionalNumbersAreRefusedEverywhere() {
            Map<String, Object> claim = liveClaim(null);
            claim.put("claimedAtMs", 10.5d);
            Map<String, Object> doc = sequencedDoc("PROJECTING", claim, null);
            assertThrows(IllegalArgumentException.class,
                    () -> CouchLineageJournalRowV2.fromRaw(doc));
        }
    }

    // ================================================================ mutation round-trips

    @Nested
    class MutationRoundTrips {

        @Test
        public void aClaimMintsTheBundleAndClearsThePerAttemptMarker() {
            // A FAILED row whose failed attempt had reached VERIFYING (marker present).
            Map<String, Object> claim = new LinkedHashMap<>(liveClaim(1500L));
            claim.remove("leaseExpiresAtMs");
            claim.put("retryCount", 2L);
            Map<String, Object> doc = sequencedDoc("FAILED", claim, null);

            CouchLineageJournalRowV2.applyProjectionClaim(doc, TARGET, "tok-2", 5000L, 9000L);
            LineageTargetLifecycle lc = CouchLineageJournalRowV2.fromRaw(doc)
                    .targetLifecycles().get(TARGET);
            assertEquals(LineagePublishStatus.PROJECTING, lc.status());
            assertEquals("tok-2", lc.claimToken());
            assertEquals(5000L, lc.claimedAtMs());
            assertEquals(9000L, lc.leaseExpiresAtMs());
            assertNull(lc.verifyingSinceMs(), "the one deliberate audit-reset point (D1)");
            assertEquals(2L, lc.retryCount(), "retryCount retained across attempts");
        }

        @Test
        public void settleToFailedIncrementsOnlyWhenAsked() {
            Map<String, Object> doc = sequencedDoc("PROJECTING", liveClaim(null), null);
            CouchLineageJournalRowV2.applySettle(doc, TARGET, LineagePublishStatus.FAILED,
                    true, null);
            LineageTargetLifecycle lc = CouchLineageJournalRowV2.fromRaw(doc)
                    .targetLifecycles().get(TARGET);
            assertEquals(1L, lc.retryCount(), "observed publish failure increments");
            assertNull(lc.leaseExpiresAtMs(), "no live lease after settle");

            Map<String, Object> doc2 = sequencedDoc("VERIFYING", liveClaim(1500L), null);
            CouchLineageJournalRowV2.applySettle(doc2, TARGET, LineagePublishStatus.FAILED,
                    false, null);
            LineageTargetLifecycle lc2 = CouchLineageJournalRowV2.fromRaw(doc2)
                    .targetLifecycles().get(TARGET);
            assertEquals(0L, lc2.retryCount(), "verify max-age / reap consumes no retry");
            assertEquals(1500L, lc2.verifyingSinceMs(), "the stage marker survives");
        }

        @Test
        public void publishedKeepsTheAuditFields() {
            Map<String, Object> doc = sequencedDoc("VERIFYING", liveClaim(1500L), null);
            CouchLineageJournalRowV2.applySettle(doc, TARGET, LineagePublishStatus.PUBLISHED,
                    false, null);
            LineageTargetLifecycle lc = CouchLineageJournalRowV2.fromRaw(doc)
                    .targetLifecycles().get(TARGET);
            assertEquals(LineagePublishStatus.PUBLISHED, lc.status());
            assertEquals("tok-1", lc.claimToken(), "token kept for audit");
            assertEquals(1500L, lc.verifyingSinceMs());
            assertNull(lc.leaseExpiresAtMs());
        }

        @Test
        public void discardedFromFailedPreservesTheBundleByteForByte() {
            Map<String, Object> claim = new LinkedHashMap<>(liveClaim(1500L));
            claim.remove("leaseExpiresAtMs");
            claim.put("retryCount", 3L);
            Map<String, Object> doc = sequencedDoc("FAILED", claim, null);
            CouchLineageJournalRowV2.applySettle(doc, TARGET, LineagePublishStatus.DISCARDED,
                    false, null);
            LineageTargetLifecycle lc = CouchLineageJournalRowV2.fromRaw(doc)
                    .targetLifecycles().get(TARGET);
            assertEquals(LineagePublishStatus.DISCARDED, lc.status());
            assertEquals("tok-1", lc.claimToken());
            assertEquals(3L, lc.retryCount());
            assertEquals(1500L, lc.verifyingSinceMs(), "C3: no transition into a terminal"
                    + " state removes audit fields");
        }

        @Test
        public void terminalReasonsAreWrittenDurably() {
            Map<String, Object> doc = sequencedDoc("VERIFYING", liveClaim(1500L), null);
            CouchLineageJournalRowV2.applySettle(doc, TARGET,
                    LineagePublishStatus.UNPROJECTABLE, false,
                    new TerminalReason("VERIFY_MISMATCH", "wrong type", 2000L));
            LineageTargetLifecycle lc = CouchLineageJournalRowV2.fromRaw(doc)
                    .targetLifecycles().get(TARGET);
            assertEquals("VERIFY_MISMATCH", lc.terminalReason().reason());
            assertEquals("wrong type", lc.terminalReason().detail());
        }
    }

    // ================================================================ the fenced store

    @Nested
    class FencedStore {

        private CouchLineageJournalStore store;
        private Cloudant rawClient;

        @BeforeEach
        void setUp() throws Exception {
            store = new CouchLineageJournalStore();
            CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
            when(wrapper.getDatabaseName()).thenReturn("nemaki_lineage");
            rawClient = mock(Cloudant.class, RETURNS_DEEP_STUBS);
            when(wrapper.getClient()).thenReturn(rawClient);
            set(store, "lineageClient", wrapper);
            set(store, "dbProvisioned", new AtomicBoolean(true));
        }

        private void set(Object target, String field, Object value) throws Exception {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        }

        private void storedDocIs(Map<String, Object> doc) {
            String documentId = CouchLineageEventV2.documentId(v2Event().deliveryId());
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(doc);
            Object id = withoutMeta.remove("_id");
            Object rev = withoutMeta.remove("_rev");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId(id instanceof String i ? i : documentId);
            sdkDoc.setRev(rev instanceof String r ? r : "3-abc");
            when(rawClient.getDocument(org.mockito.ArgumentMatchers.argThat(
                    (com.ibm.cloud.cloudant.v1.model.GetDocumentOptions o) ->
                            o != null && documentId.equals(o.docId())))
                    .execute().getResult())
                    .thenReturn(sdkDoc);
        }

        /**
         * The refusal matrix: every (expected → next) pair outside the frozen fenced table is
         * a caller bug, thrown before any IO.
         */
        @Test
        public void everyPairOutsideTheFencedTableIsRefusedBeforeIO() {
            Set<List<LineagePublishStatus>> allowed = Set.of(
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.FAILED),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.FAILED),
                    List.of(LineagePublishStatus.VERIFYING,
                            LineagePublishStatus.UNPROJECTABLE),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.REJECTED));
            for (LineagePublishStatus expected : LineagePublishStatus.values()) {
                for (LineagePublishStatus next : LineagePublishStatus.values()) {
                    if (allowed.contains(List.of(expected, next))) {
                        continue;
                    }
                    assertThrows(IllegalArgumentException.class, () ->
                            store.transitionV2("rec", TARGET, expected, next, "tok", null),
                            expected + "->" + next);
                }
            }
        }

        @Test
        public void everyPairOutsideTheUnclaimedTableIsRefusedBeforeIO() {
            Set<List<LineagePublishStatus>> allowed = Set.of(
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.WAITING_FOR_CATALOG),
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.PENDING),
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.UNRESOLVED),
                    List.of(LineagePublishStatus.PENDING, LineagePublishStatus.DISCARDED),
                    List.of(LineagePublishStatus.FAILED, LineagePublishStatus.DISCARDED));
            for (LineagePublishStatus expected : LineagePublishStatus.values()) {
                for (LineagePublishStatus next : LineagePublishStatus.values()) {
                    if (allowed.contains(List.of(expected, next))) {
                        continue;
                    }
                    assertThrows(IllegalArgumentException.class, () ->
                            store.transitionV2Unclaimed("rec", TARGET, expected, next, null),
                            expected + "->" + next);
                }
            }
        }

        @Test
        public void reasonsAreRequiredExactlyWhereTheTableSaysSo() {
            assertThrows(IllegalArgumentException.class, () -> store.transitionV2("rec",
                    TARGET, LineagePublishStatus.VERIFYING,
                    LineagePublishStatus.UNPROJECTABLE, "tok", null));
            assertThrows(IllegalArgumentException.class, () -> store.transitionV2("rec",
                    TARGET, LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED,
                    "tok", new TerminalReason("R", "", 1L)));
            assertThrows(IllegalArgumentException.class, () -> store.transitionV2Unclaimed(
                    "rec", TARGET, LineagePublishStatus.WAITING_FOR_CATALOG,
                    LineagePublishStatus.UNRESOLVED, null));
        }

        @Test
        public void aClaimOnASequencedPendingRowMintsTokenAndLease() {
            storedDocIs(sequencedDoc("PENDING", null, null));
            LineageV2TransitionStore.V2ClaimGrant grant =
                    store.claimForProjection(v2Event().deliveryId(), TARGET,
                            Duration.ofSeconds(120));
            assertNotNull(grant);
            assertNotNull(grant.claimToken());
        }

        @Test
        public void anUnsequencedRowIsNeverClaimable() {
            Map<String, Object> doc = sequencedDoc("PENDING", null, null);
            doc.put("state", "UNSEQUENCED");
            doc.remove("sequencerGeneration");
            doc.remove("sequencerLeaseToken");
            doc.put("sequenceNumber", 0L);
            storedDocIs(doc);
            assertNull(store.claimForProjection(v2Event().deliveryId(), TARGET,
                    Duration.ofSeconds(120)));
        }

        @Test
        public void aLiveClaimIsNotReclaimable() {
            storedDocIs(sequencedDoc("PROJECTING", liveClaim(null), null));
            assertNull(store.claimForProjection(v2Event().deliveryId(), TARGET,
                    Duration.ofSeconds(120)));
        }

        @Test
        public void aTokenMismatchLosesTheFence() {
            storedDocIs(sequencedDoc("PROJECTING", liveClaim(null), null));
            assertFalse(store.transitionV2(v2Event().deliveryId(), TARGET,
                    LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING,
                    "rotated-token", null));
        }

        @Test
        public void anExpiredClaimNeverRenewsItself() {
            Map<String, Object> claim = liveClaim(null);
            claim.put("leaseExpiresAtMs", 1L);
            storedDocIs(sequencedDoc("PROJECTING", claim, null));
            assertFalse(store.renewClaim(v2Event().deliveryId(), TARGET, "tok-1",
                    Duration.ofSeconds(120)),
                    "an expired claim goes through the reaper, never resurrects");
        }

        /** F4: every claimant write fails after expiry — the reaper owns expired claims. */
        @Test
        public void anExpiredClaimantCannotSettleEvenWithTheRightToken() {
            Map<String, Object> claim = liveClaim(null);
            claim.put("leaseExpiresAtMs", 1L);
            storedDocIs(sequencedDoc("PROJECTING", claim, null));
            assertFalse(store.transitionV2(v2Event().deliveryId(), TARGET,
                    LineagePublishStatus.PROJECTING, LineagePublishStatus.FAILED,
                    "tok-1", null),
                    "an expired claimant racing the reaper must lose, not settle");
        }

        /** F5: appendV2 refuses initial statuses this slice cannot write consistently. */
        @Test
        public void appendV2RefusesNonPendingInitialStatuses() {
            LineageEventV2 pending = v2Event();
            LineageEventV2 published = new LineageEventV2(pending.schemaVersion(),
                    pending.idempotencyKeyVersion(), pending.eventId(), pending.processKey(),
                    pending.delivery(), pending.deliveryId(), pending.repositoryId(),
                    pending.processType(), pending.operationId(), pending.occurredAt(),
                    pending.inputs(), pending.outputs(), pending.chunkIndex(),
                    pending.chunkCount(), pending.sequenceNumber(), pending.correlationId(),
                    pending.spoolRecordId(), pending.legacyEventKey(),
                    Map.of(TARGET, LineagePublishStatus.PUBLISHED),
                    pending.creationPayloadDigest());
            assertThrows(IllegalArgumentException.class, () -> store.appendV2(published),
                    "creation-time classifications land with their producers and reason"
                            + " shapes — a bare non-PENDING status would be a row every read"
                            + " refuses");
        }

        @Test
        public void aMalformedRowThrowsRatherThanBecomingAValue() {
            Map<String, Object> doc = sequencedDoc("VERIFYING", liveClaim(null), null);
            storedDocIs(doc);
            assertThrows(LineageSequencingStore.SequencingStorageException.class, () ->
                    store.claimForProjection(v2Event().deliveryId(), TARGET,
                            Duration.ofSeconds(120)));
        }
    }
}
