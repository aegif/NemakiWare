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
package jp.aegif.nemaki.rest.eark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller that asked for more evidence than exists is refused, not quietly given less.
 *
 * <p>The comparison itself is the subject. Getting it wrong in the lenient direction hands over
 * a package that supports less than was required, and the caller finds out from a verifier —
 * possibly in another organisation, possibly years later.
 */
class AssuranceIsCheckedBeforeAnythingIsWrittenTest {

    private static final List<String> NOTHING = List.of("PACKAGE_INTEGRITY_V1");
    private static final List<String> LEDGER = List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1");
    /** What a bundle with an RFC 3161 token and no OTS proof supports (EvidenceBundle). */
    private static final List<String> RFC3161 = List.of("PACKAGE_INTEGRITY_V1",
            "RECORD_LEDGER_V1", "ANCHORED_CHECKPOINT_V1", "TRUSTED_RFC3161_V1");
    private static final List<String> BOTH_ANCHORS = List.of("PACKAGE_INTEGRITY_V1",
            "RECORD_LEDGER_V1", "ANCHORED_CHECKPOINT_V1", "TRUSTED_RFC3161_V1",
            "ANCHORED_OTS_V1");

    @Test
    @DisplayName("BEST_AVAILABLE accepts whatever the record has, including nothing")
    void bestAvailableAcceptsAnything() {
        assertTrue(EarkSipExporter.Assurance.BEST_AVAILABLE.satisfiedBy(NOTHING));
        assertTrue(EarkSipExporter.Assurance.BEST_AVAILABLE.satisfiedBy(null),
                "the existing behaviour is to build from whatever exists and state what that "
                        + "supports; a record with no evidence at all still exports");
    }

    @Test
    @DisplayName("a stronger requirement is not met by a weaker package")
    void aWeakerPackageDoesNotSatisfyAStrongerRequirement() {
        assertFalse(EarkSipExporter.Assurance.REQUIRE_ANCHORED_CHECKPOINT.satisfiedBy(LEDGER),
                "a ledger entry with no anchor is not an anchored checkpoint, and handing over "
                        + "a package that supports one when the other was required is the "
                        + "substitution this whole layer exists to refuse");
        assertFalse(EarkSipExporter.Assurance.REQUIRE_TRUSTED_RFC3161.satisfiedBy(LEDGER));
    }

    @Test
    @DisplayName("a package that supports more also satisfies a weaker requirement")
    void aStrongerPackageSatisfiesAWeakerRequirement() {
        assertTrue(EarkSipExporter.Assurance.REQUIRE_RECORD_LEDGER.satisfiedBy(RFC3161),
                "asking for a ledger and getting one that is also timestamped is not a failure");
        assertTrue(EarkSipExporter.Assurance.REQUIRE_TRUSTED_RFC3161.satisfiedBy(RFC3161));
        assertTrue(EarkSipExporter.Assurance.REQUIRE_ANCHORED_OTS.satisfiedBy(BOTH_ANCHORS));
    }

    @Test
    @DisplayName("the profiles are a lattice, not a ladder: an RFC 3161 token does not satisfy a request for an OTS proof")
    void aSiblingProfileDoesNotSatisfyTheOther() {
        // P3 (a trusted RFC 3161 path) and P4 (an OpenTimestamps proof) both sit over P2 and
        // neither contains the other. Ranked in one list, with P3 above P4, a record holding
        // only an RFC 3161 token satisfied REQUIRE_ANCHORED_OTS and the caller was handed a
        // package with no OTS proof in it (9-6 review, P1).
        assertFalse(EarkSipExporter.Assurance.REQUIRE_ANCHORED_OTS.satisfiedBy(RFC3161),
                "the record has no OpenTimestamps proof; whatever else it has, this request was "
                        + "for that");
        assertFalse(EarkSipExporter.Assurance.REQUIRE_LONG_TERM_ERS.satisfiedBy(BOTH_ANCHORS),
                "P5 is an evidence record on top of P3 or P4, and this record has none");
        assertTrue(EarkSipExporter.Assurance.REQUIRE_LONG_TERM_ERS.satisfiedBy(
                List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1", "ANCHORED_CHECKPOINT_V1",
                        "TRUSTED_RFC3161_V1", "LONG_TERM_ERS_V1")));
    }

    @Test
    @DisplayName("a profile this version does not know does not satisfy anything")
    void anUnknownProfileSatisfiesNothing() {
        assertFalse(EarkSipExporter.Assurance.REQUIRE_PACKAGE_INTEGRITY
                        .satisfiedBy(List.of("SOMETHING_FROM_A_LATER_VERSION")),
                "an unknown name must not pass by being absent from the table. A package built "
                        + "by a newer node may well be stronger — and this version cannot tell, "
                        + "which is a reason to refuse rather than to assume");
        assertFalse(EarkSipExporter.Assurance.REQUIRE_PACKAGE_INTEGRITY.satisfiedBy(null));
        assertFalse(EarkSipExporter.Assurance.REQUIRE_PACKAGE_INTEGRITY.satisfiedBy(List.of()));
    }

    @Test
    @DisplayName("the refusal names what the record DOES support, so a caller can ask for that")
    void theRefusalSaysWhatIsAvailable() {
        EarkSipExporter.AssuranceNotMetException refusal =
                new EarkSipExporter.AssuranceNotMetException(
                        EarkSipExporter.Assurance.REQUIRE_TRUSTED_RFC3161, LEDGER);
        assertTrue(refusal.getMessage().contains("RECORD_LEDGER_V1"));
        assertEquals(LEDGER, refusal.supported());
        assertTrue(refusal.getMessage().contains("no package was built"),
                "a refusal that did not say so would leave a caller wondering whether a partial "
                        + "package is sitting somewhere");
        assertTrue("ASSURANCE_NOT_MET".equals(refusal.reasonCode()),
                "a client branches on the code, not on the sentence");
    }
}
