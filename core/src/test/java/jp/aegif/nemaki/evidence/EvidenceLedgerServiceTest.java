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

import jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind;
import jp.aegif.nemaki.evidence.EvidenceLedgerService.AppendOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appending, sealing and proving — the service around the chain (P1-3).
 */
class EvidenceLedgerServiceTest {

    private static final String DOMAIN = "bedroom";

    /** An in-memory ledger with the create-only semantics the real store promises. */
    private static final class FakeStore implements EvidenceLedgerStore {
        final List<EvidenceLedgerEntry> entries = new ArrayList<>();
        final List<EvidenceCheckpoint> checkpoints = new ArrayList<>();
        boolean active = true;
        /** Set to make the FIRST append at a position lose, modelling a concurrent writer. */
        int refuseFirstAppends;

        @Override public boolean append(EvidenceLedgerEntry entry) {
            if (refuseFirstAppends > 0) {
                refuseFirstAppends--;
                // The other writer took it. Model that by actually putting something there,
                // so the retry sees a moved tail rather than the same one.
                entries.add(EvidenceLedgerEntry.of(entry.domain(), entry.sequence(),
                        SubjectKind.LINEAGE_EVENT, "other-writer", "mh1:other",
                        entry.occurredAt(), entry.prevEntryHash()));
                return false;
            }
            for (EvidenceLedgerEntry existing : entries) {
                if (existing.sequence() == entry.sequence()) {
                    return false;
                }
            }
            entries.add(entry);
            return true;
        }

        @Override public long highestSequence(String domain) {
            return entries.stream().filter(e -> e.domain().equals(domain))
                    .mapToLong(EvidenceLedgerEntry::sequence).max().orElse(-1L);
        }

        /** When set, range() drops rows from the end — a view still building, or a limit hit. */
        int dropFromEnd;

        /** When set, range() answers null — what a store that could not read looks like. */
        boolean rangeAnswersNull;

        @Override public List<EvidenceLedgerEntry> range(String domain, long from, long to,
                int limit) {
            if (rangeAnswersNull) {
                return null;
            }
            List<EvidenceLedgerEntry> rows = entries.stream()
                    .filter(e -> e.domain().equals(domain))
                    .filter(e -> e.sequence() >= from && e.sequence() <= to)
                    .sorted(java.util.Comparator.comparingLong(EvidenceLedgerEntry::sequence))
                    .limit(limit).toList();
            // Applied AFTER the ordinary result, so the switch changes nothing when it is off.
            if (dropFromEnd < 0 && !rows.isEmpty()) {
                // Negative means the opposite: hand back one row twice, which is what a fork
                // looks like from here.
                List<EvidenceLedgerEntry> forked = new java.util.ArrayList<>(rows);
                forked.add(rows.get(rows.size() - 1));
                return forked;
            }
            return dropFromEnd <= 0 ? rows
                    : rows.subList(0, Math.max(0, rows.size() - dropFromEnd));
        }

        @Override public boolean appendCheckpoint(EvidenceCheckpoint checkpoint) {
            for (EvidenceCheckpoint existing : checkpoints) {
                if (existing.toSequence() == checkpoint.toSequence()) {
                    return false;
                }
            }
            checkpoints.add(checkpoint);
            return true;
        }

        @Override public EvidenceCheckpoint latestCheckpoint(String domain) {
            return checkpoints.stream().filter(c -> c.domain().equals(domain))
                    .max(java.util.Comparator.comparingLong(EvidenceCheckpoint::toSequence))
                    .orElse(null);
        }

        @Override public EvidenceCheckpoint checkpointEndingBefore(String domain, long from) {
            return checkpoints.stream().filter(c -> c.domain().equals(domain))
                    .filter(c -> c.toSequence() < from)
                    .max(java.util.Comparator.comparingLong(EvidenceCheckpoint::toSequence))
                    .orElse(null);
        }

        @Override public boolean isActive() {
            return active;
        }
    }

    private static EvidenceLedgerService serviceOver(FakeStore store) {
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        return service;
    }

    private static void appendSome(EvidenceLedgerService service, int n) {
        for (int i = 1; i <= n; i++) {
            service.append(DOMAIN, SubjectKind.CAPTURE_COMPLETED, "intent-" + i, "mh1:" + i,
                    "2026-08-24T00:00:0" + (i % 10) + "Z");
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a short read is not sealed as the full range")
    void aShortReadIsNotSealed() {
        // The verifier only checks relationships WITHIN the list it is handed. A view still
        // building returns fewer rows, and without an endpoint/count check a Merkle root over
        // 0..3 could be sealed as a checkpoint claiming 0..9. Everything downstream then reads
        // as covered when it is not.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 10);
        store.dropFromEnd = 6;

        Map<String, Object> body = service.closeCheckpoint(DOMAIN, "2026-08-24T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertEquals("error", body.get("status"),
                "a short read was sealed as the full range: " + body);
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(body.get("message")).contains("rows for"),
                "the failure does not say the span was short: " + body.get("message"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a null span is refused, not a crash")
    void aNullSpanIsRefusedNotACrash() {
        // coverageProblem was written to handle a null span, and the line that REPORTED the
        // refusal then dereferenced it — so the defence fell over exactly when it was used.
        // The caller turned that into HTTP 500 "could not be closed: null".
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 3);
        store.rangeAnswersNull = true;

        Map<String, Object> body = service.closeCheckpoint(DOMAIN, "2026-08-25T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertEquals("error", body.get("status"), body + "");
        org.junit.jupiter.api.Assertions.assertNull(body.get("rowsRead"),
                "a null span reported a row count");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("more rows than sequences is called a fork, not a short read")
    void aForkIsNotDescribedAsAShortRead() {
        // Running the coverage check before the verifier means the verifier's FORK finding —
        // which names both competing hashes — never reaches the operator. The least this can
        // do is not describe the opposite of what happened.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 3);
        store.dropFromEnd = -1;

        Map<String, Object> body = service.closeCheckpoint(DOMAIN, "2026-08-25T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertEquals("error", body.get("status"), body + "");
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(body.get("message")).contains("fork"),
                "more rows than sequences was reported as a short read: " + body.get("message"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a complete read IS sealed — the control")
    void aCompleteReadIsSealed() {
        // Without this, refusing every span would pass the test above and no checkpoint could
        // ever be closed.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 10);

        Map<String, Object> body = service.closeCheckpoint(DOMAIN, "2026-08-24T00:00:00Z");

        org.junit.jupiter.api.Assertions.assertEquals("success", body.get("status"), body + "");
    }

    @Test
    @DisplayName("appends chain to each other and the walk stays intact")
    void appendsChain() {
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);

        appendSome(service, 4);

        assertEquals(4, store.entries.size());
        assertTrue(EvidenceChainVerifier.verify(store.range(DOMAIN, 0, 100, 100)).intact());
    }

    @Test
    @DisplayName("an unavailable ledger says so — it does not report success")
    void anUnavailableLedgerSaysSo() {
        // "We could not record" is not "nothing to record". A caller that cannot tell them
        // apart is the DAO's null-returning update all over again (roadmap §2-1).
        FakeStore store = new FakeStore();
        store.active = false;

        EvidenceLedgerService.AppendResult result = serviceOver(store).append(DOMAIN,
                SubjectKind.CAPTURE_COMPLETED, "intent-1", "mh1:1", "2026-08-24T00:00:00Z");

        assertEquals(AppendOutcome.UNAVAILABLE, result.outcome());
        assertFalse(result.recorded());
    }

    @Test
    @DisplayName("a lost race retries past the winner instead of losing the entry")
    void aLostRaceRetries() {
        // Two writers appending at once must produce two entries in some order — not one
        // entry and one silent loss.
        FakeStore store = new FakeStore();
        store.refuseFirstAppends = 1;
        EvidenceLedgerService service = serviceOver(store);

        EvidenceLedgerService.AppendResult result = service.append(DOMAIN,
                SubjectKind.CAPTURE_COMPLETED, "mine", "mh1:mine", "2026-08-24T00:00:00Z");

        assertTrue(result.recorded(), result.reason());
        assertEquals(2, store.entries.size(), "the other writer's entry AND mine must be there");
        assertTrue(EvidenceChainVerifier.verify(store.range(DOMAIN, 0, 100, 100)).intact(),
                "the retry did not link to the NEW tail, so the chain is broken");
    }

    @Test
    @DisplayName("appending onto a FORKED tail is refused, not resolved silently")
    void appendingOntoAForkIsRefused() {
        // Linking to either arm would pick a winner, and the ledger's own answer would then
        // depend on which row was read first.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 2);
        EvidenceLedgerEntry tail = store.entries.get(1);
        store.entries.add(EvidenceLedgerEntry.of(DOMAIN, tail.sequence(),
                SubjectKind.LINEAGE_EVENT, "other-arm", "mh1:other", tail.occurredAt(),
                tail.prevEntryHash()));

        EvidenceLedgerService.AppendResult result = service.append(DOMAIN,
                SubjectKind.CAPTURE_COMPLETED, "intent-3", "mh1:3", "2026-08-24T00:00:03Z");

        assertEquals(AppendOutcome.REFUSED, result.outcome());
        assertTrue(result.reason().contains("fork"), result.reason());
    }

    @Test
    @DisplayName("a checkpoint over an empty span is refused")
    void anEmptySpanIsNotSealed() {
        // An anchor would then attest to a value that commits to no entries — worse than no
        // anchor, because it looks like coverage.
        FakeStore store = new FakeStore();

        assertEquals("noop", serviceOver(store)
                .closeCheckpoint(DOMAIN, "2026-08-24T00:00:00Z").get("status"));
    }

    @Test
    @DisplayName("a span that does not verify is NOT sealed")
    void abrokenSpanIsNotSealed() {
        // Sealing it would commit to a chain already known to be broken, and an anchor would
        // then make that permanent.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 3);
        EvidenceLedgerEntry victim = store.entries.get(1);
        store.entries.set(1, new EvidenceLedgerEntry(victim.domain(), victim.sequence(),
                victim.subjectKind(), victim.subjectId(), "mh1:FORGED", victim.occurredAt(),
                victim.prevEntryHash(), victim.entryHash()));

        Map<String, Object> result = service.closeCheckpoint(DOMAIN, "2026-08-24T01:00:00Z");

        assertEquals("error", result.get("status"));
        assertTrue(store.checkpoints.isEmpty(), "a broken span was sealed anyway");
    }

    @Test
    @DisplayName("an inclusion proof verifies against the checkpoint that covers it")
    void inclusionProofVerifies() {
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 5);
        service.closeCheckpoint(DOMAIN, "2026-08-24T01:00:00Z");

        Map<String, Object> proof = service.inclusionProof(DOMAIN, 3);

        assertEquals("success", proof.get("status"), String.valueOf(proof.get("message")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> path = (List<Map<String, Object>>) proof.get("auditPath");
        List<MerkleTree.ProofStep> steps = path.stream()
                .map(m -> new MerkleTree.ProofStep((String) m.get("siblingHash"),
                        Boolean.TRUE.equals(m.get("siblingIsLeft"))))
                .toList();
        assertTrue(MerkleTree.verify((String) proof.get("leafHash"), steps,
                        (String) proof.get("merkleRoot")),
                "the proof the service handed out does not verify against the root it names");
    }

    @Test
    @DisplayName("an entry with no checkpoint yet has NO proof — and says why")
    void unsealedEntriesHaveNoProof() {
        // Answering with a proof against the ledger's own current state would prove only that
        // the ledger agrees with itself. The honest answer is that nothing has committed to
        // this entry yet.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 3);

        Map<String, Object> proof = service.inclusionProof(DOMAIN, 2);

        assertEquals("unavailable", proof.get("status"));
        assertTrue(String.valueOf(proof.get("message")).contains("no checkpoint"),
                String.valueOf(proof.get("message")));
    }

    @Test
    @DisplayName("a proof for an OLD entry walks back to its own checkpoint")
    void anOldEntryFindsItsCheckpoint() {
        // Checkpoints are contiguous, so the covering one for an old entry is behind the
        // latest. Answering against the latest would name a root that does not contain the
        // leaf, and the proof would not verify.
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 3);
        service.closeCheckpoint(DOMAIN, "2026-08-24T01:00:00Z");
        appendSome(service, 3);
        service.closeCheckpoint(DOMAIN, "2026-08-24T02:00:00Z");

        Map<String, Object> proof = service.inclusionProof(DOMAIN, 2);

        assertEquals("success", proof.get("status"), String.valueOf(proof.get("message")));
        assertEquals(store.checkpoints.get(0).checkpointHash(), proof.get("checkpointHash"),
                "the proof named the LATEST checkpoint rather than the one covering the entry");
    }

    @Test
    @DisplayName("every proof carries what it does not establish")
    void proofsCarryTheirLimits() {
        FakeStore store = new FakeStore();
        EvidenceLedgerService service = serviceOver(store);
        appendSome(service, 2);
        service.closeCheckpoint(DOMAIN, "2026-08-24T01:00:00Z");

        String limits = String.valueOf(service.inclusionProof(DOMAIN, 1).get("limits"));

        assertTrue(limits.contains("outside this database"), limits);
    }
}
