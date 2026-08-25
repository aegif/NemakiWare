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

        EvidenceLedgerRecorder.Recorded recorded = recorderOver(service)
                .recordCaptureCompleted(REPO, intent("i-1", "src-1"),
                        evidenceWithHash("deadbeef"), "2026-08-25T00:00:00Z");

        assertTrue(recorded.inChain(), "the capture was not added to the chain");
        assertNull(recorded.warning(), "a successful append produced a warning: "
                + recorded.warning());
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
        assertTrue(EvidenceLedgerRecorder.CAPTURE_DIGEST_DOMAIN.startsWith("LEDGER_"),
                EvidenceLedgerRecorder.CAPTURE_DIGEST_DOMAIN);
        assertNotEquals(EvidenceLedgerRecorder.CAPTURE_DIGEST_DOMAIN,
                EvidenceLedgerEntry.HASH_DOMAIN,
                "the capture digest shares a domain with the ledger entry hash");
    }
}
