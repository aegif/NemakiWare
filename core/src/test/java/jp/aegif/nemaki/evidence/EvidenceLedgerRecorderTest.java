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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A capture that is not chained says so; a capture that fails to chain is still captured.
 *
 * <h2>The two rules being held apart</h2>
 *
 * <p>This runs after the capture row is already durable, so a failure here must NOT undo the
 * ingest — losing the capture because a second record could not be written would destroy the
 * thing the record was about. And it must not be silent either: a chain with a hole nobody
 * mentioned is worse than no chain, because the chain is believed.
 *
 * <p>Those two pull in opposite directions, which is why the outcome is a value with BOTH a flag
 * and a warning rather than a boolean.
 */
class EvidenceLedgerRecorderTest {

    private static final String REPO = "bedroom";

    private static CaptureIntent intent(String intentId, String sourceObjectId) {
        return new CaptureIntent("lineage_capture:" + intentId, intentId, 1750000000000L, REPO,
                "slack", "Slack", "message", sourceObjectId, "req-1", "chatImport", "admin",
                null);
    }

    private static Map<String, Object> evidenceWithHash(String hash) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(CaptureIntent.APPLIED_HASH_FIELDS.get(0), hash);
        return evidence;
    }

    private static EvidenceLedgerRecorder recorderOver(EvidenceLedgerService service) {
        EvidenceLedgerRecorder recorder = new EvidenceLedgerRecorder();
        recorder.setLedgerService(service);
        return recorder;
    }

    @Test
    @DisplayName("a completed capture is appended to the chain")
    void aCaptureIsChained() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.APPENDED, 1, "hash", null));

        CaptureIntent intent = intent("i-1", "src-1");
        Map<String, Object> evidence = evidenceWithHash("deadbeef");

        EvidenceLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordCaptureCompleted(REPO, intent, evidence, "2026-08-25T00:00:00Z");

        assertTrue(recorded.inChain(), "the capture was not added to the chain");
        assertNull(recorded.warning(), "a successful append produced a warning: "
                + recorded.warning());

        // WHAT was appended, not merely that something was. Stubbing with anyString() and
        // never capturing let `digest = ""` pass every test in this class — the chain would
        // have committed to nothing about the capture. Reviewers named that exact edit.
        org.mockito.ArgumentCaptor<String> subjectId =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> payloadDigest =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<EvidenceLedgerEntry.SubjectKind> kind =
                org.mockito.ArgumentCaptor.forClass(EvidenceLedgerEntry.SubjectKind.class);
        org.mockito.Mockito.verify(service).append(org.mockito.ArgumentMatchers.eq(REPO),
                kind.capture(), subjectId.capture(), payloadDigest.capture(),
                org.mockito.ArgumentMatchers.eq("2026-08-25T00:00:00Z"));

        assertEquals(EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED, kind.getValue());
        assertEquals(intent.intentId(), subjectId.getValue(),
                "the entry does not name the capture it is about, so a verifier holding the "
                        + "capture row cannot find its chain entry");
        assertEquals(EvidenceLedgerRecorder.captureDigest(REPO, intent, evidence),
                payloadDigest.getValue(),
                "the chain committed to something other than this capture's digest");
        assertFalse(payloadDigest.getValue().isBlank(),
                "the chain committed to an empty digest, which is to say to nothing");
    }

    @Test
    @DisplayName("an append that did not land is reported, and the capture still stands")
    void aRefusedAppendIsReportedNotHidden() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.UNAVAILABLE, -1, null,
                        "the evidence ledger is not available"));

        EvidenceLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordCaptureCompleted(REPO, intent("i-2", "src-2"),
                        evidenceWithHash("deadbeef"), "2026-08-25T00:00:00Z");

        assertFalse(recorded.inChain());
        assertNotNull(recorded.warning(),
                "the chain is missing this entry and nobody was told; a hole nobody mentions "
                        + "is worse than no chain, because the chain is believed");
        assertTrue(recorded.warning().contains("will not be back-filled"),
                "the warning does not say the gap is permanent: " + recorded.warning());
    }

    @Test
    @DisplayName("a ledger that throws does not take the capture down with it")
    void aThrowingLedgerDoesNotFailTheCapture() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));

        // Not assertThrows: the capture row is ALREADY durable at this point. Propagating would
        // fail an ingest whose evidence was successfully written.
        EvidenceLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordCaptureCompleted(REPO, intent("i-3", "src-3"),
                        evidenceWithHash("deadbeef"), "2026-08-25T00:00:00Z");

        assertFalse(recorded.inChain());
        assertTrue(recorded.warning().contains("couchdb is down"),
                "the cause was swallowed: " + recorded.warning());
    }

    @Test
    @DisplayName("a digest that cannot be computed does not fail the capture either")
    void aFailingDigestDoesNotFailTheCapture() {
        // Same rule as the throwing ledger, one step earlier: the digest reads a caller-supplied
        // map. Computing it outside the guard would let a bad map fail an ingest whose capture
        // row is already durable.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        Map<String, Object> hostile = new LinkedHashMap<>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("this evidence map cannot be read");
            }
        };

        EvidenceLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordCaptureCompleted(REPO, intent("i-9", "src-9"), hostile,
                        "2026-08-25T00:00:00Z");

        assertFalse(recorded.inChain());
        assertTrue(recorded.warning().contains("cannot be read"),
                "the cause was swallowed: " + recorded.warning());
    }

    @Test
    @DisplayName("no ledger wired is not an error, and not a warning either")
    void anUnwiredLedgerIsSilent() {
        // A deployment that has not enabled the ledger is not in an error state. A warning per
        // ingest would train an operator to ignore the log, which is how a real warning gets
        // missed later.
        EvidenceLedgerRecorder.Recorded recorded = recorderOver(null)
                .recordCaptureCompleted(REPO, intent("i-4", "src-4"), Map.of(),
                        "2026-08-25T00:00:00Z");

        assertFalse(recorded.inChain());
        assertNull(recorded.warning(),
                "an unconfigured ledger produced a per-ingest warning: " + recorded.warning());
    }

    // ---- the digest ----

    @Test
    @DisplayName("the digest changes when the capture it identifies changes")
    void theDigestIdentifiesTheCapture() {
        Map<String, Object> evidence = evidenceWithHash("deadbeef");
        String base = EvidenceLedgerRecorder.captureDigest(REPO, intent("i-5", "src-5"), evidence);

        // Each of these is part of "which capture was this, and what did it establish".
        assertNotEquals(base,
                EvidenceLedgerRecorder.captureDigest("canopy", intent("i-5", "src-5"), evidence),
                "the repository does not affect the digest, so two repositories' captures of "
                        + "the same source collide");
        assertNotEquals(base,
                EvidenceLedgerRecorder.captureDigest(REPO, intent("i-6", "src-5"), evidence),
                "the intent id does not affect the digest");
        assertNotEquals(base,
                EvidenceLedgerRecorder.captureDigest(REPO, intent("i-5", "src-6"), evidence),
                "the source object does not affect the digest");
        assertNotEquals(base,
                EvidenceLedgerRecorder.captureDigest(REPO, intent("i-5", "src-5"),
                        evidenceWithHash("cafebabe")),
                "the applied metadata hash does not affect the digest, so the chain commits to "
                        + "nothing about WHAT was recorded");
    }

    @Test
    @DisplayName("the same capture digests the same — it is recomputable from the row")
    void theDigestIsReproducible() {
        // The point of the digest: a verifier holding the stored capture row can recompute it
        // and compare it with what the chain committed to. A digest that varied per call would
        // make the chain uncheckable.
        assertEquals(
                EvidenceLedgerRecorder.captureDigest(REPO, intent("i-7", "src-7"),
                        evidenceWithHash("deadbeef")),
                EvidenceLedgerRecorder.captureDigest(REPO, intent("i-7", "src-7"),
                        evidenceWithHash("deadbeef")));
    }

    @Test
    @DisplayName("the digest is domain-separated from every other digest in the product")
    void theDigestIsDomainSeparated() {
        // Without the domain, a digest computed over the same fields elsewhere would collide
        // with this one and a value could be carried between contexts.
        // Comparing the two constants only checks that two literals differ, and the domain
        // could be dropped from the hash entirely without either changing. Compare the VALUES
        // the two domains produce from the same inputs.
        CaptureIntent intent = intent("i-8", "src-8");
        String withOurDomain = EvidenceLedgerRecorder.captureDigest(REPO, intent, Map.of());
        String withAnother = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                EvidenceLedgerEntry.HASH_DOMAIN, REPO, intent.intentId(), intent.connectorId(),
                intent.sourceObjectId(), null, null, null);

        assertNotEquals(withAnother, withOurDomain,
                "the capture digest is not domain-separated: the same fields hashed for "
                        + "another purpose produce the same value, so a digest can be carried "
                        + "between contexts");
    }
}
