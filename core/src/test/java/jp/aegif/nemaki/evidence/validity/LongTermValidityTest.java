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
package jp.aegif.nemaki.evidence.validity;

import jp.aegif.nemaki.evidence.validity.AlgorithmRegistry.Declaration;
import jp.aegif.nemaki.evidence.validity.AlgorithmRegistry.Soundness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noticing that an algorithm is going stale, before it is too late to act (P2-3).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>RFC 4998 defines both renewal operations and then states that watching for the moment
 * renewal becomes necessary is out of its scope. Adopting ERS therefore does not supply the part
 * that notices, and renewal applied after a break re-dates the evidence to the renewal — so a
 * watch that never fires is not a delay, it is a permanent loss of the original time.
 *
 * <p>These tests protect the three ways the watch could quietly fail: an unlisted algorithm
 * passing as sound, the migration window disappearing, and the two renewals — which differ by
 * whether every archived object must be read — being reported as one thing.
 */
class LongTermValidityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Test
    @DisplayName("AC1: an algorithm that is not in the table is UNKNOWN, not SOUND")
    void anUnlistedAlgorithmIsNotSound() {
        AlgorithmRegistry registry = AlgorithmRegistry.withDefaults();

        // A permissive default reports everything as sound at exactly the moment somebody
        // forgot to update the table — which is when it is being relied on.
        assertEquals(Soundness.UNKNOWN, registry.soundnessOf("BLAKE3", TODAY));
        assertEquals(Soundness.UNKNOWN, registry.soundnessOf(null, TODAY));
        assertNotEquals(Soundness.SOUND, registry.soundnessOf("SOMETHING-NEW", TODAY));
    }

    @Test
    @DisplayName("AC1 control: a listed algorithm IS answered")
    void aListedAlgorithmIsAnswered() {
        // Without this, answering UNKNOWN to everything would pass the test above.
        assertEquals(Soundness.SOUND,
                AlgorithmRegistry.withDefaults().soundnessOf("SHA-256", TODAY));
    }

    @Test
    @DisplayName("spellings of one algorithm are one algorithm")
    void spellingsAreNormalised() {
        AlgorithmRegistry registry = AlgorithmRegistry.withDefaults();

        // An OID-derived name and a hand-typed one differ exactly this much, and a miss returns
        // UNKNOWN — which reads as "we could not say" when the truth is "we said it, spelled
        // differently".
        for (String spelling : new String[] { "sha-256", "SHA256", "sha256", " SHA-256 ",
                "SHA_256" }) {
            assertEquals(Soundness.SOUND, registry.soundnessOf(spelling, TODAY),
                    "'" + spelling + "' was not recognised as SHA-256");
        }
    }

    @Test
    @DisplayName("AC2: DEPRECATED is neither SOUND nor UNSOUND")
    void theMigrationWindowExists() {
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("TEST-HASH", LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1), "test"));

        Soundness during = registry.soundnessOf("TEST-HASH", TODAY);

        assertEquals(Soundness.DEPRECATED, during,
                "the migration window collapsed to " + during + "; without it there is no "
                        + "period in which a plan can be made");
    }

    @Test
    @DisplayName("AC3: the answer changes at the declared dates")
    void thedatesAreHonoured() {
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("TEST-HASH", LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1), "test"));

        assertEquals(Soundness.SOUND, registry.soundnessOf("TEST-HASH",
                LocalDate.of(2025, 12, 31)));
        // Inclusive on the day itself: a window that starts "some time after" the declared date
        // is not a window an operator can schedule against.
        assertEquals(Soundness.DEPRECATED, registry.soundnessOf("TEST-HASH",
                LocalDate.of(2026, 1, 1)));
        assertEquals(Soundness.DEPRECATED, registry.soundnessOf("TEST-HASH",
                LocalDate.of(2029, 12, 31)));
        assertEquals(Soundness.UNSOUND, registry.soundnessOf("TEST-HASH",
                LocalDate.of(2030, 1, 1)));
    }

    @Test
    @DisplayName("a declaration that ends before it begins is refused")
    void aNegativeWindowIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Declaration("TEST", LocalDate.of(2030, 1, 1), LocalDate.of(2026, 1, 1),
                        "typo"),
                "an algorithm was allowed to become unsound before it was deprecated");
    }

    // ---- AC 4: the two renewals stay apart ----

    @Test
    @DisplayName("AC4: a failing tree hash is a HASH_TREE renewal, a failing token is TIMESTAMP")
    void theTwoRenewalsAreReportedSeparately() {
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("SHA-256", LocalDate.of(2026, 1, 1), null, "test"));
        LongTermValidityService service = new LongTermValidityService();
        service.setRegistry(registry);

        Map<String, Object> body = service.assess("bedroom", TODAY);

        // The ledger and the content digests both rest on SHA-256, and both need the expensive
        // one. Merging the counts would hide the number that costs money.
        assertEquals(2, body.get("hashTreeRenewalsDue"));
        assertEquals(0, body.get("timestampRenewalsDue"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) body.get("needs");
        for (Map<String, Object> need : needs) {
            if (RenewalNeed.Kind.HASH_TREE_RENEWAL.name().equals(need.get("kind"))) {
                assertTrue(String.valueOf(need.get("limits")).contains("every archived object"),
                        "a hash-tree renewal did not say it reads everything: " + need);
            }
        }
    }

    @Test
    @DisplayName("AC4: DEPRECATED already counts as due — waiting for UNSOUND is too late")
    void deprecationIsAlreadyDue() {
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("SHA-256", LocalDate.of(2026, 1, 1),
                LocalDate.of(2040, 1, 1), "test"));
        LongTermValidityService service = new LongTermValidityService();
        service.setRegistry(registry);

        // Acting only at UNSOUND means acting after the break, and a token applied then proves
        // the value existed at the REAPPLICATION, not when the record was made.
        assertEquals(2, service.assess("bedroom", TODAY).get("hashTreeRenewalsDue"),
                "a deprecated algorithm was reported as needing nothing yet");
    }

    @Test
    @DisplayName("AC4 control: sound algorithms produce no work")
    void soundAlgorithmsAreNotBusywork() {
        LongTermValidityService service = new LongTermValidityService();

        Map<String, Object> body = service.assess("bedroom", TODAY);

        assertEquals(0, body.get("hashTreeRenewalsDue"),
                "work was reported against the default table, so the tests above prove nothing");
        assertEquals(0, body.get("timestampRenewalsDue"));
    }

    // ---- AC 5 / AC 6: what the report must keep saying ----

    @Test
    @DisplayName("AC5: the assessment says the table is not our warranty")
    void theTableIsNotAWarranty() {
        Map<String, Object> body = new LongTermValidityService().assess("bedroom", TODAY);
        String note = String.valueOf(body.get("declarationIsNotAWarranty")).toLowerCase();

        assertTrue(note.contains("not an assurance"),
                "the assessment presents the bundled dates as though we vouched for them: "
                        + note);
        assertTrue(note.contains("rfc 4998"),
                "the note does not say where the obligation to watch comes from: " + note);
    }

    @Test
    @DisplayName("AC6: renewal does not read as recovering the original time")
    void renewalDoesNotReachBackwards() {
        RenewalNeed timestamp = new RenewalNeed(RenewalNeed.Kind.TIMESTAMP_RENEWAL, "token",
                "SHA-1", Soundness.UNSOUND, "test");
        RenewalNeed tree = new RenewalNeed(RenewalNeed.Kind.HASH_TREE_RENEWAL, "tree", "SHA-1",
                Soundness.UNSOUND, "test");

        for (RenewalNeed need : List.of(timestamp, tree)) {
            String limits = need.limits().toLowerCase();
            assertTrue(limits.contains("not when the record was made")
                            || limits.contains("does not recover the original time"),
                    need.kind() + " does not say that renewing after a break re-dates the "
                            + "evidence: " + need.limits());
        }
    }

    @Test
    @DisplayName("an UNDETERMINED need says it is a gap in the table, not a finding")
    void undeterminedIsNotAFinding() {
        RenewalNeed need = new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "x", "BLAKE3",
                Soundness.UNKNOWN, "test");

        assertTrue(need.limits().contains("gap in the table"), need.limits());
        assertTrue(need.limits().contains("NOT a finding that it is sound"), need.limits());
    }

    @Test
    @DisplayName("a real CONFIRMED token IS assessed — through the production enumeration")
    void aConfirmedTokenIsAssessed() {
        // The path this pins was dead twice over: the service asked pending() for confirmed
        // receipts (a loop whose body could never run) and then looked for an attribute name
        // no rung writes. timestampRenewalsDue was structurally always 0, and the test that
        // asserted 0 was supported by an unwired store rather than by the code being right.
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("SHA-1", LocalDate.of(2011, 1, 1),
                LocalDate.of(2030, 1, 1), "test"));
        // The ledger and the content digests rest on SHA-256; declare it so the assertion
        // below is about the TOKEN and not about an incomplete fixture.
        registry.declare(new Declaration("SHA-256", null, null, "test"));
        LongTermValidityService service = new LongTermValidityService();
        service.setRegistry(registry);
        service.setReceiptStore(storeHolding(jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts
                .confirmed(jp.aegif.nemaki.rest.purview.anchor.AnchorKind.RFC3161_TSA,
                        "abc", java.time.Instant.parse("2026-08-24T00:00:00Z"),
                        new byte[] { 1 }, "p",
                        Map.of("digestAlgorithm", "SHA-1"))));

        Map<String, Object> body = service.assess("bedroom", LocalDate.of(2031, 1, 1));

        // A failing IMPRINT is a HASH_TREE renewal — the value has to be re-hashed, which
        // needs the archived data. Only a failing SIGNATURE would be a timestamp renewal, and
        // no rung records the signature algorithm, which is why that count stays 0 and the
        // response says so rather than leaving the zero to be misread.
        // 1, not 3: the fixture declares SHA-256 sound, so the ledger and the content
        // digests are NONE and only the SHA-1 imprint is due.
        assertEquals(1, body.get("hashTreeRenewalsDue"),
                "a confirmed token whose imprint algorithm is retired was not reported as "
                        + "needing renewal; nothing in the deployment would ever notice");
        assertEquals(0, body.get("timestampRenewalsDue"));
        assertTrue(String.valueOf(body.get("timestampRenewalsNote")).contains("structurally 0"),
                "a zero timestamp-renewal count was left to be read as 'nothing is due'");
        assertEquals(0, body.get("undetermined"),
                "the token's algorithm was not recognised: " + body.get("needs"));
    }

    @Test
    @DisplayName("a CONFIRMED token on a sound algorithm needs nothing — the control")
    void aSoundTokenIsNotBusywork() {
        // Without this, reporting every confirmed receipt as due would pass the test above.
        LongTermValidityService service = new LongTermValidityService();
        service.setReceiptStore(storeHolding(jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts
                .confirmed(jp.aegif.nemaki.rest.purview.anchor.AnchorKind.RFC3161_TSA,
                        "abc", java.time.Instant.parse("2026-08-24T00:00:00Z"),
                        new byte[] { 1 }, "p",
                        Map.of("digestAlgorithm", "SHA-256"))));

        assertEquals(0, service.assess("bedroom", TODAY).get("hashTreeRenewalsDue"),
                "a sound imprint produced work");
    }

    @Test
    @DisplayName("the attribute this service reads is one the RFC 3161 rung actually writes")
    void theAttributeNameMatchesTheProducer() throws Exception {
        // The fixtures above deliberately use the LITERAL "digestAlgorithm", not the constant,
        // so renaming the constant cannot make them agree with themselves. This pins the other
        // half: that the literal is what the producing rung really records. The first version
        // looked for "signatureAlgorithm", which nothing in the product writes.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/jp/aegif/nemaki/rest/purview/anchor/Rfc3161AnchorTarget.java"));

        assertTrue(source.contains("attrs.put(\"" + LongTermValidityService
                        .IMPRINT_ALGORITHM_ATTRIBUTE + "\""),
                "LongTermValidityService reads '" + LongTermValidityService
                        .IMPRINT_ALGORITHM_ATTRIBUTE + "' but Rfc3161AnchorTarget never writes it; "
                        + "every confirmed token would be assessed as UNKNOWN");
    }

    /** A store that answers confirmed() with one receipt and pending() with nothing. */
    private static jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore storeHolding(
            jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt receipt) {
        return new jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore() {
            @Override
            public SaveOutcome save(String domain, long toSequence,
                    jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt r) {
                return SaveOutcome.STORED;
            }

            @Override
            public List<jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt> forCheckpoint(
                    String domain, long toSequence) {
                return List.of(receipt);
            }

            @Override
            public List<PendingReceipt> pending(String domain, int limit) {
                // Deliberately empty: if the service goes back to asking this query for
                // confirmed receipts, the test above fails rather than quietly passing.
                return List.of();
            }

            @Override
            public List<PendingReceipt> confirmed(String domain, int limit) {
                return List.of(new PendingReceipt(domain, 5, receipt));
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("an unwired receipt store is UNDETERMINED, not 'no anchors need renewal'")
    void anUnwiredStoreDoesNotReportZero() {
        Map<String, Object> body = new LongTermValidityService().assess("bedroom", TODAY);

        assertEquals(1, body.get("undetermined"),
                "the anchors were silently reported as needing nothing; an unreadable source "
                        + "is not an empty one");
    }
}
