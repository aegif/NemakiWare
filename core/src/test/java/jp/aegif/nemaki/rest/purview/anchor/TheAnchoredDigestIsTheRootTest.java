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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a timestamp is over: the Merkle root's BYTES, not a hash of the text of the root.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>The root is already a SHA-256 digest. The product timestamps it by decoding the 64 hex
 * characters into 32 bytes and sending those ({@code Rfc3161AnchorTarget.decodeSha256Hex}), so
 * {@code hex(messageImprint) == merkleRoot}, which is what the specification says in §11 and
 * §12. Both verifier checks — P2's {@code anchor commits root} and P3's {@code token imprint} —
 * instead hashed the hex STRING a second time, and would therefore have refused every token
 * this product has ever produced. They were green because their fixtures built tokens the same
 * wrong way: the writer's side was never consulted (third review, 2026-09-22).
 *
 * <p>So this reads BOTH sides against the specification, from the module that writes. A fixture
 * cannot satisfy it by agreeing with the code under test, because what it compares is the
 * product's own encoder with the specification's sentence, and then the verifier's source with
 * the operation that was wrong.
 */
class TheAnchoredDigestIsTheRootTest {

    /** A Merkle root as the ledger writes one. */
    private static final String ROOT =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static final Path SPEC = Path.of("../docs/design/evidence-profile-v1.md");
    private static final Path VERIFIER =
            Path.of("../evidence-verifier-core/src/main/java/jp/aegif/nemaki/verifier");

    private static String read(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the product timestamps the root's bytes, so hex(imprint) is the root itself")
    void theProductTimestampsTheRootsBytes() {
        byte[] imprint = Rfc3161AnchorTarget.decodeSha256Hex(ROOT);

        assertEquals(32, imprint.length,
                "the imprint the product sends is " + imprint.length + " bytes; a SHA-256 "
                        + "message imprint is 32");
        assertEquals(ROOT, HexFormat.of().formatHex(imprint),
                "the bytes the product timestamps do not hex back to the root it was asked to "
                        + "anchor, so no verifier can compare a token with a checkpoint");
        // The operation the verifier must NOT perform, computed so the difference is a
        // measured fact rather than an assumption.
        assertFalse(doubleHashed().equals(ROOT),
                "fixture check: hashing the hex string a second time must give a different "
                        + "value, or this test cannot tell the two readings apart");
    }

    private static String doubleHashed() {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ROOT.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    @Test
    @DisplayName("the specification says the imprint IS the root, in both profiles that read it")
    void theSpecificationSaysTheImprintIsTheRoot() throws IOException {
        String spec = read(SPEC);
        assertTrue(spec.contains("`messageImprint` が anchor target の `merkleRoot` に一致"),
                "§12's TOKEN_IMPRINT no longer says the imprint IS the root. That sentence is "
                        + "what the product's encoder and the verifier's comparison both have "
                        + "to agree with");
        assertTrue(spec.contains("hex(messageImprint) == chain の末尾 link の `merkleRoot`")
                        || spec.contains("`hex(messageImprint) == chain の末尾 link の `merkleRoot`"),
                "§11's ANCHOR_COMMITS_ROOT no longer states the comparison, so P2 and P3 can "
                        + "drift apart again");
        assertFalse(spec.contains("SHA-256(UTF-8(anchor-target-checkpoint.merkleRoot))"),
                "the specification carries the double-hash reading again. It was written into "
                        + "§11 by copying the implementation's mistake, which is how a "
                        + "specification stops being the thing that decides");
    }

    /**
     * Neither verifier check hashes the root again. Source-read because the behaviour is in
     * another module: what this holds is that the operation that was wrong does not return.
     */
    @Test
    @DisplayName("neither P2 nor P3 hashes the Merkle root a second time")
    void neitherCheckHashesTheRootAgain() throws IOException {
        for (String className : List.of("AnchoredCheckpoint", "TrustedRfc3161")) {
            String source = read(VERIFIER.resolve(className + ".java"))
                    .replaceAll("(?m)//.*$", "")
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            assertFalse(source.contains("sha256(merkleRoot"),
                    className + " hashes the Merkle root again before comparing it with a "
                            + "token's imprint. The root is already a digest and the product "
                            + "timestamps its bytes, so this refuses every real token — and a "
                            + "fixture that builds tokens the same way will not notice");
        }
    }
}
