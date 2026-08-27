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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming a standard is not implementing it.
 *
 * <h2>What is being defended</h2>
 *
 * <p>The specific hazard of a declared-but-unimplemented format: a response that says
 * "RFC 4998" reads, to anybody skimming, as a product that produces RFC 4998. The disclaimer is
 * the whole protection, so it is pinned to travel in the same map as the name — not merely to
 * exist somewhere in the class.
 */
class ErsFormatTest {

    @Test
    @DisplayName("a renewal that is due names the format it would be produced in")
    void aDueRenewalNamesItsFormat() {
        // The gap this closes: the monitor could say "a renewal is coming" and not what to
        // renew into, which is an alarm nobody can act on.
        RenewalNeed need = new RenewalNeed(RenewalNeed.Kind.TIMESTAMP_RENEWAL, "checkpoint-1",
                "SHA-1", AlgorithmRegistry.Soundness.UNSOUND, "SHA-1 is past its declared end date");

        assertEquals(ErsFormat.CHOSEN, need.targetFormat());
        assertEquals("RFC 4998", need.targetFormat().specification());
    }

    @Test
    @DisplayName("a renewal that is NOT due names no format — the control")
    void nothingDueNamesNoFormat() {
        // Without this, returning the format unconditionally would pass the test above while
        // telling an operator with nothing to do that there is a format they should be using.
        assertNull(new RenewalNeed(RenewalNeed.Kind.NONE, "checkpoint-1", "SHA-256",
                AlgorithmRegistry.Soundness.SOUND, "nothing due").targetFormat());
        assertNull(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "checkpoint-1", "SHA-3",
                null, "not in the table").targetFormat(),
                "an algorithm nobody has judged was given a renewal target, which reads as a "
                        + "finding that a renewal is needed");
    }

    @Test
    @DisplayName("the disclaimer travels in the same map as the name")
    void theDisclaimerTravelsWithTheName() {
        Map<String, Object> body = new RenewalNeed(RenewalNeed.Kind.HASH_TREE_RENEWAL,
                "checkpoint-1", "SHA-1", AlgorithmRegistry.Soundness.UNSOUND, "broken").asMap();

        assertEquals("RFC 4998", body.get("renewalFormat"));
        assertNotNull(body.get("renewalFormatLimits"),
                "the format is named with nothing beside it bounding what naming it means, so a "
                        + "reader takes the name for full conformance: " + body);
        // The disclaimer used to be "this product does NOT generate evidence records". It does
        // now, so that sentence would be false — and a false disclaimer is worse than none,
        // because it is the sentence a careful reader trusts. What has to be bounded is the
        // part that is still narrower than the name: WHAT the record is about, and what nobody
        // checked.
        String limits = String.valueOf(body.get("renewalFormatLimits"));
        assertTrue(limits.contains("checkpoint hash"),
                "the disclaimer does not say the record is about a checkpoint rather than a "
                        + "document, which is the assumption the name invites: " + limits);
        assertTrue(limits.contains("not a claim of conformance"), limits);
        assertTrue(limits.contains("signature and certificate are not verified"), limits);
        assertFalse(limits.contains("does NOT generate"),
                "the disclaimer still says this product generates no evidence records, which "
                        + "stopped being true: " + limits);
    }

    @Test
    @DisplayName("nothing due carries no format AND no disclaimer about one")
    void nothingDueCarriesNeither() {
        Map<String, Object> body = new RenewalNeed(RenewalNeed.Kind.NONE, "checkpoint-1",
                "SHA-256", AlgorithmRegistry.Soundness.SOUND, "nothing due").asMap();

        assertNull(body.get("renewalFormat"));
        assertNull(body.get("renewalFormatLimits"),
                "a response with nothing due carries a paragraph about evidence records, which "
                        + "is noise where an operator is looking for a verdict");
    }

    @Test
    @DisplayName("the rejected option is still written down")
    void theRejectedOptionIsRecorded() {
        // A decision whose rejected alternative is not recorded is indistinguishable from never
        // having considered it, and the next person re-opens the same question.
        assertEquals(2, ErsFormat.values().length,
                "the comparison lost an option; the decision is then unreviewable");
        assertEquals("RFC 6283", ErsFormat.RFC_6283_XML.specification());
        assertFalse(ErsFormat.CHOSEN == ErsFormat.RFC_6283_XML,
                "the chosen format changed without this test being updated");
    }

    @Test
    @DisplayName("the placement in a CSIP package is decided in one place, and it is not PREMIS's")
    void thePlacementIsDecidedOnce() {
        // Otherwise the exporter decides it again, differently, on the day it is implemented.
        //
        // This said metadata/preservation until 2026-08-27, with the reasoning "an evidence
        // record is preservation metadata" -- an OAIS category mapped onto a CSIP directory.
        //
        // The FOLDER was never the problem. What decides the folder is the commons-ip2 call, and
        // addPreservationMetadata declares the file in <amdSec><digiprovMD> -- the slot CSIP32
        // names for PREMIS. RODA 6.3.0 reads that slot into SIP.getPreservationMetadata() and
        // pushes every entry through PremisV3Utils.binaryToGenericPremis, so a DER there fails
        // the WHOLE ingest. Measured with controls; the same record via addOtherMetadata ingests
        // and survives. Note CSIP32's level is SHOULD, so this is a departure from its intent,
        // not a requirement violation -- see ErsFormat's javadoc.
        //
        // This constant only DESCRIBES the outcome. The assertion that guards the cause is in
        // ErsIsInTheSipAtItsDeclaredPlaceTest, over the parsed SIP's metadata lists.
        assertEquals("metadata/other", ErsFormat.CSIP_LOCATION,
                "the evidence record's declared place changed. If it went back to "
                        + "metadata/preservation, check the add*Metadata call too: declaring a "
                        + "DER blob in <digiprovMD> makes at least one receiver reject the "
                        + "ENTIRE package (docs/design/p3-4-custody-transfer.md §11)");
        // No assertNotEquals("metadata/preservation", ...) here: the assertEquals above already
        // implies it, so it could never fail on its own. An earlier version of this test added
        // one and the commit message called it extra discrimination -- it was not.
    }
}
