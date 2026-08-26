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

import org.bouncycastle.tsp.TimeStampToken;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks an RFC 4998 evidence record against the value it claims to cover (P2-3).
 *
 * <h2>What it checks, and the one thing it refuses to say</h2>
 *
 * <p>For each archive timestamp: the reduced hash tree's first node is hashed the way RFC 4998
 * §4.2 says — the sorted concatenation of its values, with no domain separation — and compared
 * with the message imprint inside the timestamp token. That is the link between the data and
 * the time.
 *
 * <p>It does <b>not</b> validate the timestamp authority's certificate, its chain, or its
 * revocation status, because this product holds no trust anchors for any TSA. A verifier that
 * does is the one that can complete the check; this one reports what it saw and says which part
 * it did not do. Reporting a token as "valid" on the strength of its internal consistency alone
 * would be exactly the substitution this layer exists to refuse — the signature is the part
 * that makes it evidence, and it is the part not checked here.
 *
 * <p>{@link ErsRecord#LIMITS} covers what the record itself does and does not establish; those
 * limits travel in every report this class produces.
 */
public final class ErsVerifier {

    private ErsVerifier() {
    }

    /** One archive timestamp's result. */
    public record TimestampResult(int chain, int position, boolean imprintMatches,
            String digestAlgorithmOid, String genTime, String detail) {}

    /**
     * Whether the record's own links hold, and what was not looked at.
     *
     * <p>{@code linksHold} is deliberately not called "valid". A record whose links hold and
     * whose TSA nobody vouched for is not a validated record.
     */
    public record Report(boolean linksHold, int timestampsChecked, List<TimestampResult> results,
            String limits, String notChecked) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            // The caveats FIRST. A reader skimming a verification result meets "the signature
            // was not checked" before the word that looks like a verdict.
            m.put("limits", limits);
            m.put("notChecked", notChecked);
            m.put("linksHold", linksHold);
            m.put("timestampsChecked", timestampsChecked);
            m.put("results", results.stream().map(r -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("chain", r.chain());
                one.put("position", r.position());
                one.put("imprintMatches", r.imprintMatches());
                one.put("digestAlgorithm", r.digestAlgorithmOid());
                one.put("genTime", r.genTime());
                one.put("detail", r.detail());
                return one;
            }).toList());
            return m;
        }
    }

    /** What this build does not do, said in the same breath as any result it produces. */
    public static final String NOT_CHECKED =
            "The timestamp authority's SIGNATURE, certificate chain and revocation status were "
                    + "NOT verified: this product holds no trust anchors for any TSA. So this "
                    + "result says the record's internal links hold, and says NOTHING about "
                    + "whether the times in it came from an authority anyone trusts. A verifier "
                    + "with the TSA's trust anchors is the one that can finish the check.";

    /**
     * Verifies every archive timestamp in the record.
     *
     * @param der the evidence record
     * @param expectedFirstValue the value the first timestamp should cover — the checkpoint
     *        hash. Pass {@code null} to check the internal links only; passing it is what ties
     *        the record to something the caller holds, and without it a record that is
     *        perfectly self-consistent about somebody else's data passes.
     */
    public static Report verify(byte[] der, byte[] expectedFirstValue) {
        List<TimestampResult> results = new ArrayList<>();
        ErsRecord record;
        try {
            record = ErsRecord.parse(der);
        } catch (Exception e) {
            return new Report(false, 0, List.of(), ErsRecord.LIMITS,
                    "The record could not be parsed (" + e.getMessage() + "), so nothing in it "
                            + "was checked. " + NOT_CHECKED);
        }
        boolean allHold = true;
        int checked = 0;
        for (int c = 0; c < record.chains().size(); c++) {
            List<ErsRecord.ArchiveTimeStamp> chain = record.chains().get(c);
            for (int i = 0; i < chain.size(); i++) {
                ErsRecord.ArchiveTimeStamp ats = chain.get(i);
                TimestampResult result = check(c, i, ats);
                results.add(result);
                checked++;
                allHold &= result.imprintMatches();
            }
        }
        if (expectedFirstValue != null) {
            boolean tied = !record.chains().isEmpty()
                    && !record.chains().get(0).isEmpty()
                    && record.chains().get(0).get(0).reducedHashtreeFirstNode().stream()
                            .anyMatch(v -> Arrays.equals(v, expectedFirstValue));
            if (!tied) {
                allHold = false;
                results.add(new TimestampResult(0, 0, false, null, null,
                        "the record does not cover the value it was checked against, so it is "
                                + "about something else — a record that is perfectly consistent "
                                + "about another party's data is not evidence about ours"));
            }
        }
        if (checked == 0) {
            return new Report(false, 0, results, ErsRecord.LIMITS,
                    "The record contains no archive timestamp, so there was nothing to check. "
                            + NOT_CHECKED);
        }
        return new Report(allHold, checked, results, ErsRecord.LIMITS, NOT_CHECKED);
    }

    private static TimestampResult check(int chain, int position,
            ErsRecord.ArchiveTimeStamp ats) {
        try {
            TimeStampToken token = new TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(
                            new ByteArrayInputStream(ats.timeStampDer())));
            byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
            String oid = token.getTimeStampInfo().getMessageImprintAlgOID().getId();
            byte[] computed = reduce(ats.reducedHashtreeFirstNode(), oid);
            boolean matches = computed != null && Arrays.equals(computed, imprint);
            return new TimestampResult(chain, position, matches, oid,
                    String.valueOf(token.getTimeStampInfo().getGenTime().toInstant()),
                    matches ? "the token's message imprint is the hash of what the record says "
                                    + "it covers"
                            : "the token's message imprint is NOT the hash of what the record "
                                    + "says it covers, so this timestamp is about a different "
                                    + "value");
        } catch (Exception e) {
            return new TimestampResult(chain, position, false, null, null,
                    "this timestamp could not be read (" + e.getMessage() + "), which is not a "
                            + "finding that it is wrong");
        }
    }

    /**
     * RFC 4998 §4.2: hash the SORTED concatenation of the node's values.
     *
     * <p>Sorted, and with no length or type prefix. This product's own Merkle tree is
     * RFC 6962-style and does prefix — the two are different algorithms, and using ours here
     * would produce records that only this product can read while looking standard.
     */
    private static byte[] reduce(List<byte[]> node, String digestOid) {
        if (node == null || node.isEmpty()) {
            return null;
        }
        String algorithm = switch (digestOid == null ? "" : digestOid) {
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            default -> null;
        };
        if (algorithm == null) {
            // Not a guess at SHA-256. A digest algorithm this build does not know is one whose
            // result it cannot compute, and computing the wrong one produces a mismatch that
            // reads as a broken record rather than an unsupported one.
            return null;
        }
        List<byte[]> sorted = new ArrayList<>(node);
        sorted.sort(Arrays::compareUnsigned);
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            for (byte[] value : sorted) {
                digest.update(value);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
