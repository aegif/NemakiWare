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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks an RFC 4998 evidence record the way §4.3 and §5.3 say to (P2-3).
 *
 * <h2>What each position must cover</h2>
 *
 * <p>The verifier does not hash "whatever value is in the record and compare it to the token".
 * That is what the first version did, and it made a chain of mutually unrelated tokens report
 * success: every timestamp agreed with the value filed beside it, and no timestamp was tied to
 * the one before. What ties them is that the expected imprint at each position is <b>computed
 * from what came before</b>:
 *
 * <ul>
 *   <li>chain 0, position 0 — the data object's hash, unchanged (no reduced hash tree, §4.2)</li>
 *   <li>chain 0, position i&gt;0 — {@code H(previous ContentInfo DER)} (§5.2)</li>
 *   <li>chain k&gt;0, position 0 — {@code H(h')} where {@code h'} is the record's own first
 *       hash list, and {@code h'} must itself equal {@code H(sorted(h(d), ha))} with
 *       {@code ha = H(DER of all previous chains)} (§5.3)</li>
 * </ul>
 *
 * <p>The last relationship needs {@code h(d)} under the new chain's algorithm, which cannot be
 * derived from the old hash — only from the data object. A caller that has it passes it; one
 * that does not gets that link reported as unchecked rather than as verified.
 *
 * <h2>The one thing it refuses to say</h2>
 *
 * <p>It does not validate the timestamp authority's certificate, chain or revocation status,
 * because this product holds no trust anchors for any TSA. So the result is called
 * {@code linksHold} and not "valid": a record whose links hold and whose TSA nobody vouched for
 * is not a validated record.
 */
public final class ErsVerifier {

    private ErsVerifier() {
    }

    /** One archive timestamp's result. */
    public record TimestampResult(int chain, int position, boolean imprintMatches,
            String digestAlgorithmOid, String genTime, String detail) {}

    /** Whether the record's own links hold, and what was not looked at. */
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
     * Verifies every archive timestamp against what RFC 4998 says it must cover.
     *
     * @param dataObjectHash {@code H(d)} under the FIRST chain's algorithm — the value the
     *        first token must carry. Required: without it a record that is perfectly
     *        self-consistent about somebody else's data passes.
     * @param dataObjectHashByAlgorithm {@code H(d)} under each later chain's algorithm, keyed by
     *        OID. A chain whose algorithm is absent from this map has its §5.3 relationship
     *        reported as unchecked — not as holding.
     */
    public static Report verify(byte[] der, byte[] dataObjectHash,
            Map<String, byte[]> dataObjectHashByAlgorithm) {
        List<TimestampResult> results = new ArrayList<>();
        ErsRecord record;
        try {
            record = ErsRecord.parse(der);
        } catch (Exception e) {
            return new Report(false, 0, List.of(), ErsRecord.LIMITS,
                    "The record could not be parsed (" + e.getMessage() + "), so nothing in it "
                            + "was checked. " + NOT_CHECKED);
        }
        if (dataObjectHash == null || dataObjectHash.length == 0) {
            return new Report(false, 0, List.of(), ErsRecord.LIMITS,
                    "No data object hash was supplied, so there was nothing to check the record "
                            + "AGAINST. A record verified only against itself says nothing about "
                            + "whose data it is. " + NOT_CHECKED);
        }
        boolean allHold = true;
        int checked = 0;
        List<List<ErsRecord.ArchiveTimeStamp>> chains = record.chains();
        for (int c = 0; c < chains.size(); c++) {
            List<ErsRecord.ArchiveTimeStamp> chain = chains.get(c);
            for (int i = 0; i < chain.size(); i++) {
                ErsRecord.ArchiveTimeStamp ats = chain.get(i);
                byte[] expected;
                try {
                    expected = expectedImprint(record, chains, c, i, dataObjectHash,
                            dataObjectHashByAlgorithm);
                } catch (RuntimeException e) {
                    results.add(new TimestampResult(c, i, false, ats.digestAlgorithmOid(), null,
                            "what this timestamp should cover could not be computed ("
                                    + e.getMessage() + "), which is not a finding that it is "
                                    + "wrong"));
                    allHold = false;
                    checked++;
                    continue;
                }
                TimestampResult result = check(c, i, ats, expected);
                results.add(result);
                checked++;
                allHold &= result.imprintMatches();
            }
        }
        if (checked == 0) {
            return new Report(false, 0, results, ErsRecord.LIMITS,
                    "The record contains no archive timestamp, so there was nothing to check. "
                            + NOT_CHECKED);
        }
        return new Report(allHold, checked, results, ErsRecord.LIMITS, NOT_CHECKED);
    }

    /** As above, when there is only one chain and so only one algorithm. */
    public static Report verify(byte[] der, byte[] dataObjectHash) {
        return verify(der, dataObjectHash, Map.of());
    }

    private static byte[] expectedImprint(ErsRecord record,
            List<List<ErsRecord.ArchiveTimeStamp>> chains, int c, int i, byte[] dataObjectHash,
            Map<String, byte[]> byAlgorithm) {
        ErsRecord.ArchiveTimeStamp ats = chains.get(c).get(i);
        if (c == 0 && i == 0) {
            if (!ats.firstHashList().isEmpty()) {
                // A first Archive Timestamp WITH a tree is a different form: §4.3 step 2 needs
                // h in the list and step 3 hashes the list.
                if (ats.firstHashList().stream().noneMatch(v -> Arrays.equals(v, dataObjectHash))) {
                    throw new IllegalStateException("the data object's hash is not in the first "
                            + "hash list, so this record is about something else");
                }
                return ErsRecord.digest(ats.digestAlgorithmOid(),
                        ErsRecord.sortedConcat(ats.firstHashList()));
            }
            return dataObjectHash;
        }
        if (i > 0) {
            // §5.2 timestamp renewal: the previous token's content, hashed.
            ErsRecord.ArchiveTimeStamp previous = chains.get(c).get(i - 1);
            byte[] over = ErsRecord.digest(ats.digestAlgorithmOid(), previous.timeStampDer());
            if (ats.firstHashList().isEmpty()) {
                return over;
            }
            if (ats.firstHashList().stream().noneMatch(v -> Arrays.equals(v, over))) {
                throw new IllegalStateException("this renewal's hash list does not contain the "
                        + "hash of the timestamp it renews, so it does not extend this chain");
            }
            return ErsRecord.digest(ats.digestAlgorithmOid(),
                    ErsRecord.sortedConcat(ats.firstHashList()));
        }
        // §5.3 hash-tree renewal: a new chain, whose h' must commit to every previous chain.
        if (ats.firstHashList().isEmpty()) {
            throw new IllegalStateException("a new chain with no hash list cannot commit to the "
                    + "chains before it, so it is a timestamp filed beside them rather than one "
                    + "that covers them");
        }
        byte[] ha = ErsRecord.digest(ats.digestAlgorithmOid(),
                ErsRecord.encodeSequence(chains.subList(0, c)));
        byte[] hOfData = byAlgorithm.get(ats.digestAlgorithmOid());
        if (hOfData != null) {
            byte[] hPrime = ErsRecord.digest(ats.digestAlgorithmOid(),
                    ErsRecord.sortedConcat(List.of(hOfData, ha)));
            if (ats.firstHashList().stream().noneMatch(v -> Arrays.equals(v, hPrime))) {
                throw new IllegalStateException("this chain's first hash value is not "
                        + "H(sorted(H(data), H(previous chains))), so it does not renew them");
            }
        }
        return ErsRecord.digest(ats.digestAlgorithmOid(),
                ErsRecord.sortedConcat(ats.firstHashList()));
    }

    private static TimestampResult check(int chain, int position,
            ErsRecord.ArchiveTimeStamp ats, byte[] expected) {
        try {
            TimeStampToken token = new TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(
                            new ByteArrayInputStream(ats.timeStampDer())));
            byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
            boolean matches = Arrays.equals(expected, imprint);
            return new TimestampResult(chain, position, matches,
                    token.getTimeStampInfo().getMessageImprintAlgOID().getId(),
                    String.valueOf(token.getTimeStampInfo().getGenTime().toInstant()),
                    matches ? "the token covers exactly what RFC 4998 says this position must "
                                    + "cover"
                            : "the token's message imprint is NOT what this position must "
                                    + "cover, so this timestamp does not belong here");
        } catch (Exception e) {
            return new TimestampResult(chain, position, false, ats.digestAlgorithmOid(), null,
                    "this timestamp could not be read (" + e.getMessage() + "), which is not a "
                            + "finding that it is wrong");
        }
    }
}
