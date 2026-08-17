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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four digest kinds are different things, and this test is what keeps them different.
 *
 * <p>All four produce lowercase hex and two of them produce it at the same width, which is why
 * a future reader could plausibly "simplify" one into another. Each assertion below fails if
 * that happens.
 */
public class LineageDigestKindsTest {

    /** The one string every SHA-256 implementation agrees on. */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Nested
    @DisplayName("the primitive")
    class Primitive {

        @Test
        @DisplayName("is SHA-256 and nothing else")
        void matchesTheKnownVector() {
            assertEquals(ABC_SHA256, LineageDigests.sha256Hex("abc"));
        }

        @Test
        @DisplayName("hashes UTF-8 bytes, not UTF-16 ones")
        void hashesUtf8() throws Exception {
            String value = "日本語📄";
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            String expected = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(utf8));

            assertEquals(expected, LineageDigests.sha256Hex(value));
        }

        @Test
        @DisplayName("byte[] and String overloads agree")
        void overloadsAgree() {
            assertEquals(LineageDigests.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)),
                    LineageDigests.sha256Hex("abc"));
        }
    }

    @Nested
    @DisplayName("identity vs the primitive")
    class Identity {

        /**
         * If this ever passes, the typed encoding has been refactored away and every
         * {@code processKey} in the design's identity rules has silently changed.
         */
        @Test
        @DisplayName("a canonical hash is NOT a plain hash of the same string")
        void canonicalIsDomainSeparated() {
            assertNotEquals(LineageDigests.sha256Hex("abc"), LineageCanonicalHash.hash("abc"));
        }

        @Test
        @DisplayName("and the separation is what makes concatenation ambiguity impossible")
        void concatenationCannotCollide() {
            assertNotEquals(LineageCanonicalHash.hash("ab", "c"),
                    LineageCanonicalHash.hash("a", "bc"));
        }

        /**
         * The golden vectors live in {@code LineageCanonicalHashTest}; this only pins that the
         * final step is still SHA-256 over the encoder's bytes, which is the part routed through
         * {@link LineageDigests}.
         */
        @Test
        @DisplayName("but it is still SHA-256 at the last step")
        void stillSha256() {
            String hash = LineageCanonicalHash.hash("abc");
            assertEquals(64, hash.length());
            assertTrue(hash.chars().allMatch(
                    c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')));
        }
    }

    @Nested
    @DisplayName("evidence vs redaction")
    class EvidenceVsRedaction {

        @Test
        @DisplayName("evidence is the primitive, deliberately")
        void evidenceIsPlain() {
            assertEquals(LineageDigests.sha256Hex("abc"), LineageDigests.evidenceDigest("abc"));
            assertEquals(ABC_SHA256, EndpointAttribute.evidenceDigest("abc"));
        }

        @Test
        @DisplayName("redaction is a prefix of evidence — which is exactly why it is not evidence")
        void redactionIsAPrefix() {
            String redaction = LineageDigests.redactionDigest("abc");
            assertEquals(LineageDigests.REDACTION_HEX_CHARS, redaction.length());
            assertTrue(ABC_SHA256.startsWith(redaction));
            assertEquals(redaction, LineageEndpoint.shortDigest("abc"));
        }

        /**
         * The guard that stops a log-side value from being accepted as proof. It is width-based,
         * so it holds for any redaction digest, not just this one.
         */
        @Test
        @DisplayName("a redaction digest is refused where an evidence digest is required")
        void redactionIsNotAcceptedAsEvidence() {
            assertFalse(EndpointAttribute.isEvidenceDigest(LineageDigests.redactionDigest("abc")));
            assertTrue(EndpointAttribute.isEvidenceDigest(LineageDigests.evidenceDigest("abc")));
        }

        @Test
        @DisplayName("redaction still separates two different values")
        void redactionStillDistinguishes() {
            assertNotEquals(LineageDigests.redactionDigest("abc"),
                    LineageDigests.redactionDigest("abd"));
        }
    }

    @Nested
    @DisplayName("distribution")
    class Distribution {

        /**
         * The binary digest is domain-tagged before hashing, so it cannot be reproduced by
         * hashing the file list on its own — which is the property that stops a file whose
         * content happens to be a digest from impersonating one.
         */
        @Test
        @DisplayName("the domain tag changes the result")
        void domainTagMatters() {
            List<Map<String, String>> files = List.of(
                    Map.of("path", "WEB-INF/lib/a.jar", "sha256Hex", ABC_SHA256));

            assertNotEquals(LineageCanonicalHash.hash(files),
                    LineageCanonicalHash.hash("BARRIER_BINARY_V1", files));
        }
    }
}
