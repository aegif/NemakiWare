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
package jp.aegif.nemaki.evidence.anchor;

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A pending commitment survives a restart, and reload cannot strengthen it (P2-0).
 *
 * <h2>Why persistence is the load-bearing part of rung 2</h2>
 *
 * <p>OpenTimestamps hands back a PENDING proof and settles hours later. {@code upgrade()} needs
 * the pending proof BYTES to ask the calendar again. Held only in memory, every pending
 * commitment dies at the next restart: the calendar still has it, a block still confirmed it,
 * and this deployment can no longer produce the proof. Rung 2 is then decorative — it looks
 * configured and yields nothing — which is the quietest way an anchoring story fails.
 */
class AnchorReceiptPersistenceTest {

    private static final String DOMAIN = "bedroom";
    private static final String ROOT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** An in-memory stand-in with the same contract, so the service can be exercised whole. */
    private static final class MemoryStore implements AnchorReceiptStore {
        private final Map<String, PendingReceipt> rows = new LinkedHashMap<>();

        @Override
        public SaveOutcome save(String domain, long toSequence, AnchorReceipt receipt) {
            String key = domain + ":" + toSequence + ":" + receipt.kind();
            // The same monotonicity rule the real store enforces inside its CAS. A stand-in
            // that stored unconditionally would let the service's tests pass against a store
            // that does not keep the rule.
            PendingReceipt held = rows.get(key);
            if (receipt.status() != AnchorStatus.CONFIRMED && held != null
                    && held.receipt().status() == AnchorStatus.CONFIRMED) {
                return SaveOutcome.KEPT_STRONGER;
            }
            // Round-trip through the codec, because the point of storing is to get it back and
            // a stand-in that hands back the same object would test nothing about that.
            AnchorReceipt reloaded =
                    AnchorReceiptCodec.fromDocument(AnchorReceiptCodec.toDocument(receipt));
            rows.put(key, new PendingReceipt(domain, toSequence, reloaded));
            return SaveOutcome.STORED;
        }

        @Override
        public List<AnchorReceipt> forCheckpoint(String domain, long toSequence) {
            List<AnchorReceipt> out = new ArrayList<>();
            for (PendingReceipt row : rows.values()) {
                if (row.domain().equals(domain) && row.toSequence() == toSequence) {
                    out.add(row.receipt());
                }
            }
            return out;
        }

        @Override
        public List<PendingReceipt> pending(String domain, int limit) {
            List<PendingReceipt> out = new ArrayList<>();
            for (PendingReceipt row : rows.values()) {
                if (row.domain().equals(domain)
                        && row.receipt().status() == AnchorStatus.PENDING) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<PendingReceipt> confirmed(String domain, int limit) {
            List<PendingReceipt> out = new ArrayList<>();
            for (PendingReceipt row : rows.values()) {
                if (row.domain().equals(domain)
                        && row.receipt().status() == AnchorStatus.CONFIRMED) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    private static EvidenceLedgerStore storeAt(long highest) {
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.highestSequence(anyString())).thenReturn(highest);
        return store;
    }

    /** A rung that stamps PENDING, then confirms once the test says the block landed. */
    private static final class SettlingRung implements AnchorTarget {
        private boolean settled;

        @Override
        public AnchorKind kind() {
            return AnchorKind.OPENTIMESTAMPS;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public AnchorReceipt anchor(String hexDigest) {
            return AnchorReceipt.pending(kind(), hexDigest, Instant.parse("2026-08-24T00:00:00Z"),
                    new byte[] { 9, 9, 9 },
                    AnchorReceiptCodec.sha256Hex(new byte[] { 9, 9, 9 }),
                    Map.of("calendar", "alice"));
        }

        @Override
        public AnchorReceipt upgrade(AnchorReceipt pending) {
            if (!settled) {
                return pending;
            }
            // The real target needs these bytes; a store that lost them makes this impossible.
            assertArrayEquals(new byte[] { 9, 9, 9 }, pending.proof(),
                    "the pending proof bytes did not survive storage, so upgrade() has nothing "
                            + "to send back to the calendar");
            return jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts.confirmed(kind(),
                    pending.anchoredDigest(), Instant.parse("2026-08-24T06:00:00Z"),
                    new byte[] { 9, 9, 9, 7 }, "settledproof", Map.of("block", "912345"));
        }
    }

    @Test
    @DisplayName("a pending commitment is written down, with its proof bytes")
    void aPendingCommitmentIsStored() {
        MemoryStore receipts = new MemoryStore();
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(new SettlingRung()));

        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));

        List<AnchorReceipt> stored = receipts.forCheckpoint(DOMAIN, 5);
        assertEquals(1, stored.size(), "the pending receipt was not stored at all");
        assertEquals(AnchorStatus.PENDING, stored.get(0).status());
        assertArrayEquals(new byte[] { 9, 9, 9 }, stored.get(0).proof(),
                "the proof bytes were dropped; without them the commitment can never be "
                        + "upgraded and the anchor is lost while the calendar still holds it");
    }

    @Test
    @DisplayName("upgradePending settles a stored commitment and writes the result back")
    void upgradeSettlesAndPersists() {
        MemoryStore receipts = new MemoryStore();
        SettlingRung rung = new SettlingRung();
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(rung));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));

        // Before the block lands: nothing changes, and that is the ordinary answer.
        assertEquals(List.of(), service.upgradePending(DOMAIN, 10),
                "an unsettled commitment was reported as upgraded");
        assertEquals(AnchorStatus.PENDING, receipts.forCheckpoint(DOMAIN, 5).get(0).status());

        rung.settled = true;
        List<AnchorReceipt> upgraded = service.upgradePending(DOMAIN, 10);

        assertEquals(1, upgraded.size(), "the settled commitment was not upgraded");
        assertEquals(AnchorStatus.CONFIRMED, receipts.forCheckpoint(DOMAIN, 5).get(0).status(),
                "the upgrade was not written back, so the next restart loses it again");
        assertEquals(List.of(), service.upgradePending(DOMAIN, 10),
                "an already-confirmed receipt was offered for upgrade again");
    }

    @Test
    @DisplayName("a receipt whose rung is no longer configured is left pending, not failed")
    void anOrphanedPendingReceiptIsLeftAlone() {
        MemoryStore receipts = new MemoryStore();
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(new SettlingRung()));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));

        // The operator turns rung 2 off. The calendar still holds the commitment.
        service.setTargets(List.of());
        assertEquals(List.of(), service.upgradePending(DOMAIN, 10));

        assertEquals(AnchorStatus.PENDING, receipts.forCheckpoint(DOMAIN, 5).get(0).status(),
                "an orphaned commitment was marked something other than pending; failing it "
                        + "asserts something about an anchor nobody checked, and deleting it "
                        + "loses a proof the calendar still holds");
    }

    @Test
    @DisplayName("an upgrade that comes back as a DIFFERENT rung is ignored")
    void anUpgradeMustBeOfTheSameRung() {
        MemoryStore receipts = new MemoryStore();
        AnchorTarget confused = new AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return AnchorKind.OPENTIMESTAMPS;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AnchorReceipt anchor(String hexDigest) {
                return AnchorReceipt.pending(kind(), hexDigest, Instant.now(),
                        new byte[] { 1 }, "p", Map.of());
            }

            @Override
            public AnchorReceipt upgrade(AnchorReceipt pending) {
                return jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts.confirmed(
                        AnchorKind.RFC3161_TSA, pending.anchoredDigest(), Instant.now(),
                        new byte[] { 2 }, "q", Map.of());
            }
        };
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(confused));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));

        assertEquals(List.of(), service.upgradePending(DOMAIN, 10));

        // Saving it would write a row under the OTHER rung's key and leave this one pending for
        // ever: the commitment reads as unsettled while a settled proof sits one row away under
        // a name nothing looks for.
        List<AnchorReceipt> stored = receipts.forCheckpoint(DOMAIN, 5);
        assertEquals(1, stored.size(), "a second row was written under the wrong rung");
        assertEquals(AnchorKind.OPENTIMESTAMPS, stored.get(0).kind());
        assertEquals(AnchorStatus.PENDING, stored.get(0).status());
    }

    // ---- an anchor may be added to, never quietly taken away ----

    @Test
    @DisplayName("a FAILED attempt does not erase a CONFIRMED receipt")
    void aFailureDoesNotEraseAProof() {
        MemoryStore receipts = new MemoryStore();
        receipts.save(DOMAIN, 5, jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts.confirmed(
                AnchorKind.RFC3161_TSA, ROOT, Instant.parse("2026-08-24T00:00:00Z"),
                new byte[] { 1, 2, 3 }, "tokendigest", Map.of("genTime", "2026-08-24T00:00:00Z")));

        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(failingRung(AnchorKind.RFC3161_TSA)));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T01:00:00Z"));

        // One briefly unreachable TSA would otherwise destroy the token already obtained.
        List<AnchorReceipt> stored = receipts.forCheckpoint(DOMAIN, 5);
        assertEquals(1, stored.size());
        assertEquals(AnchorStatus.CONFIRMED, stored.get(0).status(),
                "a transient failure overwrote a confirmed RFC 3161 token; the proof is gone "
                        + "and nothing says it ever existed");
        assertArrayEquals(new byte[] { 1, 2, 3 }, stored.get(0).proof());
    }

    @Test
    @DisplayName("re-anchoring does not turn a settled .ots back into a pending commitment")
    void reAnchoringDoesNotUnsettleACommitment() {
        MemoryStore receipts = new MemoryStore();
        receipts.save(DOMAIN, 5, jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts.confirmed(
                AnchorKind.OPENTIMESTAMPS, ROOT, Instant.parse("2026-08-24T00:00:00Z"),
                new byte[] { 9, 9, 9, 7 }, "settledproof", Map.of("block", "912345")));

        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        // OpenTimestamps returns PENDING on EVERY stamp, so this is what one extra cron run does.
        service.setTargets(List.of(new SettlingRung()));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T02:00:00Z"));

        assertEquals(AnchorStatus.CONFIRMED, receipts.forCheckpoint(DOMAIN, 5).get(0).status(),
                "an .ots that had reached a Bitcoin block was replaced by a fresh unconfirmed "
                        + "commitment; hours of waiting are discarded by re-running a job");
    }

    @Test
    @DisplayName("a CONFIRMED receipt still replaces a PENDING one — the control")
    void confirmationStillOverwritesPending() {
        // Without this, refusing every write would pass the two tests above and upgrade()
        // could never record anything.
        MemoryStore receipts = new MemoryStore();
        SettlingRung rung = new SettlingRung();
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setReceiptStore(receipts);
        service.setTargets(List.of(rung));
        service.anchor(EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));
        rung.settled = true;

        service.upgradePending(DOMAIN, 10);

        assertEquals(AnchorStatus.CONFIRMED, receipts.forCheckpoint(DOMAIN, 5).get(0).status(),
                "the settled proof was refused, so nothing can ever be confirmed");
    }

    @Test
    @DisplayName("the limits shown come from the RECEIPT, not from its rung's usual meaning")
    void limitsFollowTheReceiptNotTheKind() {
        // A pending RFC 3161 attempt carries NOT_A_TIME_PROOF. Rendering the kind's sentence
        // put "binds a message imprint to the authority's stated time" beside an attempt that
        // establishes nothing.
        AnchorReceipt pendingTsa = AnchorReceipt.pending(AnchorKind.RFC3161_TSA, ROOT,
                Instant.parse("2026-08-24T00:00:00Z"), new byte[] { 1 }, "p", Map.of());

        String shown = AnchorService.claimLimitsFor(pendingTsa);

        assertTrue(shown.contains("NOT a time proof"),
                "a pending TSA attempt was shown the sentence a CONFIRMED token earns: " + shown);
        // NOT `claimLimitsFor(kind) == claimLimitsFor(confirmedReceipt)` — the test fixture
        // builds a confirmed receipt with kind.timeSemantics(), so both sides reduce to the
        // same call and the assertion is a tautology. This is the SECOND time that shape
        // appeared in this work; assert the content instead.
        assertTrue(AnchorService.claimLimitsFor(AnchorKind.RFC3161_TSA).contains("accuracy"),
                "a confirmed TSA token stopped getting the accuracy-bounded sentence");
    }

    @Test
    @DisplayName("the RENDERED output uses the receipt's limits, not its rung's")
    void theRenderedLimitsFollowTheReceipt() {
        // The helper being right is not enough: both render paths call it, and either could go
        // back to passing `receipt.kind()`. A reviewer showed that reverting both call sites
        // left every test passing.
        AnchorService service = new AnchorService();
        service.setStore(storeAt(5));
        service.setTargets(List.of(pendingRung(AnchorKind.RFC3161_TSA)));

        AnchorService.Outcome outcome = service.anchor(
                EvidenceCheckpoint.of(DOMAIN, 0, 5, ROOT, null, "2026-08-24T00:00:00Z"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts =
                (List<Map<String, Object>>) outcome.asMap().get("receipts");
        String rendered = String.valueOf(receipts.get(0).get("claimLimits"));

        assertTrue(rendered.contains("NOT a time proof"),
                "the report rendered a pending TSA attempt beside the sentence a CONFIRMED "
                        + "token earns: " + rendered);
    }

    /** A rung that stays PENDING, for the rendering test. */
    private static AnchorTarget pendingRung(AnchorKind kind) {
        return new AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return kind;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AnchorReceipt anchor(String hexDigest) {
                return AnchorReceipt.pending(kind, hexDigest, Instant.now(), new byte[] { 1 },
                        "p", Map.of());
            }
        };
    }

    /** A rung that always fails, for the downgrade tests. */
    private static AnchorTarget failingRung(AnchorKind kind) {
        return new AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return kind;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AnchorReceipt anchor(String hexDigest) {
                return AnchorReceipt.failed(kind, hexDigest, Instant.now(), "TSA unreachable");
            }
        };
    }

    // ---- the codec must not be able to strengthen a receipt on reload ----

    @Test
    @DisplayName("a stored row that says CONFIRMED with no proof reloads as FAILED")
    void aForgedConfirmationDoesNotSurviveReload() {
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("kind", "RFC3161_TSA");
        forged.put("status", "CONFIRMED");
        forged.put("timeSemantics", "BIDIRECTIONAL_WITHIN_ACCURACY");
        forged.put("anchoredDigest", ROOT);
        forged.put("attemptedAt", "2026-08-24T00:00:00Z");
        forged.put("anchoredAt", "2026-08-24T00:00:00Z");
        forged.put("proofBase64", null);

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(forged);

        // Anyone who can edit this database could otherwise write "CONFIRMED" and have the
        // evidence report render an anchor that no authority ever issued.
        assertEquals(AnchorStatus.FAILED, reloaded.status(),
                "a row claiming CONFIRMED with no proof was rebuilt as a confirmed anchor");
        assertTrue(reloaded.failureReason().contains("no proof"), reloaded.failureReason());
    }

    @Test
    @DisplayName("a proof that does not hash to its recorded digest reloads as FAILED")
    void aProofInconsistentWithItsDigestIsRefused() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "RFC3161_TSA");
        row.put("status", "CONFIRMED");
        row.put("anchoredDigest", ROOT);
        row.put("attemptedAt", "2026-08-24T00:00:00Z");
        row.put("anchoredAt", "2026-08-24T00:00:00Z");
        row.put("proofBase64", java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2 }));
        row.put("proofDigest", "0000000000000000000000000000000000000000000000000000000000000000");

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(row);

        // Catches a partial write or a corrupted blob. It does NOT stop somebody who can edit
        // the database from writing a matching pair, and the message does not pretend it does.
        assertEquals(AnchorStatus.FAILED, reloaded.status(),
                "a row whose two halves disagree was rebuilt as a confirmed anchor");
        assertTrue(reloaded.failureReason().contains("does not hash to"),
                reloaded.failureReason());
    }

    @Test
    @DisplayName("a CONFIRMED OpenTimestamps receipt with no local time survives a reload")
    void aConfirmedOtsWithoutALocalTimeRoundTrips() {
        // The sidecar returns a bitcoinBlockHeight but no block TIME (that needs a Bitcoin
        // node), so OpenTimestampsAnchorTarget mints CONFIRMED with a null anchoredAt. The
        // codec used to refuse exactly that, which made the verdict depend on whether anyone
        // had restarted: confirmed in memory, FAILED after a reload. The factory and the codec
        // must agree; what the receipt cannot say about time is said in its limits instead.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "OPENTIMESTAMPS");
        row.put("status", "CONFIRMED");
        row.put("timeSemantics", "UPPER_BOUND_ONLY");
        row.put("anchoredDigest", ROOT);
        row.put("attemptedAt", "2026-08-24T00:00:00Z");
        row.put("anchoredAt", null);
        byte[] proof = new byte[] { 9, 9, 9, 7 };
        row.put("proofBase64", java.util.Base64.getEncoder().encodeToString(proof));
        row.put("proofDigest", AnchorReceiptCodec.sha256Hex(proof));

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(row);

        assertEquals(AnchorStatus.CONFIRMED, reloaded.status(),
                "a complete .ots proof was downgraded on reload because this deployment has no "
                        + "Bitcoin node to date the block with; the verdict then depends on "
                        + "whether anyone restarted");
        assertNull(reloaded.anchoredAt(),
                "a time was invented for a receipt that does not have one");
    }

    @Test
    @DisplayName("a CONFIRMED row with no proofDigest reloads as FAILED")
    void aConfirmedRowWithNoProofDigestIsRefused() {
        // Every rung records a proofDigest when it mints a confirmed receipt, so a row without
        // one was written by something else. The first version skipped the comparison when the
        // field was absent, which let through exactly the "partial write" case it named.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "RFC3161_TSA");
        row.put("status", "CONFIRMED");
        row.put("anchoredDigest", ROOT);
        row.put("attemptedAt", "2026-08-24T00:00:00Z");
        row.put("anchoredAt", "2026-08-24T00:00:00Z");
        row.put("proofBase64", java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2 }));

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(row);

        assertEquals(AnchorStatus.FAILED, reloaded.status(),
                "a proof with nothing to check it against was rebuilt as a confirmed anchor");
        assertTrue(reloaded.failureReason().contains("no proofDigest"),
                reloaded.failureReason());
    }

    @Test
    @DisplayName("a confirmed receipt with no local time says the time is not here")
    void aConfirmedReceiptWithoutATimeSaysSo() {
        // The UPPER_BOUND_ONLY sentence says the commitment existed "no later than that time".
        // For a confirmed OpenTimestamps proof on a deployment with no Bitcoin node there is no
        // such time in the response, so the sentence pointed at a value that is not there.
        AnchorReceipt timeless = AnchorReceiptCodec.fromDocument(otsConfirmedWithoutATime());

        String limits = AnchorService.claimLimitsFor(timeless);

        assertTrue(limits.contains("does not hold the anchoring time"),
                "the limits point at an anchoring time this response does not carry: " + limits);
    }

    @Test
    @DisplayName("a confirmed receipt WITH a time does not carry that note — the control")
    void aTimedReceiptDoesNotCarryTheNote() {
        // Without this, appending the note unconditionally would pass the test above and every
        // RFC 3161 token would read as though its time were missing.
        AnchorReceipt timed = jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts.confirmed(
                AnchorKind.RFC3161_TSA, ROOT, Instant.parse("2026-08-24T00:00:00Z"),
                new byte[] { 1 }, "ignored", Map.of());

        assertFalse(AnchorService.claimLimitsFor(timed).contains("does not hold the anchoring"),
                "a token that DOES state its time was described as not stating one");
    }

    private static Map<String, Object> otsConfirmedWithoutATime() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "OPENTIMESTAMPS");
        row.put("status", "CONFIRMED");
        row.put("timeSemantics", "UPPER_BOUND_ONLY");
        row.put("anchoredDigest", ROOT);
        row.put("attemptedAt", "2026-08-24T00:00:00Z");
        row.put("anchoredAt", null);
        byte[] proof = { 9, 9, 9, 7 };
        row.put("proofBase64", java.util.Base64.getEncoder().encodeToString(proof));
        row.put("proofDigest", AnchorReceiptCodec.sha256Hex(proof));
        return row;
    }

    @Test
    @DisplayName("an unreadable timeSemantics downgrades rather than assuming the kind's usual")
    void anUnreadableSemanticsDowngrades() {
        CatalogAnchorTarget minter = new CatalogAnchorTarget();
        minter.setEnabled(true);
        minter.setPublisher(digest -> "entity-1");
        Map<String, Object> doc = AnchorReceiptCodec.toDocument(minter.anchor(ROOT));
        doc.put("kind", "RFC3161_TSA");
        doc.put("timeSemantics", "GIBBERISH");

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(doc);

        // An RFC 3161 token without accuracy is deliberately downgraded when it is issued.
        // Falling back to the kind's usual semantics here would undo that on every reload.
        assertEquals(AnchorStatus.CONFIRMED, reloaded.status());
        assertEquals(AnchorKind.TimeSemantics.UPPER_BOUND_ONLY, reloaded.timeSemantics(),
                "a corrupt semantics field was rebuilt as the kind's strongest reading");
    }

    @Test
    @DisplayName("an ordinary receipt round-trips unchanged — the control")
    void anOrdinaryReceiptRoundTrips() {
        // Without this, refusing everything would pass the three tests above.
        CatalogAnchorTarget minter = new CatalogAnchorTarget();
        minter.setEnabled(true);
        minter.setPublisher(digest -> "entity-42");
        AnchorReceipt original = minter.anchor(ROOT);

        AnchorReceipt reloaded =
                AnchorReceiptCodec.fromDocument(AnchorReceiptCodec.toDocument(original));

        assertEquals(AnchorStatus.CONFIRMED, reloaded.status());
        assertEquals(original.kind(), reloaded.kind());
        assertEquals(original.anchoredDigest(), reloaded.anchoredDigest());
        assertEquals(original.anchoredAt(), reloaded.anchoredAt());
        assertEquals(original.proofDigest(), reloaded.proofDigest());
        assertArrayEquals(original.proof(), reloaded.proof());
        assertEquals("entity-42", reloaded.attributes().get("catalogEntityId"));
        assertNull(reloaded.failureReason());
        assertNotNull(reloaded.attemptedAt());
    }
}
