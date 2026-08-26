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

    /**
     * One archive timestamp's result.
     *
     * <p>{@link Status} is a THIRD value, not a shade of false. A record whose newer chain
     * could not be checked because the caller did not supply {@code H(d)} under that algorithm
     * is machine-indistinguishable from a broken one if the only outputs are booleans — and a
     * consumer reading booleans is every consumer that is not reading the English.
     */
    public record TimestampResult(int chain, int position, Status status,
            String digestAlgorithmOid, String genTime, String detail) {

        public enum Status {
            /** The token covers what this position must cover. */
            MATCHES,
            /** It covers something else. A finding about the record. */
            DOES_NOT_MATCH,
            /** Nothing was established either way. A statement about this check. */
            NOT_CHECKED
        }

        public boolean imprintMatches() {
            return status == Status.MATCHES;
        }
    }

    /** Whether the record's own links hold, and what was not looked at. */
    /**
     * @param linksHold every position was checked AND matched
     * @param timestampsChecked how many were actually checked — not how many are in the record
     * @param timestampsNotChecked how many could not be, which is why linksHold is false
     */
    public record Report(boolean linksHold, int timestampsChecked, int timestampsNotChecked,
            List<TimestampResult> results, String limits, String notChecked) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            // The caveats FIRST. A reader skimming a verification result meets "the signature
            // was not checked" before the word that looks like a verdict.
            m.put("limits", limits);
            m.put("notChecked", notChecked);
            m.put("linksHold", linksHold);
            // Before the count of what WAS checked, because a non-zero value here is the
            // reason linksHold is false and a reader who meets the checked count first has
            // already concluded "something is wrong with the record".
            m.put("timestampsNotChecked", timestampsNotChecked);
            m.put("timestampsChecked", timestampsChecked);
            m.put("results", results.stream().map(r -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("chain", r.chain());
                one.put("position", r.position());
                one.put("status", r.status().name());
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
            return new Report(false, 0, 0, List.of(), ErsRecord.LIMITS,
                    "The record could not be parsed (" + e.getMessage() + "), so nothing in it "
                            + "was checked. " + NOT_CHECKED);
        }
        if (dataObjectHash == null || dataObjectHash.length == 0) {
            return new Report(false, 0, 0, List.of(), ErsRecord.LIMITS,
                    "No data object hash was supplied, so there was nothing to check the record "
                            + "AGAINST. A record verified only against itself says nothing about "
                            + "whose data it is. " + NOT_CHECKED);
        }
        boolean allHold = true;
        int checked = 0;
        int notChecked = 0;
        List<List<ErsRecord.ArchiveTimeStamp>> chains = record.chains();
        for (int c = 0; c < chains.size(); c++) {
            List<ErsRecord.ArchiveTimeStamp> chain = chains.get(c);
            for (int i = 0; i < chain.size(); i++) {
                ErsRecord.ArchiveTimeStamp ats = chain.get(i);
                byte[] expected;
                try {
                    expected = expectedImprint(record, chains, c, i, dataObjectHash,
                            dataObjectHashByAlgorithm);
                } catch (UncheckableLinkException e) {
                    // NOT_CHECKED, and not counted as checked. Counting it would make the
                    // report say it looked at something it did not.
                    results.add(new TimestampResult(c, i, TimestampResult.Status.NOT_CHECKED,
                            ats.digestAlgorithmOid(), null,
                            "this link was NOT checked: " + e.getMessage() + " — which is not a "
                                    + "finding that it is wrong, and not a finding that it "
                                    + "holds"));
                    notChecked++;
                    continue;
                } catch (RuntimeException e) {
                    // A structural disagreement IS a finding about the record.
                    results.add(new TimestampResult(c, i, TimestampResult.Status.DOES_NOT_MATCH,
                            ats.digestAlgorithmOid(), null,
                            "what this timestamp should cover could not be computed: "
                                    + e.getMessage()));
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
            return new Report(false, 0, 0, results, ErsRecord.LIMITS,
                    "The record contains no archive timestamp, so there was nothing to check. "
                            + NOT_CHECKED);
        }
        return new Report(allHold && notChecked == 0, checked, notChecked, results,
                ErsRecord.LIMITS, NOT_CHECKED);
    }

    /** As above, when there is only one chain and so only one algorithm. */
    public static Report verify(byte[] der, byte[] dataObjectHash) {
        return verify(der, dataObjectHash, Map.of());
    }

    private static byte[] expectedImprint(ErsRecord record,
            List<List<ErsRecord.ArchiveTimeStamp>> chains, int c, int i, byte[] dataObjectHash,
            Map<String, byte[]> byAlgorithm) {
        ErsRecord.ArchiveTimeStamp ats = chains.get(c).get(i);
        byte[] mustBeInFirstList;
        String what;
        if (c == 0 && i == 0) {
            mustBeInFirstList = dataObjectHash;
            what = "the data object's hash";
        } else if (i > 0) {
            // §5.2 timestamp renewal: the previous token's content, hashed.
            mustBeInFirstList = ErsRecord.digest(ats.digestAlgorithmOid(),
                    chains.get(c).get(i - 1).timeStampDer());
            what = "the hash of the timestamp this renews";
        } else {
            // §5.3 hash-tree renewal: a new chain, whose h' must commit to every previous one.
            byte[] ha = ErsRecord.digest(ats.digestAlgorithmOid(),
                    ErsRecord.encodeSequence(chains.subList(0, c)));
            byte[] hOfData = byAlgorithm.get(ats.digestAlgorithmOid());
            if (hOfData == null) {
                // NOT waved through. Returning H(the record's own list) here would compare the
                // token with a value taken from the record itself, so any timestamp filed
                // beside the old chains would pass — which is the defect this branch exists to
                // catch. "We cannot check this link" is not "this link holds".
                throw new UncheckableLinkException("this chain renews under "
                        + ats.digestAlgorithmOid() + ", and checking that it covers the older "
                        + "chains needs H(data object) under that algorithm — which cannot be "
                        + "derived from the old hash. Supply it, or this link stays unchecked.");
            }
            mustBeInFirstList = ErsRecord.digest(ats.digestAlgorithmOid(),
                    ErsRecord.sortedConcat(List.of(hOfData, ha)));
            what = "H(sorted(H(data), H(previous chains))), which is what makes this chain "
                    + "renew the ones before it rather than sit beside them";
        }
        if (ats.hashTree().isEmpty()) {
            // §4.2 allows an Archive Timestamp with no hash lists; §4.3 then degenerates to
            // "the root is h".
            return mustBeInFirstList;
        }
        return walk(ats, mustBeInFirstList, what);
    }

    /**
     * §4.3 steps 2 and 3, over EVERY list.
     *
     * <p>Step 2 finds {@code h} in the first list; step 3 hashes that list and requires the
     * result to be a member of the next, repeating to the root. Reading only the first list and
     * stopping — which is what the previous version did — lets a record carry
     * {@code [[H(d)], [anything]]} and be measured on the first list alone.
     */
    private static byte[] walk(ErsRecord.ArchiveTimeStamp ats, byte[] h, String what) {
        List<List<byte[]>> tree = ats.hashTree();
        if (tree.get(0).stream().noneMatch(v -> Arrays.equals(v, h))) {
            throw new IllegalStateException("the first hash list does not contain " + what
                    + ", so this Archive Timestamp is about something else");
        }
        byte[] current = ErsRecord.digest(ats.digestAlgorithmOid(),
                ErsRecord.sortedConcat(tree.get(0)));
        for (int level = 1; level < tree.size(); level++) {
            // The computed value BECOMES a member of the next list; it is not expected to be
            // stored there. §4.3 step 3: "This hash value h' MUST become a member of the next
            // higher list of hash values." A reduced tree stores only the SIBLINGS at each
            // level — the RFC's own example is pht1=(h2abc,h1), pht2=(h3), and the root is
            // H(sorted(h12, h3)) with h12 computed from pht1 and nowhere in pht2.
            //
            // Requiring the parent to be stored in the next list, which is what this did
            // before, rejects every standard record and accepts only the shape this product
            // was writing — encoder and verifier agreeing with each other again.
            List<byte[]> withParent = new ArrayList<>(tree.get(level));
            withParent.add(current);
            current = ErsRecord.digest(ats.digestAlgorithmOid(),
                    ErsRecord.sortedConcat(withParent));
        }
        return current;
    }

    /** A link this build cannot check — reported as unchecked, never as holding. */
    static class UncheckableLinkException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UncheckableLinkException(String message) {
            super(message);
        }
    }

    private static TimestampResult check(int chain, int position,
            ErsRecord.ArchiveTimeStamp ats, byte[] expected) {
        try {
            TimeStampToken token = new TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(
                            new ByteArrayInputStream(ats.timeStampDer())));
            byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
            String tokenAlgorithm = token.getTimeStampInfo().getMessageImprintAlgOID().getId();
            if (!tokenAlgorithm.equals(ats.digestAlgorithmOid())) {
                // §4.2 step 5: the timestamp's hash algorithm MUST be the tree's, or
                // digestAlgorithm must say what the tree uses. A record declaring SHA-512 with
                // a SHA-256 token is not a record whose tree was built under SHA-512.
                return new TimestampResult(chain, position,
                        TimestampResult.Status.DOES_NOT_MATCH, tokenAlgorithm,
                        String.valueOf(token.getTimeStampInfo().getGenTime().toInstant()),
                        "this Archive Timestamp declares " + ats.digestAlgorithmOid()
                                + " and its token was taken under " + tokenAlgorithm
                                + ", so the tree and the timestamp are not about the same "
                                + "algorithm");
            }
            boolean matches = Arrays.equals(expected, imprint);
            return new TimestampResult(chain, position,
                    matches ? TimestampResult.Status.MATCHES
                            : TimestampResult.Status.DOES_NOT_MATCH,
                    token.getTimeStampInfo().getMessageImprintAlgOID().getId(),
                    String.valueOf(token.getTimeStampInfo().getGenTime().toInstant()),
                    matches ? "the token covers exactly what RFC 4998 says this position must "
                                    + "cover"
                            : "the token's message imprint is NOT what this position must "
                                    + "cover, so this timestamp does not belong here");
        } catch (Exception e) {
            return new TimestampResult(chain, position,
                    TimestampResult.Status.NOT_CHECKED, ats.digestAlgorithmOid(), null,
                    "this timestamp could not be read (" + e.getMessage() + "), which is not a "
                            + "finding that it is wrong");
        }
    }
}
