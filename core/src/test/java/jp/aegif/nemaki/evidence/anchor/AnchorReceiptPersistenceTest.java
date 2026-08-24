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
        public void save(String domain, long toSequence, AnchorReceipt receipt) {
            // Round-trip through the codec, because the point of storing is to get it back and
            // a stand-in that hands back the same object would test nothing about that.
            AnchorReceipt reloaded =
                    AnchorReceiptCodec.fromDocument(AnchorReceiptCodec.toDocument(receipt));
            rows.put(domain + ":" + toSequence + ":" + receipt.kind(),
                    new PendingReceipt(domain, toSequence, reloaded));
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
                    new byte[] { 9, 9, 9 }, "pendingproof", Map.of("calendar", "alice"));
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
    @DisplayName("a CONFIRMED row with no anchored time also reloads as FAILED")
    void aConfirmationWithoutATimeIsRefused() {
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("kind", "OPENTIMESTAMPS");
        forged.put("status", "CONFIRMED");
        forged.put("anchoredDigest", ROOT);
        forged.put("attemptedAt", "2026-08-24T00:00:00Z");
        forged.put("proofBase64", java.util.Base64.getEncoder().encodeToString(new byte[] { 1 }));

        AnchorReceipt reloaded = AnchorReceiptCodec.fromDocument(forged);

        assertEquals(AnchorStatus.FAILED, reloaded.status());
        assertTrue(reloaded.failureReason().contains("no anchored time"),
                reloaded.failureReason());
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
