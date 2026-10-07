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

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The evidence record this product really writes, as bytes.
 *
 * <h2>Why it is a file and not a builder</h2>
 *
 * <p>Every fixture in this module is written from the verifier's own reading, so it agrees with
 * the verifier whether or not the verifier is right. For the evidence record that went wrong in
 * the worst way: the fixtures were two-element sequences with the wanted digest sitting at the
 * top level, and the check scanned the DER for any 32-byte {@code OCTET STRING}. Both agreed.
 * Neither resembled a real record, which has three elements, no reduced hash tree at all, and
 * keeps the root inside the timestamp token's {@code TSTInfo} — so the check would have
 * answered {@code FAILED} on every package this product ships (both reviews, fifth round).
 *
 * <p>These bytes come from {@code core}'s {@code ErsGoldenWriter}, which builds the record with
 * {@code ErsRecord} itself. The modules cannot see each other, so the file is the seam.
 * {@code core}'s {@code TheGoldenEvidenceRecordIsStillWhatWeWriteTest} reads the same file with
 * the product's own reader and fails if the writer has drifted away from it.
 */
final class GoldenErs {

    private GoldenErs() {
    }

    /** The record, as this product wrote it. */
    static byte[] der() {
        return resource("/golden/product-ers.der");
    }

    /** The Merkle root that record covers — what the package must state for it to verify. */
    static String root() {
        return new String(resource("/golden/product-ers-root.txt"), StandardCharsets.UTF_8).trim();
    }

    /** An anchor target document stating {@code merkleRoot}. */
    static byte[] anchorTarget(String merkleRoot) {
        return ("{\"domain\":\"record-content\",\"merkleRoot\":\"" + merkleRoot + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The golden record with one top-level element replaced.
     *
     * <p>Used to build the records this product does NOT write — a declared algorithm it cannot
     * compute, a version it does not know — out of real bytes rather than out of a shape someone
     * imagined. Everything not named stays exactly as the writer produced it.
     */
    static byte[] withElement(int index, ASN1Encodable replacement) {
        try {
            ASN1Sequence record = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der()));
            ASN1EncodableVector edited = new ASN1EncodableVector();
            for (int i = 0; i < record.size(); i++) {
                edited.add(i == index ? replacement : record.getObjectAt(i));
            }
            return new DERSequence(edited).getEncoded();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The golden record's {@code archiveTimeStampSequence}, for building chained variants. */
    static ASN1Sequence timestampSequence() {
        try {
            ASN1Sequence record = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der()));
            return ASN1Sequence.getInstance(record.getObjectAt(record.size() - 1));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = GoldenErs.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the test classpath. It is "
                        + "produced by core's ErsGoldenWriter; see that class for the command");
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
