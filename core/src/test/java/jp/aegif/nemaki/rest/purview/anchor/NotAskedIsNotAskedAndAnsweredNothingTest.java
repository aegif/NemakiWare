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
package jp.aegif.nemaki.rest.purview.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revocation material at issuance, and the two ways of being "never" (plan §11).
 *
 * <p>The product has always written {@code revocationDataCapturedAt=never}, which was true and
 * said nothing about WHY. With collection wired there are two whys — nobody asked, and the
 * answer did not come — and an operator who turned collection on needs to tell them apart: the
 * second is a problem to fix and the first is a setting.
 */
class NotAskedIsNotAskedAndAnsweredNothingTest {

    @Test
    @DisplayName("collection off says NOT_ATTEMPTED, and still says 'never' to old readers")
    void collectionOffIsNotAttempted() {
        Map<String, String> attributes =
                RevocationMaterial.notAttempted("collection is off").asAttributes();

        assertEquals("NOT_ATTEMPTED", attributes.get("revocationStatus"));
        assertEquals("never", attributes.get("revocationDataCapturedAt"),
                "the field keeps the name and the meaning it always had, so nothing downstream "
                        + "changes meaning when this lands");
        assertNull(attributes.get("revocationDigest"));
    }

    @Test
    @DisplayName("asked and no answer is UNAVAILABLE — a different fact from not asking")
    void askedAndUnansweredIsNotTheSameAsNotAsking() {
        Map<String, String> attributes = RevocationMaterial
                .unavailable("http://crl.example/ca.crl", "the endpoint answered 503")
                .asAttributes();

        assertEquals("UNAVAILABLE", attributes.get("revocationStatus"),
                "an operator who turned collection ON and sees this has something to fix; "
                        + "reporting it as NOT_ATTEMPTED would hide a broken endpoint behind a "
                        + "setting they did not choose");
        assertEquals("never", attributes.get("revocationDataCapturedAt"),
                "nothing was captured either way — the DIFFERENCE is in the status, not in a "
                        + "timestamp that would have to be invented");
        assertEquals("http://crl.example/ca.crl", attributes.get("revocationSource"));
    }

    @Test
    @DisplayName("captured material records WHEN, because that is the whole claim")
    void capturedMaterialRecordsWhen() {
        Instant when = Instant.parse("2026-09-20T00:00:00Z");
        Map<String, String> attributes = RevocationMaterial
                .captured(new byte[] { 1, 2, 3 }, "abc", when, "http://crl.example/ca.crl")
                .asAttributes();

        assertEquals("CAPTURED", attributes.get("revocationStatus"));
        assertEquals(when.toString(), attributes.get("revocationDataCapturedAt"),
                "a CRL fetched today says whether the certificate is revoked today. Whether it "
                        + "was valid when the token was made can only be answered by having "
                        + "kept the answer from then, so WHEN is the claim");
        assertEquals("abc", attributes.get("revocationDigest"));
    }

    @Test
    @DisplayName("material recorded as captured with no bytes is refused outright")
    void capturedWithNoBytesIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RevocationMaterial(RevocationMaterial.Status.CAPTURED, new byte[0],
                        "d", Instant.EPOCH, "s", null),
                "an absence dressed as a presence; by the time it is in a receipt nothing can "
                        + "tell them apart");
        assertThrows(IllegalArgumentException.class,
                () -> new RevocationMaterial(RevocationMaterial.Status.CAPTURED,
                        new byte[] { 1 }, "d", null, "s", null),
                "captured material must say when it was retrieved, or it cannot be told apart "
                        + "from something fetched later");
        assertThrows(IllegalArgumentException.class,
                () -> new RevocationMaterial(RevocationMaterial.Status.UNAVAILABLE,
                        new byte[] { 1 }, null, null, "s", null),
                "bytes on material that was not captured would be taken by a reader as the "
                        + "answer");
    }

    @Test
    @DisplayName("captured bytes travel in the receipt and come back out")
    void capturedBytesTravelInTheReceipt() {
        byte[] crl = { 0x30, 0x05, 0x01, 0x02 };
        Map<String, String> attributes = RevocationMaterial
                .captured(crl, "abc", Instant.EPOCH, "http://crl.example/ca.crl")
                .asAttributes();

        assertTrue(attributes.containsKey(RevocationMaterial.MATERIAL_KEY),
                "material collected at issuance and left nowhere is material nobody outside "
                        + "this deployment can ever use");
        assertArrayEquals(crl, RevocationMaterial.materialIn(attributes));
    }

    @Test
    @DisplayName("material that cannot be decoded is a refusal, not material this node does not have")
    void undecodableMaterialIsARefusalNotAnAbsence() {
        // It answered null, and null is "no material was captured" — so a receipt holding
        // material this version cannot read shipped as a package saying the material was never
        // there (9-6 review, P3). The assembler turns the refusal into "the evidence could not
        // be read"; nothing half-decoded reaches the package either way.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> RevocationMaterial.materialIn(Map.of(RevocationMaterial.MATERIAL_KEY, "not base64 !!!")));
        assertNull(RevocationMaterial.materialIn(Map.of(RevocationMaterial.MATERIAL_KEY, "")),
                "an empty value is no material");
        assertNull(RevocationMaterial.materialIn(null));
    }

    @Test
    @DisplayName("collection is OFF by default, and off does not pretend")
    void collectionIsOffByDefault() {
        Rfc3161AnchorTarget target =
                new Rfc3161AnchorTarget("http://tsa.example", "NONE", "SHA-256");
        // No token needed: with collection off the method answers before looking at one, which
        // is itself the property being measured — a node that has not been configured to reach
        // a CRL endpoint does not reach one.
        RevocationMaterial material = target.collectRevocationMaterial(null);

        assertEquals(RevocationMaterial.Status.NOT_ATTEMPTED, material.status(),
                "turning collection on makes anchoring reach a CRL endpoint, which is a "
                        + "decision about what this node talks to");
        assertTrue(material.detail().contains("nothing is known"), material.detail());
    }
}
