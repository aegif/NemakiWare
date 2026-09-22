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
package jp.aegif.nemaki.verifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packages this product really writes, read by this verifier.
 *
 * <h2>The seam this closes</h2>
 *
 * <p>{@code evidence-verifier-core} may not depend on {@code core}, so for months nothing ran
 * the product's own output through the product's own reader. The gap was not theoretical: the
 * same defect — <b>this verifier refusing packages this product writes</b> — shipped into the
 * branch TWICE in three days, and both times a reviewer found it by rebuilding the layout by
 * hand rather than a lock finding it.
 *
 * <ul>
 *   <li>The METS lookup dropped {@code representations/<id>/METS.xml}, which in CSIP is the one
 *       that names the payload, so every package answered "carries payload the METS does not
 *       name" (canon R80(a)).</li>
 *   <li>commons-ip2 percent-encodes the href and writes the zip entry raw, so a payload named
 *       {@code 契約書 v2.txt} was named one way and stored another (canon R81(a)).</li>
 * </ul>
 *
 * <p>The bytes are written by {@code core}'s {@code SipGoldenWriter} and checked in. The twin
 * of {@code AStandardReaderAcceptsOurEvidenceRecordTest}, for the same reason.
 *
 * <p><b>Two packages, and the second is the one that matters.</b> An ASCII payload name is the
 * example that discriminates nothing: {@code encodeHref("minutes.txt")} is
 * {@code minutes.txt}, so it answers the same whether the resolver decodes or not.
 *
 * <p><b>Three packages, and the third carries the v1 SECTION.</b> The first two are legacy
 * §4.1 layout, so {@code v1 layout} and {@code one evidence section} passed through their
 * "there is no section, so §4.2 has nothing to constrain" arm (subagent, fourteenth review,
 * P2, measured). The third makes {@code one evidence section}, {@code payload fixity} and the
 * composite verdict run over a package with a real §4.2 section.
 *
 * <p><b>What this still cannot do</b>, stated because it was measured rather than assumed:
 * gutting {@code v1Layout} to always PASS leaves this test green, and correctly so — a seam
 * over the packages the product WRITES cannot catch a rule that always passes, because the
 * product does not write a package that rule should refuse. {@code TheV1LayoutIsCheckedTest}
 * is what measures the rule; this measures that the product's output survives it.
 *
 * <p><b>VERIFIED, not "not FAILED".</b> The first pair of goldens was built without the
 * report's {@code content} section, so their PREMIS recorded no digest and both answered
 * {@code INDETERMINATE} — every structural check passing over a package that could not reach
 * P0 either way. Measured with the CLI before it was noticed here.
 */
class TheProductsOwnPackageIsVerifiedTest {

    private static Path golden(String name) throws Exception {
        return Path.of(TheProductsOwnPackageIsVerifiedTest.class.getResource(
                "/golden/" + name).toURI());
    }

    private static Outcome.Check checkNamed(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a package this product writes is not refused by this product's verifier")
    void theProductsOwnPackagePassesTheStructuralChecks() throws Exception {
        for (String name : List.of("product-sip-ascii.zip", "product-sip-encoded-name.zip",
                "product-sip-v1-section.zip")) {
            List<Outcome.Check> checks =
                    PackageIntegrity.check(PackageReader.open(golden(name)).entries());

            assertEquals(Outcome.PASSED, checkNamed(checks, "mets closure").outcome(),
                    name + ": this verifier says the package this product writes does not close "
                            + "over its own files — " + checkNamed(checks, "mets closure")
                                    .detail());
            assertEquals(Outcome.PASSED, checkNamed(checks, "zip safe").outcome(), name);
            assertEquals(Outcome.PASSED, checkNamed(checks, "one evidence section").outcome(),
                    name + ": " + checkNamed(checks, "one evidence section").detail());
            assertEquals(Outcome.PASSED, checkNamed(checks, "v1 layout").outcome(),
                    name + ": " + checkNamed(checks, "v1 layout").detail());
            assertEquals(Outcome.PASSED, checkNamed(checks, "payload fixity").outcome(),
                    name + ": " + checkNamed(checks, "payload fixity").detail());
            // VERIFIED, not merely "not FAILED". A package whose PREMIS records no digest
            // answers INDETERMINATE and every structural check above still passes — which is
            // what the first pair of goldens did, so they measured the structure only.
            assertEquals(Outcome.Verdict.VERIFIED, Outcome.combine(checks, checks),
                    name + ": P0 does not verify a package this product wrote: " + checks);
        }
    }

    /**
     * The encoded-name package really does carry an encoded reference.
     *
     * <p>Stated before the answer above is trusted: if a later commons-ip2 stopped encoding,
     * the test above would pass for a reason that has nothing to do with the decoding it is
     * there to measure, and the seam would be silently open again.
     */
    /**
     * The v1 golden really carries a section, or the arm it was added for is still vacuous.
     */
    @Test
    @DisplayName("the v1 golden carries the section, or it measures nothing")
    void theV1GoldenCarriesTheSection() throws Exception {
        java.util.Map<String, byte[]> entries =
                PackageReader.open(golden("product-sip-v1-section.zip")).entries();

        assertTrue(entries.keySet().stream().anyMatch(n -> n.endsWith("/profile.json")),
                "the v1 golden carries no profile.json, so v1 layout is still answered by its "
                        + "'nothing to constrain' arm: " + entries.keySet());
        assertTrue(entries.keySet().stream()
                        .noneMatch(n -> n.endsWith("metadata/other/nemaki-evidence.json")),
                "the v1 golden carries the legacy file as well, which §4.2 makes FAILED: "
                        + entries.keySet());
    }

    @Test
    @DisplayName("the encoded-name golden really is encoded, or it measures nothing")
    void theEncodedGoldenIsActuallyEncoded() throws Exception {
        java.util.Map<String, byte[]> entries =
                PackageReader.open(golden("product-sip-encoded-name.zip")).entries();

        String payload = entries.keySet().stream()
                .filter(n -> n.contains("/data/") && !n.endsWith("/"))
                .findFirst().orElseThrow();
        assertTrue(payload.endsWith("契約書 v2.txt"),
                "the golden's payload is not stored under its raw name: " + payload);

        boolean encoded = false;
        for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith("METS.xml")) {
                continue;
            }
            String mets = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            if (mets.contains("%E5%A5%91") || mets.contains("+v2.txt")) {
                encoded = true;
            }
        }
        assertTrue(encoded,
                "no METS in the golden carries an encoded reference, so the package no longer "
                        + "exercises the decoding it was checked in for");
    }
}
