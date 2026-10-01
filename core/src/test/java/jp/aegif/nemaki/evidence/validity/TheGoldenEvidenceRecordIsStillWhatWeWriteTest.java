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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checked-in evidence record is still the shape this product writes.
 *
 * <h2>The seam this guards</h2>
 *
 * <p>{@code evidence-verifier-core} runs its real {@code LONG_TERM_ERS_V1} checks over
 * {@code evidence-verifier-core/src/test/resources/golden/product-ers.der}, because the two
 * modules cannot see each other and a fixture built on the verifier's side agrees with the
 * verifier whether or not the verifier is right. That is not a hypothetical: the previous
 * fixtures were two-element sequences with the digest at the top level, and the check scanned
 * for any 32-byte {@code OCTET STRING}. Both were wrong together, and the verifier would have
 * refused every real package (both reviews, fifth round).
 *
 * <p>A checked-in artefact has the opposite failure: it stops resembling the writer and nobody
 * notices. So this end reads the same bytes with {@link ErsRecord} — the writer's own reader —
 * and compares them against a record built NOW. The record is not reproducible byte for byte
 * (fresh key, fresh genTime), so what is compared is the structure and the covered value.
 */
class TheGoldenEvidenceRecordIsStillWhatWeWriteTest {

    private static final Path GOLDEN =
            Path.of("../evidence-verifier-core/src/test/resources/golden/product-ers.der");
    private static final Path ROOT =
            Path.of("../evidence-verifier-core/src/test/resources/golden/product-ers-root.txt");

    private static byte[] goldenDer() throws Exception {
        assertTrue(Files.exists(GOLDEN), GOLDEN + " is not there. It is produced by "
                + "ErsGoldenWriter; that class's javadoc carries the command");
        return Files.readAllBytes(GOLDEN);
    }

    /** Which implicit tags the first Archive Timestamp actually carries, in order. */
    private static java.util.List<Integer> taggedFields(byte[] der) throws Exception {
        org.bouncycastle.asn1.ASN1Sequence record = org.bouncycastle.asn1.ASN1Sequence
                .getInstance(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(der));
        org.bouncycastle.asn1.ASN1Sequence sequence = org.bouncycastle.asn1.ASN1Sequence
                .getInstance(record.getObjectAt(record.size() - 1));
        org.bouncycastle.asn1.ASN1Sequence chain =
                org.bouncycastle.asn1.ASN1Sequence.getInstance(sequence.getObjectAt(0));
        org.bouncycastle.asn1.ASN1Sequence ats =
                org.bouncycastle.asn1.ASN1Sequence.getInstance(chain.getObjectAt(0));
        java.util.List<Integer> tags = new java.util.ArrayList<>();
        for (int i = 0; i < ats.size(); i++) {
            if (ats.getObjectAt(i) instanceof org.bouncycastle.asn1.ASN1TaggedObject tagged) {
                tags.add(tagged.getTagNo());
            }
        }
        return tags;
    }

    private static String goldenRoot() throws Exception {
        return Files.readString(ROOT, StandardCharsets.UTF_8).trim();
    }

    @Test
    @DisplayName("this product's own reader reads the golden record")
    void ourReaderReadsIt() throws Exception {
        ErsRecord read = ErsRecord.parse(goldenDer());

        assertEquals(1, read.chains().size(),
                "the golden record no longer holds one chain, so the fixture the verifier runs "
                        + "on is not the shape this product writes");
        assertEquals(1, read.chains().get(0).size(),
                "the golden record no longer holds exactly one Archive Timestamp");
        assertTrue(read.chains().get(0).get(0).hashTree().isEmpty(),
                "the golden record has grown a reduced hash tree. This product writes none — "
                        + "RFC 4998 §4.2 allows a timestamp with no hash value lists, and that "
                        + "is what lets the existing RFC 3161 anchor BE the first Archive "
                        + "Timestamp. The verifier's degenerate-form branch is no longer the "
                        + "one a real record takes");
    }

    /**
     * The golden covers the root its companion file names.
     *
     * <p>Read out of the token rather than trusted: the file beside it is what the verifier
     * compares against, and a pair that disagreed would make the verifier's pass meaningless.
     */
    @Test
    @DisplayName("the golden record's timestamp covers exactly the root named beside it")
    void theTokenCoversTheStatedRoot() throws Exception {
        ErsRecord read = ErsRecord.parse(goldenDer());
        org.bouncycastle.tsp.TimeStampToken token = new org.bouncycastle.tsp.TimeStampToken(
                new org.bouncycastle.cms.CMSSignedData(
                        read.chains().get(0).get(0).timeStampDer()));

        assertArrayEquals(HexFormat.of().parseHex(goldenRoot()),
                token.getTimeStampInfo().getMessageImprintDigest(),
                "the checked-in record does not cover the root checked in beside it, so the "
                        + "verifier's PASS over this pair establishes nothing");
        assertEquals(ErsRecord.SHA256_OID,
                token.getTimeStampInfo().getMessageImprintAlgOID().getId());
    }

    /**
     * A record built now has the same shape, and covers what it was asked to cover.
     *
     * <p>This is the half that goes red when the writer changes: the golden stays put, and a
     * fresh record stops matching it.
     */
    @Test
    @DisplayName("a record built now has the golden's shape, and its imprint is the root itself")
    void afreshRecordMatchesTheGoldensShape() throws Exception {
        byte[] h = HexFormat.of().parseHex(goldenRoot());
        ErsRecord golden = ErsRecord.parse(goldenDer());
        ErsRecord fresh = ErsRecord.parse(ErsGoldenWriter.recordOver(h));

        assertEquals(golden.chains().size(), fresh.chains().size(),
                "a record built now holds a different number of chains from the checked-in one");
        assertEquals(golden.chains().get(0).size(), fresh.chains().get(0).size(),
                "a record built now holds a different number of Archive Timestamps");
        assertEquals(golden.chains().get(0).get(0).hashTree().isEmpty(),
                fresh.chains().get(0).get(0).hashTree().isEmpty(),
                "a record built now disagrees with the checked-in one about whether there is a "
                        + "reduced hash tree");
        assertEquals(golden.chains().get(0).get(0).digestAlgorithmOid(),
                fresh.chains().get(0).get(0).digestAlgorithmOid());

        // The DER's own shape, not the parsed view. ErsRecord.parse NORMALISES an absent
        // digestAlgorithm [0] to the token's algorithm, so a writer that stopped emitting the
        // field would leave both ends of this seam green while the checked-in bytes and the
        // fresh ones had different structures (subagent, sixth review, P3).
        assertEquals(taggedFields(goldenDer()), taggedFields(ErsGoldenWriter.recordOver(h)),
                "the Archive Timestamp's tagged fields changed shape between the checked-in "
                        + "record and one built now, and the parsed comparison above cannot "
                        + "see it");

        // And the rule the whole correction turns on: the first token's imprint IS the data
        // object hash, unchanged. Not hashed again (R70, and the imprint double-hash before it).
        assertArrayEquals(h, ErsRecord.imprintForFirst(h),
                "the first Archive Timestamp's imprint is no longer the data object hash "
                        + "itself, so a record this product writes no longer covers the Merkle "
                        + "root the anchor covered");
    }
}
