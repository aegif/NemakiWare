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
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An anchor that has not been confirmed does not read as anchored (P2-0).
 *
 * <h2>What is actually being defended</h2>
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}. The failure this layer is built against
 * is not "the anchor did not go out" — that one is loud. It is the quiet one: a submission that
 * stays pending for the hours a Bitcoin block takes, reported as though the proof were in hand;
 * three rungs with very different meanings flattened into the single word "anchored"; or a root
 * anchored after the ledger has already moved past it, fixing a false claim somewhere it cannot
 * be taken back.
 *
 * <p>The rung types themselves ({@code AnchorKind}, {@code AnchorReceipt}) are covered by
 * {@code Rfc3161AnchorTargetTest} and {@code OpenTimestampsAnchorTargetTest}. These tests are
 * about what the ledger adds on top.
 */
class AnchorServiceTest {

    private static final String DOMAIN = "bedroom";
    private static final String ROOT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static EvidenceCheckpoint checkpoint(long toSequence) {
        return EvidenceCheckpoint.of(DOMAIN, 0, toSequence, ROOT, null, "2026-08-24T00:00:00Z");
    }

    private static EvidenceLedgerStore storeAt(long highest) {
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.highestSequence(anyString())).thenReturn(highest);
        return store;
    }

    private static AnchorService serviceWith(EvidenceLedgerStore store, AnchorTarget... targets) {
        AnchorService service = new AnchorService();
        service.setStore(store);
        service.setTargets(List.of(targets));
        return service;
    }

    /** A rung that answers with whatever status the test wants. */
    private static AnchorTarget rung(AnchorKind kind, AnchorStatus status) {
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
                return switch (status) {
                    case PENDING -> AnchorReceipt.pending(kind, hexDigest, Instant.now(),
                            new byte[] { 1 }, "proofdigest", Map.of());
                    case FAILED -> AnchorReceipt.failed(kind, hexDigest, Instant.now(), "no");
                    case NOT_CONFIGURED -> AnchorReceipt.notConfigured(kind, hexDigest);
                    case CONFIRMED -> confirmedFor(kind, hexDigest);
                };
            }
        };
    }

    /**
     * CONFIRMED receipts can only be built inside the anchor package, so borrow a real rung.
     *
     * <p>The borrowed rung stamps its OWN kind on the receipt, so this fixture can only make a
     * confirmed ATLAS_CATALOG one. It used to take {@code kind} and ignore it everywhere except
     * a label, which meant asking for a confirmed OPENTIMESTAMPS rung silently produced a
     * catalog receipt — and {@code AnchorService} now (rightly) turns that mismatch into FAILED,
     * so the test would have been measuring a kind mismatch it never meant to write. Refuse
     * instead of substituting.
     */
    private static AnchorReceipt confirmedFor(AnchorKind kind, String hexDigest) {
        assertEquals(AnchorKind.ATLAS_CATALOG, kind,
                "this fixture can only produce a CONFIRMED ATLAS_CATALOG receipt, because it "
                        + "builds one with a real CatalogAnchorTarget; a confirmed receipt for "
                        + "another rung needs a fixture in that rung's own package");
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher(digest -> "entity-" + kind.name());
        AnchorReceipt receipt = catalog.anchor(hexDigest);
        assertEquals(AnchorStatus.CONFIRMED, receipt.status(), "fixture is not confirmed");
        return receipt;
    }

    // ---- AC 1: PENDING is not confirmed ----

    @Test
    @DisplayName("AC1: a pending commitment is not counted as an anchor")
    void pendingDoesNotCount() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.OPENTIMESTAMPS, AnchorStatus.PENDING)).anchor(checkpoint(5));

        assertEquals(List.of(), outcome.confirmedRungs(),
                "a pending OpenTimestamps commitment was counted as anchored; nothing is "
                        + "proved until a block confirms it, which takes hours");
        assertEquals(AnchorStatus.PENDING, outcome.receipts().get(0).status(),
                "the pending status was not even reported");
    }

    @Test
    @DisplayName("AC1 control: a confirmed anchor IS counted")
    void confirmedCounts() {
        // Without this, counting nothing at all would pass the test above.
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        assertEquals(List.of("ATLAS_CATALOG"), outcome.confirmedRungs());
    }

    // ---- AC 2: every rung's claim travels with it ----

    @Test
    @DisplayName("AC2: each rung's limits come from its TimeSemantics, and differ")
    void everyRungCarriesItsOwnClaim() {
        String catalog = AnchorService.claimLimitsFor(AnchorKind.ATLAS_CATALOG);
        String ots = AnchorService.claimLimitsFor(AnchorKind.OPENTIMESTAMPS);
        String tsa = AnchorService.claimLimitsFor(AnchorKind.RFC3161_TSA);

        // Three different statements. One sentence reused would let the weakest rung borrow
        // the strongest one's meaning, which is the whole hazard of the word "anchored".
        assertTrue(catalog.contains("NOT a time proof"), catalog);
        assertTrue(ots.contains("no later than") && ots.contains("commitment"), ots);
        assertTrue(tsa.contains("accuracy") && tsa.contains("no accreditation"), tsa);
        assertEquals(3, java.util.Set.of(catalog, ots, tsa).size(),
                "two rungs share a limits sentence");
    }

    @Test
    @DisplayName("AC2: the limits reach the report next to the status")
    void theLimitsAreInTheOutput() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED),
                rung(AnchorKind.OPENTIMESTAMPS, AnchorStatus.PENDING)).anchor(checkpoint(5));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts =
                (List<Map<String, Object>>) outcome.asMap().get("receipts");

        for (Map<String, Object> receipt : receipts) {
            assertNotNull(receipt.get("claimLimits"),
                    "a receipt reached the report without its limits: " + receipt);
            List<String> keys = new java.util.ArrayList<>(receipt.keySet());
            assertTrue(keys.indexOf("claimLimits") < keys.indexOf("proofDigest"),
                    "the limits come after the proof (" + keys + "); a reader who stops at the "
                            + "hash takes away the number alone");
        }
    }

    // ---- AC 3: one rung's failure does not stop another ----

    @Test
    @DisplayName("AC3: a throwing rung is reported FAILED and the others still run")
    void oneRungCannotTakeTheOthersDown() {
        AnchorTarget broken = new AnchorTarget() {
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
                throw new IllegalStateException("calendar server unreachable");
            }
        };

        AnchorService.Outcome outcome = serviceWith(storeAt(5), broken,
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        // The whole point of the ladder is that a customer can lean on a different rung.
        assertEquals(2, outcome.receipts().size());
        assertEquals(AnchorStatus.FAILED, outcome.receipts().get(0).status(),
                "a receipt from another rung was accepted as this rung's own, so a buggy rung "
                        + "could report CONFIRMED for a digest nobody asked it about");
        assertEquals(List.of("ATLAS_CATALOG"), outcome.confirmedRungs(),
                "a broken rung stopped a working one");
        assertTrue(outcome.receipts().get(0).failureReason().contains("unreachable"),
                "the failure reason was lost: " + outcome.receipts().get(0).failureReason());
    }

    // ---- AC 4: no anchoring over an unsettled tail ----

    @Test
    @DisplayName("AC4: a checkpoint the ledger has already moved past is not anchored")
    void aStaleRootIsRefused() {
        AnchorService.Outcome outcome = serviceWith(storeAt(9),
                rung(AnchorKind.RFC3161_TSA, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        // Anchoring here would fix, somewhere we cannot rewrite, an assertion that was already
        // false when it was made: that this root was the ledger.
        assertNotNull(outcome.refusedReason());
        assertTrue(outcome.refusedReason().contains("9"), outcome.refusedReason());
        assertEquals(List.of(), outcome.receipts(),
                "the root was sent anyway; a refusal that still sends is not a refusal");
    }

    @Test
    @DisplayName("AC4: an unreadable ledger head refuses too — it does not assume settled")
    void anUnreadableHeadRefuses() {
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.highestSequence(anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));

        AnchorService.Outcome outcome = serviceWith(store,
                rung(AnchorKind.RFC3161_TSA, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        assertNotNull(outcome.refusedReason(),
                "an unreadable head was treated as 'nothing is behind this root'");
        assertEquals(List.of(), outcome.confirmedRungs());
    }

    @Test
    @DisplayName("a checkpoint that does not hash to its own contents is not anchored")
    void anAlteredCheckpointIsRefused() {
        // EvidenceCheckpoint.selfVerifies() existed with NO production caller. A row whose
        // root or range was edited still hashes to something — just not to its own contents —
        // and anchoring it fixes the edited value somewhere it cannot be taken back.
        EvidenceCheckpoint sound = checkpoint(5);
        EvidenceCheckpoint altered = new EvidenceCheckpoint(DOMAIN, 0, 5,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", null,
                sound.createdAt(), sound.checkpointHash());

        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.RFC3161_TSA, AnchorStatus.CONFIRMED)).anchor(altered);

        assertNotNull(outcome.refusedReason(),
                "an altered checkpoint was sent to an external timestamp service");
        assertTrue(outcome.refusedReason().contains("does not hash to its own contents"),
                outcome.refusedReason());
        assertEquals(List.of(), outcome.receipts());
    }

    @Test
    @DisplayName("AC4 control: a current checkpoint IS anchored")
    void aCurrentRootGoesOut() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        assertNull(outcome.refusedReason(),
                "a current checkpoint was refused, so the refusal tests above prove nothing");
        assertEquals(List.of("ATLAS_CATALOG"), outcome.confirmedRungs());
    }

    @Test
    @DisplayName("a receipt for a DIFFERENT rung is refused")
    void aReceiptFromAnotherRungIsRefused() {
        // upgradePending already checked the kind; the first anchoring accepted whatever came
        // back, so a buggy rung could return CONFIRMED for another rung and the Outcome would
        // still name our root and count it (review).
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
                return confirmedFor(AnchorKind.ATLAS_CATALOG, hexDigest);
            }
        };

        AnchorService.Outcome outcome = serviceWith(storeAt(5), confused).anchor(checkpoint(5));

        assertEquals(List.of(), outcome.confirmedRungs(),
                "a receipt from another rung was counted as this rung's anchor");
        assertEquals(AnchorStatus.FAILED, outcome.receipts().get(0).status(),
                "a receipt from another rung was accepted as this rung's own, so a buggy rung "
                        + "could report CONFIRMED for a digest nobody asked it about");
    }

    @Test
    @DisplayName("a receipt for a DIFFERENT value is refused")
    void aReceiptForAnotherDigestIsRefused() {
        AnchorTarget wanderer = new AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return AnchorKind.ATLAS_CATALOG;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AnchorReceipt anchor(String hexDigest) {
                // Anchors something else entirely.
                return confirmedFor(AnchorKind.ATLAS_CATALOG,
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            }
        };

        AnchorService.Outcome outcome = serviceWith(storeAt(5), wanderer).anchor(checkpoint(5));

        assertEquals(List.of(), outcome.confirmedRungs(),
                "an anchor of a different value was reported as this checkpoint's anchor, "
                        + "beside this checkpoint's root");
        assertTrue(outcome.receipts().get(0).failureReason().contains("different value"),
                outcome.receipts().get(0).failureReason());
    }

    @Test
    @DisplayName("a matching receipt is still accepted — the control")
    void aMatchingReceiptIsAccepted() {
        // Without this, refusing everything would pass the two tests above.
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED)).anchor(checkpoint(5));

        assertEquals(List.of("ATLAS_CATALOG"), outcome.confirmedRungs());
    }

    // ---- AC 5 / AC 6 ----

    @Test
    @DisplayName("AC5: an unconfigured rung is NOT_CONFIGURED, not FAILED")
    void anUnconfiguredRungIsNotAFailure() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(false);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));

        // FAILED reads as an outage somebody has to investigate. "Off" is a choice.
        assertEquals(AnchorStatus.NOT_CONFIGURED, outcome.receipts().get(0).status());
    }

    @Test
    @DisplayName("AC5: an enabled catalog with no publisher is also NOT_CONFIGURED")
    void anUnwiredCatalogIsNotAFailure() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));

        assertEquals(AnchorStatus.NOT_CONFIGURED, outcome.receipts().get(0).status(),
                "an enabled-but-unwired catalog reported FAILED, which buries the real cause "
                        + "(nobody configured a catalog) under an apparent outage");
    }

    @Test
    @DisplayName("AC6: the result is per-rung, not one word")
    void theResultIsNotOneFlag() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED),
                rung(AnchorKind.OPENTIMESTAMPS, AnchorStatus.PENDING)).anchor(checkpoint(5));

        assertEquals(List.of("ATLAS_CATALOG"), outcome.asMap().get("confirmedRungs"),
                "the confirmed rungs were not enumerated; 'anchored' as one word lets a "
                        + "catalog-only deployment borrow a timestamp authority's sentence");
    }

    @Test
    @DisplayName("a catalog write with no entity id is FAILED, not confirmed")
    void aSilentCatalogWriteIsNotAnAnchor() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher(digest -> null);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));
        AnchorReceipt receipt = outcome.receipts().get(0);

        assertEquals(AnchorStatus.FAILED, receipt.status(),
                "a catalog that returned no entity id was recorded as a confirmed anchor; "
                        + "there would be nothing to check it against");
        // The status alone is held up by AnchorReceipt's own refusal of an empty proof, which
        // surfaces as a generic argument error. Asserting the REASON is what pins this class's
        // own check — and the reason is the line an operator actually reads.
        assertTrue(receipt.failureReason().contains("entity id"),
                "the failure says only that something was wrong, not that the catalog returned "
                        + "no entity id: " + receipt.failureReason());
    }

    @Test
    @DisplayName("the catalog rung's proof is the entity reference, not the digest itself")
    void theCatalogProofIsNotTheDigest() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher(digest -> "atlas-entity-77");

        AnchorReceipt receipt = catalog.anchor(ROOT);

        // A "proof" that is the anchored value would prove the anchor from the thing being
        // anchored. What a checker needs is where to go and see the same digest.
        assertEquals("atlas-entity-77", receipt.attributes().get("catalogEntityId"));
        assertEquals("atlas-entity-77", new String(receipt.proof(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- retrying only the rungs that hold nothing ----

    /** A receipt store holding exactly what the test says, and recording what was written. */
    private static AnchorReceiptStore receiptsHolding(List<AnchorReceipt> stored,
            List<AnchorReceipt> written) {
        return new AnchorReceiptStore() {
            @Override public SaveOutcome save(String domain, long toSequence,
                    AnchorReceipt receipt) {
                written.add(receipt);
                return SaveOutcome.STORED;
            }

            @Override public List<AnchorReceipt> forCheckpoint(String domain, long toSequence) {
                return stored;
            }

            @Override public List<PendingReceipt> pending(String domain, int limit) {
                return List.of();
            }

            @Override public List<PendingReceipt> confirmed(String domain, int limit) {
                return List.of();
            }

            @Override public boolean isActive() {
                return true;
            }
        };
    }

    private static AnchorService serviceWithReceipts(AnchorReceiptStore receipts,
            AnchorTarget... targets) {
        AnchorService service = serviceWith(storeAt(5), targets);
        service.setReceiptStore(receipts);
        return service;
    }

    @Test
    @DisplayName("a rung that already holds a commitment is not contacted again")
    void aSettledRungIsNotReAnchored() {
        List<AnchorReceipt> written = new java.util.ArrayList<>();
        AnchorReceipt alreadyConfirmed = confirmedFor(AnchorKind.ATLAS_CATALOG, ROOT);
        AnchorService service = serviceWithReceipts(
                receiptsHolding(List.of(alreadyConfirmed), written),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED));

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        assertEquals(List.of(), outcome.receipts(),
                "a settled rung was contacted again, which mints a second commitment nobody "
                        + "needs");
        assertEquals(List.of(), written, "a second receipt was written for a settled rung");
        // NOT a refusal. Reporting "everything is already settled" as refused meant a healthy
        // deployment answered refused:true on every call, which is how an operator learns to
        // ignore the field. Nothing stopped us; there was nothing to do. Which of the two an
        // empty list means is said by the controller, in its message.
        assertNull(outcome.refusedReason(),
                "a healthy checkpoint with every rung settled was reported as a refusal: "
                        + outcome.refusedReason());
    }

    @Test
    @DisplayName("an unconfigured rung is not 'holding nothing' — it is not contacted")
    void anUnconfiguredRungIsNotRetried() {
        // Every rung is constructed and answers isConfigured() from configuration, so a default
        // deployment has three rungs that are not configured. Without this they qualify as
        // "holds nothing" for ever: the retry rewrote three NOT_CONFIGURED rows on every call,
        // adding revisions and no information.
        List<AnchorReceipt> written = new java.util.ArrayList<>();
        AnchorTarget unconfigured = new AnchorTarget() {
            @Override public AnchorKind kind() {
                return AnchorKind.RFC3161_TSA;
            }

            @Override public boolean isConfigured() {
                return false;
            }

            @Override public AnchorReceipt anchor(String hexDigest) {
                throw new AssertionError("an unconfigured rung was contacted");
            }
        };
        AnchorService service = serviceWithReceipts(
                receiptsHolding(List.of(), written), unconfigured);

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        assertEquals(List.of(), outcome.receipts(),
                "an unconfigured rung produced a receipt on the retry path");
        assertEquals(List.of(), written,
                "a NOT_CONFIGURED row was rewritten; on a timer that is one write per rung per "
                        + "call for ever, with no information added");
    }

    @Test
    @DisplayName("a rung that failed IS retried — the control")
    void aFailedRungIsRetried() {
        // Without this, refusing to contact anything would pass the test above while leaving
        // the original hole: a sealed checkpoint whose anchor failed can never be anchored.
        List<AnchorReceipt> written = new java.util.ArrayList<>();
        AnchorService service = serviceWithReceipts(
                receiptsHolding(List.of(AnchorReceipt.failed(AnchorKind.ATLAS_CATALOG, ROOT,
                        Instant.now(), "the catalog was down")), written),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED));

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        assertEquals(List.of("ATLAS_CATALOG"), outcome.confirmedRungs(),
                "a rung whose earlier attempt FAILED was not retried, so the checkpoint stays "
                        + "unanchored for ever: the seal cannot be redone and upgrade-pending "
                        + "only looks at PENDING rows");
        assertEquals(1, written.size(), "the retry's receipt was not stored");
    }

    @Test
    @DisplayName("a PENDING commitment counts as held — a second submission is not a retry")
    void aPendingRungIsNotReSubmitted() {
        List<AnchorReceipt> written = new java.util.ArrayList<>();
        AnchorService service = serviceWithReceipts(
                receiptsHolding(List.of(AnchorReceipt.pending(AnchorKind.OPENTIMESTAMPS, ROOT,
                        Instant.now(), new byte[] { 1 }, "proofdigest", Map.of())), written),
                rung(AnchorKind.OPENTIMESTAMPS, AnchorStatus.PENDING));

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        assertEquals(List.of(), outcome.receipts(),
                "a live commitment waiting on a block was submitted a second time");
    }

    @Test
    @DisplayName("without a receipt store it refuses rather than contacting every rung")
    void noReceiptStoreMeansNoBlanketRetry() {
        // Not knowing which rungs are settled is not a licence to contact all of them: that is
        // exactly the re-stamp of an already-settled commitment this path was built to avoid.
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED))
                .retryUnsettled(checkpoint(5));

        assertEquals(List.of(), outcome.receipts());
        assertNotNull(outcome.refusedReason());
        assertTrue(outcome.refusedReason().contains("second commitment"),
                "the refusal does not say what it is protecting: " + outcome.refusedReason());
    }

    @Test
    @DisplayName("a stale root is refused on the retry path too")
    void aStaleRootIsNotReAnchoredEither() {
        // The retry path is a second way to reach anchoring, and the first thing it must not do
        // is become a way around the refusals the ordinary path makes.
        AnchorService.Outcome outcome = serviceWithReceipts(
                receiptsHolding(List.of(), new java.util.ArrayList<>()),
                rung(AnchorKind.ATLAS_CATALOG, AnchorStatus.CONFIRMED))
                .retryUnsettled(EvidenceCheckpoint.of(DOMAIN, 0, 3, ROOT, null,
                        "2026-08-24T00:00:00Z"));

        assertEquals(List.of(), outcome.receipts(),
                "a root the ledger has already moved past was anchored through the retry path");
        assertTrue(outcome.refusedReason().contains("already is not"),
                "the refusal is not the currency one: " + outcome.refusedReason());
    }

    // ---- a rung that does not say what it anchored ----

    @Test
    @DisplayName("a receipt that names no digest is not called 'a different value'")
    void aReceiptWithNoDigestSaysThat() {
        AnchorTarget silent = new AnchorTarget() {
            @Override public AnchorKind kind() {
                return AnchorKind.ATLAS_CATALOG;
            }

            @Override public boolean isConfigured() {
                return true;
            }

            @Override public AnchorReceipt anchor(String hexDigest) {
                return AnchorReceipt.pending(AnchorKind.ATLAS_CATALOG, null, Instant.now(),
                        new byte[] { 1 }, "proofdigest", Map.of());
            }
        };

        AnchorReceipt receipt = serviceWith(storeAt(5), silent).anchor(checkpoint(5))
                .receipts().get(0);

        assertEquals(AnchorStatus.FAILED, receipt.status());
        assertTrue(receipt.failureReason().contains("does not say what it anchored"),
                "'it did not say' was reported as 'it anchored something else', which states "
                        + "more than is known: " + receipt.failureReason());
    }

    // ---- the missing-anchoring-time note belongs to ONE rung ----

    /**
     * A CONFIRMED receipt carrying no anchoring time, for whichever rung the test names.
     *
     * <p>Built through the real decode path because that is the only way one can occur: every
     * rung that mints a confirmed receipt records a time, so this shape arrives from a STORED
     * row whose {@code anchoredAt} is absent or unparseable — {@code instant()} swallows a bad
     * value and hands back null, and {@code confirmedOrRefuse} rebuilds the row as CONFIRMED.
     */
    private static AnchorReceipt confirmedWithNoAnchoringTime(AnchorKind kind,
            AnchorKind.TimeSemantics semantics) {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher(digest -> "atlas-entity-1");
        Map<String, Object> doc = new java.util.LinkedHashMap<>(
                jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec.toDocument(
                        catalog.anchor(ROOT)));
        doc.put("anchoredAt", "not-a-timestamp");
        doc.put("kind", kind.name());
        doc.put("timeSemantics", semantics.name());

        AnchorReceipt receipt =
                jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec.fromDocument(doc);
        assertEquals(AnchorStatus.CONFIRMED, receipt.status(), "fixture is not confirmed");
        assertNull(receipt.anchoredAt(), "fixture still carries an anchoring time");
        return receipt;
    }

    @Test
    @DisplayName("a timeless CATALOG receipt does not borrow OpenTimestamps' block")
    void aTimelessCatalogReceiptDoesNotBorrowTheBlock() {
        // The note was one sentence for every kind, and the sentence was written for
        // OpenTimestamps. On a catalog receipt it invents a complete proof and a block that do
        // not exist, and promotes a rung whose own limits open with "this is NOT a time proof".
        // A note whose job is to stop a weaker fact reading as a stronger one must not be the
        // thing that does it.
        String limits = AnchorService.claimLimitsFor(confirmedWithNoAnchoringTime(
                AnchorKind.ATLAS_CATALOG, AnchorKind.TimeSemantics.NOT_A_TIME_PROOF));

        assertTrue(limits.contains("NOT a time proof"),
                "the catalog rung stopped saying what it is not: " + limits);
        assertFalse(limits.contains("block"),
                "a catalog receipt was told a third party can read its time from a block; there "
                        + "is no block and no proof: " + limits);
        assertFalse(limits.contains("The proof is complete"),
                "a catalog entry was called a complete proof: " + limits);
    }

    @Test
    @DisplayName("a timeless OPENTIMESTAMPS receipt still says where the time can be read")
    void aTimelessOpenTimestampsReceiptStillPointsAtTheBlock() {
        // The control for the test above: removing the note entirely would also make that one
        // pass, and would drop the one rung the sentence was true for.
        String limits = AnchorService.claimLimitsFor(confirmedWithNoAnchoringTime(
                AnchorKind.OPENTIMESTAMPS, AnchorKind.TimeSemantics.UPPER_BOUND_ONLY));

        assertTrue(limits.contains("block"),
                "the reader is not told the time is in the block: " + limits);
        assertTrue(limits.contains("no time in this response"),
                "the reader is not warned off the times that ARE in the response: " + limits);
    }

    @Test
    @DisplayName("a timeless RFC 3161 receipt points at the token, not at a block")
    void aTimelessTsaReceiptPointsAtTheToken() {
        String limits = AnchorService.claimLimitsFor(confirmedWithNoAnchoringTime(
                AnchorKind.RFC3161_TSA, AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY));

        assertTrue(limits.contains("inside the token"),
                "the reader is not told where the authority's time actually is: " + limits);
        assertFalse(limits.contains("read the time from the block"),
                "a TSA token was described as committing to a block: " + limits);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("every configured rung FAILED is a refusal, not a success")
    void everyRungFailedIsARefusal() {
        // AnchorTarget's contract is that ordinary remote failure comes back as a FAILED
        // RECEIPT rather than an exception -- all three implementations follow it. anchor()
        // collected those receipts and still returned refusedReason == null, and the controller
        // arm that decides the HTTP status reads only refusedReason. So a checkpoint anchored
        // NOWHERE came back 200 success, with the failure visible only in the nested rows.
        AnchorService service = serviceWith(storeAt(5), alwaysFailing(AnchorKind.RFC3161_TSA));

        AnchorService.Outcome outcome = service.anchor(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertNotNull(outcome.refusedReason(),
                "every configured rung failed and the outcome reports no refusal: "
                        + outcome.asMap());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.refusedReason().contains("not anchored anywhere"),
                outcome.refusedReason());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("one rung settling is NOT a refusal — the control")
    void oneSettledRungIsNotARefusal() {
        // Without this, refusing whenever any rung failed would make a partially-anchored
        // checkpoint -- the ordinary case with three rungs and one outage -- read as anchored
        // nowhere.
        jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget catalog =
                new jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher(digest -> "entity-1");
        AnchorService service =
                serviceWith(storeAt(5), alwaysFailing(AnchorKind.RFC3161_TSA), catalog);

        AnchorService.Outcome outcome = service.anchor(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertNull(outcome.refusedReason(),
                "a checkpoint with one settled rung was reported as anchored nowhere: "
                        + outcome.refusedReason());
    }

    private static jp.aegif.nemaki.rest.purview.anchor.AnchorTarget alwaysFailing(
            AnchorKind kind) {
        return new jp.aegif.nemaki.rest.purview.anchor.AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return kind;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt anchor(String digest) {
                return jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt.failed(kind, digest,
                        java.time.Instant.parse("2026-08-28T00:00:00Z"), "the TSA was down");
            }
        };
    }


    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a receipt row that could not be READ stops a re-anchor")
    void unreadableRowsStopARetry() {
        // A row that cannot be decoded is not an absent receipt. The store dropped such rows
        // silently, so an unreadable PENDING or CONFIRMED receipt looked like a rung that had
        // never been anchored -- and retryUnsettled contacts exactly those. Contacting a rung
        // that is already committed mints a second OpenTimestamps commitment, or BUYS A SECOND
        // RFC 3161 TOKEN. The store's exception path was already a refusal; its decode path
        // was not.
        AnchorReceiptStore store = new StubStore() {
            @Override
            public int unreadableCount() {
                return 2;
            }
        };
        AnchorService service = serviceWith(storeAt(5), alwaysFailing(AnchorKind.RFC3161_TSA));
        service.setReceiptStore(store);

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertNotNull(outcome.refusedReason(),
                "rows that could not be read were treated as rungs with nothing, so a settled "
                        + "rung is about to be contacted again");
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.refusedReason().contains("could not be read"), outcome.refusedReason());
        org.junit.jupiter.api.Assertions.assertTrue(outcome.receipts().isEmpty(),
                "a rung was contacted anyway");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("readable rows do NOT stop a retry — the control")
    void readableRowsDoNotStopARetry() {
        // Without this, refusing whenever the count is consulted would make every retry a
        // refusal and the way back from a failed anchor would be closed again.
        AnchorService service = serviceWith(storeAt(5), alwaysFailing(AnchorKind.RFC3161_TSA));
        service.setReceiptStore(new StubStore());

        AnchorService.Outcome outcome = service.retryUnsettled(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertNull(outcome.refusedReason(),
                "a store with nothing unreadable refused the retry: " + outcome.refusedReason());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a commitment whose receipt could not be STORED is a refusal")
    void aLostReceiptIsARefusal() {
        // The worst outcome in this class: the external commitment HAS been made and this
        // deployment has no record of it. For a PENDING OpenTimestamps receipt the proof cannot
        // be recovered by re-anchoring -- re-anchoring mints a NEW commitment -- and persist()
        // caught the write failure, logged it, and returned. anchor() reported no refusal, so
        // the controller answered 200 success over a proof that had just been lost.
        AnchorReceiptStore refusingToWrite = new StubStore() {
            @Override
            public SaveOutcome save(String domain, long toSequence,
                    jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt receipt) {
                throw new IllegalStateException("couchdb is down");
            }
        };
        AnchorService service = serviceWith(storeAt(5), alwaysPending(AnchorKind.OPENTIMESTAMPS));
        service.setReceiptStore(refusingToWrite);

        AnchorService.Outcome outcome = service.anchor(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertNotNull(outcome.refusedReason(),
                "a commitment was made and its receipt was lost, and the outcome says nothing: "
                        + outcome.asMap());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.refusedReason().contains("NOT stored"), outcome.refusedReason());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a FAILED receipt that could not be stored is not — the control")
    void aLostFailedReceiptIsNotARefusal() {
        // A FAILED receipt has no commitment behind it, so losing the row loses nothing.
        // Without this, refusing on every write failure would turn an ordinary TSA outage into
        // "a proof was lost", which is the strongest sentence this class has.
        AnchorReceiptStore refusingToWrite = new StubStore() {
            @Override
            public SaveOutcome save(String domain, long toSequence,
                    jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt receipt) {
                throw new IllegalStateException("couchdb is down");
            }
        };
        AnchorService service = serviceWith(storeAt(5), alwaysFailing(AnchorKind.RFC3161_TSA));
        service.setReceiptStore(refusingToWrite);

        AnchorService.Outcome outcome = service.anchor(checkpoint(5));

        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.refusedReason() != null
                        && outcome.refusedReason().contains("not anchored anywhere"),
                "a lost FAILED row was reported as a lost proof rather than as a failed rung: "
                        + outcome.refusedReason());
    }

    private static jp.aegif.nemaki.rest.purview.anchor.AnchorTarget alwaysPending(
            AnchorKind kind) {
        return new jp.aegif.nemaki.rest.purview.anchor.AnchorTarget() {
            @Override
            public AnchorKind kind() {
                return kind;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt anchor(String digest) {
                return jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt.pending(kind, digest,
                        java.time.Instant.parse("2026-08-28T00:00:00Z"),
                        new byte[] { 1, 2, 3 }, "d1", java.util.Map.of());
            }
        };
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a store that could not be asked is not 'nothing settled'")
    void anUnaskableStoreIsNotNothingSettled() {
        // anchor() and retryUnsettled() both carry refusal in an Outcome and the controller maps
        // both; this third verb returned a bare List, so "the store is not wired" and "asked,
        // nothing had settled" were the SAME VALUE. The endpoint then told the operator
        // "nothing had settled yet ... not a failure -- DO NOT RE-ANCHOR" for a deployment that
        // had never been asked -- advice to leave a commitment unupgraded for ever.
        //
        // AnchorReceiptStore.isActive()'s javadoc says callers must not read "no pending
        // receipts" from a store that could not be asked. /status, LongTermValidityService and
        // EvidenceRecordService all consult it; this class never did.
        for (AnchorReceiptStore store : new AnchorReceiptStore[] { null, new StubStore() {
                @Override
                public boolean isActive() {
                    return false;
                }
            } }) {
            AnchorService service = new AnchorService();
            service.setReceiptStore(store);

            AnchorService.Upgraded result = service.upgradePending(DOMAIN, 10);

            org.junit.jupiter.api.Assertions.assertNotNull(result.unavailable(),
                    "a store that could not be asked answered as though it had been: "
                            + result.upgraded());
            org.junit.jupiter.api.Assertions.assertTrue(
                    result.unavailable().contains("NOT a finding"), result.unavailable());
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an answered, empty store is NOT unavailable — the control")
    void anAnsweredEmptyStoreIsNotUnavailable() {
        AnchorService service = new AnchorService();
        service.setReceiptStore(new StubStore());

        AnchorService.Upgraded result = service.upgradePending(DOMAIN, 10);

        org.junit.jupiter.api.Assertions.assertNull(result.unavailable(),
                "an answered, empty store was reported as unreachable: " + result.unavailable());
        org.junit.jupiter.api.Assertions.assertTrue(result.upgraded().isEmpty());
    }

    /** Answers everything emptily; subclasses change the one thing under test. */
    private static class StubStore implements AnchorReceiptStore {
        @Override
        public SaveOutcome save(String domain, long toSequence,
                jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt receipt) {
            return SaveOutcome.STORED;
        }

        @Override
        public List<jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt> forCheckpoint(
                String domain, long toSequence) {
            return List.of();
        }

        @Override
        public List<PendingReceipt> pending(String domain, int limit) {
            return List.of();
        }

        @Override
        public List<PendingReceipt> confirmed(String domain, int limit) {
            return List.of();
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a partly-readable pending list refuses BEFORE upgrading")
    void aPartlyReadablePendingListRefusesBeforeActing() {
        // Checked after the loop, this method upgraded and SAVED the rows it could read and
        // then answered "upgradedCount: 0, unavailable" — work that happened, reported as not
        // having happened, on every run until the broken row was repaired. The sibling verb
        // (retryUnsettled) already refuses before acting; the two were asymmetric.
        AnchorReceiptStore store = mock(AnchorReceiptStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.pending(anyString(), anyInt())).thenReturn(java.util.List.of(
                new AnchorReceiptStore.PendingReceipt(DOMAIN, 5L,
                        AnchorReceipt.pending(AnchorKind.RFC3161_TSA, "ab".repeat(32),
                                Instant.now(), new byte[] {1}, "tsa", java.util.Map.of()))));
        when(store.unreadableCount()).thenReturn(1);
        AnchorTarget target = mock(AnchorTarget.class);
        AnchorService service = new AnchorService();
        service.setReceiptStore(store);
        service.setTargets(List.of(target));

        AnchorService.Upgraded result = service.upgradePending(DOMAIN, 10);

        assertTrue(result.upgraded().isEmpty(),
                "rows were upgraded from a list the store could not fully read, so the answer "
                        + "denies work that happened: " + result.upgraded());
        assertNotNull(result.unavailable(), "the refusal carries no reason");
        verify(target, org.mockito.Mockito.never()).upgrade(any());
        verify(store, org.mockito.Mockito.never()).save(anyString(), anyLong(), any());
    }
}
