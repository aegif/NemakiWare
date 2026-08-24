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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An anchor that has not been confirmed does not read as anchored (P2-0).
 *
 * <h2>What is actually being defended</h2>
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}. The failure this layer is built against
 * is not "the anchor did not go out" — that one is loud. It is the quiet one: a submission that
 * is pending for the hours a Bitcoin block takes, reported as though the proof were already in
 * hand; or three tiers with very different meanings flattened into the single word "anchored",
 * so a deployment running only a catalog can borrow the sentence that belongs to a timestamp
 * authority.
 */
class AnchorServiceTest {

    private static final String DOMAIN = "bedroom";
    private static final String ROOT = "abc123";

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

    /** A tier that answers with whatever state the test wants. */
    private static AnchorTarget tier(String id, AnchorState state) {
        return new AnchorTarget() {
            @Override
            public String tierId() {
                return id;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String claimLimits() {
                return "tier " + id + " does not establish the truth of the record";
            }

            @Override
            public AnchorReceipt submit(String domain, long from, long to, String root,
                    String createdAt) {
                return new AnchorReceipt(id, state, domain, from, to, root, createdAt,
                        state == AnchorState.CONFIRMED ? createdAt : null,
                        state == AnchorState.CONFIRMED ? "proof-" + id : null, null,
                        claimLimits());
            }
        };
    }

    // ---- AC 1: SUBMITTED is not confirmed ----

    @Test
    @DisplayName("AC1: a pending submission is not counted as an anchor")
    void submittedDoesNotCount() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                tier("opentimestamps", AnchorState.SUBMITTED)).anchor(checkpoint(5));

        assertEquals(List.of(), outcome.confirmedTiers(),
                "a pending OpenTimestamps submission was counted as anchored; nothing is proved "
                        + "until a block confirms it, which takes hours");
        assertEquals(AnchorState.SUBMITTED, outcome.receipts().get(0).state(),
                "the pending state was not even reported");
        assertFalse(outcome.receipts().get(0).counts());
    }

    @Test
    @DisplayName("AC1 control: a confirmed submission IS counted")
    void confirmedCounts() {
        // Without this, counting nothing at all would pass the test above.
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                tier("rfc3161", AnchorState.CONFIRMED)).anchor(checkpoint(5));

        assertEquals(List.of("rfc3161"), outcome.confirmedTiers());
    }

    // ---- AC 2: a tier must say what it does not establish ----

    @Test
    @DisplayName("AC2: a receipt cannot be built without claimLimits")
    void aReceiptNeedsItsLimits() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AnchorReceipt("rfc3161", AnchorState.CONFIRMED, DOMAIN, 0, 5, ROOT,
                        "t", "t", "token", null, "  "));
        assertTrue(e.getMessage().contains("claimLimits"), e.getMessage());
    }

    @Test
    @DisplayName("AC2: a tier that declares no limits is FAILED, not silently anchored")
    void aTierWithoutLimitsCannotAnchor() {
        AnchorTarget mute = new AnchorTarget() {
            @Override
            public String tierId() {
                return "mute";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String claimLimits() {
                return "";
            }

            @Override
            public AnchorReceipt submit(String d, long f, long t, String r, String c) {
                throw new AssertionError("submit must not be reached: a tier that cannot state "
                        + "its limits must not be allowed to produce an anchor at all");
            }
        };

        AnchorService.Outcome outcome = serviceWith(storeAt(5), mute).anchor(checkpoint(5));

        assertEquals(AnchorState.FAILED, outcome.receipts().get(0).state());
        assertEquals(List.of(), outcome.confirmedTiers());
    }

    @Test
    @DisplayName("AC2: a CONFIRMED receipt must carry a confirmedAt")
    void confirmedNeedsATime() {
        assertThrows(IllegalArgumentException.class,
                () -> new AnchorReceipt("rfc3161", AnchorState.CONFIRMED, DOMAIN, 0, 5, ROOT,
                        "t", null, "token", null, "limits"),
                "a confirmation with no time was accepted; that is what copying the pending "
                        + "branch produces");
    }

    // ---- AC 3: one tier's failure does not stop another ----

    @Test
    @DisplayName("AC3: a throwing tier is reported FAILED and the others still run")
    void oneTierCannotTakeTheOthersDown() {
        AnchorTarget broken = new AnchorTarget() {
            @Override
            public String tierId() {
                return "opentimestamps";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String claimLimits() {
                return "the subject is the commitment, not the record";
            }

            @Override
            public AnchorReceipt submit(String d, long f, long t, String r, String c) {
                throw new IllegalStateException("calendar server unreachable");
            }
        };

        AnchorService.Outcome outcome = serviceWith(storeAt(5), broken,
                tier("rfc3161", AnchorState.CONFIRMED)).anchor(checkpoint(5));

        // The whole point of the ladder is that a customer can lean on a different rung.
        assertEquals(2, outcome.receipts().size());
        assertEquals(AnchorState.FAILED, outcome.receipts().get(0).state());
        assertEquals(List.of("rfc3161"), outcome.confirmedTiers(),
                "a broken tier stopped a working one");
        assertTrue(outcome.receipts().get(0).reason().contains("unreachable"),
                "the failure reason was lost: " + outcome.receipts().get(0).reason());
    }

    // ---- AC 4: no anchoring over an unsettled tail ----

    @Test
    @DisplayName("AC4: a checkpoint the ledger has already moved past is not anchored")
    void aStaleRootIsRefused() {
        AnchorTarget target = tier("rfc3161", AnchorState.CONFIRMED);
        AnchorService.Outcome outcome = serviceWith(storeAt(9), target).anchor(checkpoint(5));

        // Anchoring here would fix, in a place we cannot rewrite, an assertion that was already
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

        AnchorService.Outcome outcome =
                serviceWith(store, tier("rfc3161", AnchorState.CONFIRMED)).anchor(checkpoint(5));

        assertNotNull(outcome.refusedReason(),
                "an unreadable head was treated as 'nothing is behind this root'");
        assertEquals(List.of(), outcome.confirmedTiers());
    }

    @Test
    @DisplayName("AC4 control: a current checkpoint IS anchored")
    void aCurrentRootGoesOut() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                tier("rfc3161", AnchorState.CONFIRMED)).anchor(checkpoint(5));

        assertEquals(null, outcome.refusedReason(),
                "a current checkpoint was refused, so the refusal tests above prove nothing");
        assertEquals(List.of("rfc3161"), outcome.confirmedTiers());
    }

    // ---- AC 5 / AC 6 ----

    @Test
    @DisplayName("AC5: a disabled tier is NOT_ATTEMPTED, not FAILED")
    void aDisabledTierIsNotAFailure() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(false);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));

        // FAILED reads as an outage somebody has to investigate. "Off" is a choice.
        assertEquals(AnchorState.NOT_ATTEMPTED, outcome.receipts().get(0).state());
    }

    @Test
    @DisplayName("AC5: an enabled catalog with no publisher is also NOT_ATTEMPTED")
    void anUnwiredCatalogIsNotAFailure() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));

        assertEquals(AnchorState.NOT_ATTEMPTED, outcome.receipts().get(0).state(),
                "an enabled-but-unwired catalog reported FAILED, which buries the real cause "
                        + "(nobody configured a catalog) under an apparent outage");
    }

    @Test
    @DisplayName("AC6: the result is per-tier, and every tier's limits come with it")
    void theResultIsNotOneFlag() {
        AnchorService.Outcome outcome = serviceWith(storeAt(5),
                tier("catalog", AnchorState.CONFIRMED),
                tier("opentimestamps", AnchorState.SUBMITTED)).anchor(checkpoint(5));
        Map<String, Object> body = outcome.asMap();

        assertEquals(List.of("catalog"), body.get("confirmedTiers"),
                "the confirmed tiers were not enumerated; 'anchored' as one word lets a "
                        + "catalog-only deployment borrow a timestamp authority's sentence");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) body.get("receipts");
        assertEquals(2, receipts.size());
        for (Map<String, Object> receipt : receipts) {
            assertNotNull(receipt.get("claimLimits"),
                    "a receipt reached the report without its limits: " + receipt);
        }
    }

    @Test
    @DisplayName("the catalog tier says it is neither a time proof nor independent")
    void theCatalogTierAdmitsWhatItIs() {
        String limits = new CatalogAnchorTarget().claimLimits().toLowerCase();

        assertTrue(limits.contains("not a time proof") || limits.contains("not") && limits
                .contains("time proof"), limits);
        assertTrue(limits.contains("both"),
                "the catalog tier does not say an administrator who reaches both systems "
                        + "defeats it: " + limits);
    }

    @Test
    @DisplayName("a catalog write with no entity id is FAILED, not confirmed")
    void aSilentCatalogWriteIsNotAnAnchor() {
        CatalogAnchorTarget catalog = new CatalogAnchorTarget();
        catalog.setEnabled(true);
        catalog.setPublisher((d, f, t, r, c) -> null);

        AnchorService.Outcome outcome = serviceWith(storeAt(5), catalog).anchor(checkpoint(5));

        assertEquals(AnchorState.FAILED, outcome.receipts().get(0).state(),
                "a catalog that returned no entity id was recorded as a confirmed anchor; "
                        + "there would be nothing to check it against");
    }
}
