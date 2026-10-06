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

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code LONG_TERM_ERS_V1} — the checks of {@code evidence-profile-v1.md} §14.
 *
 * <h2>The data object is the anchor target's MERKLE ROOT</h2>
 *
 * <p>Not the record, and not the checkpoint's canonical bytes. An evidence record beside a
 * document is read by almost everyone as a long term signature ON THAT DOCUMENT, and it is not:
 * its first Archive Timestamp <b>is</b> the RFC 3161 token this repository already had, and that
 * token was taken over the {@code merkleRoot}'s bytes (§11, §12). So the only value the record
 * can be about is that root, and a package whose record covers something else fails rather than
 * passing on the strength of being well formed.
 *
 * <p>What a receiver therefore cannot do alone: RFC 4998 §4.3 step 1 computes {@code h = H(d)}
 * from the data object {@code d}. Here {@code d} is the Merkle tree's top-level concatenation,
 * which <b>no package carries</b>. A receiver checks that the record covers the {@code merkleRoot}
 * this package states, not that the root was correctly derived from anything. §14 says so, and
 * so does the text that ships with the record.
 *
 * <h2>The record is read as a structure, not scanned for a value</h2>
 *
 * <p>The first version collected every 32-byte {@code OCTET STRING} anywhere in the DER and
 * asked whether the wanted digest was among them. That was wrong in both directions at once
 * (both reviews, fifth round):
 *
 * <ul>
 *   <li><b>It refused every real record.</b> A record this product writes has no reduced hash
 *       tree at all — the root lives inside the timestamp token's {@code TSTInfo}, inside the
 *       CMS {@code eContent} — and the scan stopped at that {@code OCTET STRING} without
 *       descending. Our own verifier would have answered {@code FAILED} on our own package.</li>
 *   <li><b>It accepted made-up ones.</b> Any DER declaring version 1 with the right 32 bytes
 *       lying anywhere inside it passed all three checks (residual R72).</li>
 * </ul>
 *
 * <p>So the record is now walked the way RFC 4998 defines it: version, digest algorithms, and
 * the {@code ArchiveTimeStampSequence} as the LAST element (the two optional tagged fields sit
 * between, so counting from the front reads an {@code encryptionInfo} as the timestamps).
 *
 * <h2>Unknown algorithms are not mismatches</h2>
 *
 * <p>§14 is explicit. A digest algorithm this version cannot compute means the record has not
 * been checked; calling it a mismatch would report a defect nobody found. Which algorithms are
 * looked at is now precise as well: the record's declared ones, each Archive Timestamp's own,
 * and each token's message imprint algorithm. The previous version walked the entire DER, so a
 * timestamp authority whose CMS signature used SHA-384 made a conformant record
 * {@code UNAVAILABLE} over an algorithm the record does not use for hashing anything.
 */
public final class LongTermErs {

    /** The checks this profile will not pass without — §14. */
    public static final List<String> REQUIRED =
            List.of("ers parse", "ers data object", "ers chain", "ers algorithms");

    /** RFC 4998 defines version 1 only. */
    private static final int VERSION = 1;

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    private LongTermErs() {
    }

    /** One {@code ArchiveTimeStamp}: its algorithm, its reduced hash tree, and its token. */
    record ArchiveTimeStamp(String digestOid, List<List<byte[]>> tree, byte[] tokenDer,
            ASN1Sequence encoded) {
    }

    /** Raised while reading the structure; the message is what the report says. */
    private static final class NotAnEvidenceRecord extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotAnEvidenceRecord(String message) {
            super(message);
        }
    }

    /** Raised when this build cannot compute something; never reported as a mismatch. */
    private static final class Uncheckable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Uncheckable(String message) {
            super(message);
        }
    }

    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();
        byte[] der = ersIn(entries);
        if (der == null) {
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.absent(name,
                        "the package carries no evidence record"));
            }
            return checks;
        }

        List<String> declared = new ArrayList<>();
        List<List<ArchiveTimeStamp>> chains;
        ASN1Sequence sequence;
        try {
            ASN1Sequence record = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der));
            // The version FIRST, and before the shape: the rest of the structure is defined by
            // the version, so a record of a later version whose layout this reader does not know
            // must be reported as unread rather than as malformed.
            if (record.size() == 0) {
                // An empty SEQUENCE states no version, so "a version this reader does not know"
                // would be a claim about a document that claims nothing (subagent, sixth
                // review, P3).
                throw new NotAnEvidenceRecord("the file is an empty SEQUENCE, which states "
                        + "neither a version nor a timestamp");
            }
            int version = ASN1Integer.getInstance(record.getObjectAt(0)).intValueExact();
            if (version != VERSION) {
                // UNSUPPORTED, not failed: a version this reader does not know has not been
                // checked, and nothing about it is a finding.
                checks.add(Outcome.Check.unavailable("ers parse", "UNSUPPORTED_ERS_VERSION",
                        "the record declares version " + version
                                + " and RFC 4998 defines version " + VERSION + " only"));
                for (String name : REQUIRED.subList(1, REQUIRED.size())) {
                    checks.add(Outcome.Check.absent(name, "the record did not parse"));
                }
                return checks;
            }
            if (record.size() < 3) {
                throw new NotAnEvidenceRecord("an EvidenceRecord has at least version, "
                        + "digestAlgorithms and archiveTimeStampSequence; this one has "
                        + record.size() + " element(s)");
            }
            ASN1Sequence algorithms = ASN1Sequence.getInstance(record.getObjectAt(1));
            for (int i = 0; i < algorithms.size(); i++) {
                declared.add(AlgorithmIdentifier.getInstance(algorithms.getObjectAt(i))
                        .getAlgorithm().getId());
            }
            // The LAST element: cryptoInfos [0] and encryptionInfo [1] sit between the digest
            // algorithms and the sequence, so counting from the front reads one of them as the
            // timestamps on any record that carries one.
            sequence = ASN1Sequence.getInstance(record.getObjectAt(record.size() - 1));
            chains = readChains(sequence);
        } catch (NotAnEvidenceRecord malformed) {
            checks.add(Outcome.Check.failed("ers parse",
                    "the package presents a file as an RFC 4998 evidence record and it does "
                            + "not parse as one: " + malformed.getMessage()));
            for (String name : REQUIRED.subList(1, REQUIRED.size())) {
                checks.add(Outcome.Check.absent(name, "the record did not parse"));
            }
            return checks;
        } catch (Exception notAnErs) {
            checks.add(Outcome.Check.failed("ers parse",
                    "the package presents a file as an RFC 4998 evidence record and it does "
                            + "not parse as one: " + notAnErs));
            for (String name : REQUIRED.subList(1, REQUIRED.size())) {
                checks.add(Outcome.Check.absent(name, "the record did not parse"));
            }
            return checks;
        }
        checks.add(Outcome.Check.passed("ers parse"));

        AnchoredRoot wanted = anchoredRoot(entries);
        checks.add(wanted.refusal() != null ? wanted.refusal()
                : dataObject(chains.get(0).get(0), wanted.value()));
        checks.add(chain(chains, sequence, wanted.value()));
        checks.add(algorithms(declared, chains));
        return checks;
    }

    // ------------------------------------------------------------------ structure

    private static List<List<ArchiveTimeStamp>> readChains(ASN1Sequence sequence) {
        List<List<ArchiveTimeStamp>> chains = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            ASN1Sequence chainSeq = asSequence(sequence.getObjectAt(i),
                    "an ArchiveTimeStampChain is a SEQUENCE OF ArchiveTimeStamp");
            List<ArchiveTimeStamp> chain = new ArrayList<>();
            for (int j = 0; j < chainSeq.size(); j++) {
                chain.add(readArchiveTimeStamp(asSequence(chainSeq.getObjectAt(j),
                        "an ArchiveTimeStamp is a SEQUENCE")));
            }
            if (chain.isEmpty()) {
                throw new NotAnEvidenceRecord("chain " + i + " holds no Archive Timestamp, so "
                        + "the record declares a chain that timestamps nothing");
            }
            chains.add(chain);
        }
        if (chains.isEmpty()) {
            throw new NotAnEvidenceRecord("the archiveTimeStampSequence is empty, so the record "
                    + "carries no timestamp at all");
        }
        return chains;
    }

    private static ASN1Sequence asSequence(Object value, String what) {
        try {
            return ASN1Sequence.getInstance(value);
        } catch (RuntimeException notASequence) {
            throw new NotAnEvidenceRecord(what + "; this is " + notASequence.getMessage());
        }
    }

    /**
     * {@code ArchiveTimeStamp ::= SEQUENCE { digestAlgorithm [0], attributes [1] OPTIONAL,
     * reducedHashtree [2] OPTIONAL, timeStamp ContentInfo }} — IMPLICIT tags.
     */
    private static ArchiveTimeStamp readArchiveTimeStamp(ASN1Sequence ats) {
        String algorithm = null;
        List<List<byte[]>> tree = new ArrayList<>();
        byte[] token = null;
        // Counted, not inferred. "[2] seen" was inferred from `tree.isEmpty()`, so an EMPTY
        // first [2] let a second one through; [1] was type-checked and never counted; and a
        // second non-tagged SEQUENCE silently overwrote the token. RFC 4998 gives each of these
        // fields once (Codex, seventh review, P2).
        boolean sawTree = false;
        boolean sawAttributes = false;
        for (int i = 0; i < ats.size(); i++) {
            Object element = ats.getObjectAt(i);
            if (element instanceof ASN1TaggedObject tagged) {
                // RFC 4998 defines [0], [1] and [2] here and nothing else. Skipping whatever
                // else turned up meant a record could carry a field this reader does not
                // understand and still pass every check — "we did not look" reported as "we
                // checked" (Codex, sixth review, P2).
                if (tagged.getTagNo() > 2) {
                    throw new NotAnEvidenceRecord("an ArchiveTimeStamp carries a ["
                            + tagged.getTagNo() + "] field, and RFC 4998 defines [0], [1] and "
                            + "[2] only. This reader does not know what it says");
                }
                if (tagged.getTagNo() == 1) {
                    if (sawAttributes) {
                        throw new NotAnEvidenceRecord("an ArchiveTimeStamp carries attributes "
                                + "[1] twice, and RFC 4998 gives it once");
                    }
                    sawAttributes = true;
                    try {
                        org.bouncycastle.asn1.ASN1Set.getInstance(tagged, false);
                    } catch (RuntimeException notASet) {
                        throw new NotAnEvidenceRecord("an ArchiveTimeStamp's attributes [1] is "
                                + "not a SET: " + notASet.getMessage());
                    }
                    continue;
                }
                if (tagged.getTagNo() == 0) {
                    if (algorithm != null) {
                        throw new NotAnEvidenceRecord("an ArchiveTimeStamp states its "
                                + "digestAlgorithm twice, so which one built its tree is not "
                                + "decided by the record");
                    }
                    algorithm = AlgorithmIdentifier.getInstance(tagged, false)
                            .getAlgorithm().getId();
                } else if (tagged.getTagNo() == 2) {
                    if (sawTree) {
                        throw new NotAnEvidenceRecord("an ArchiveTimeStamp carries two reduced "
                                + "hash trees, so which one it commits to is not decided by the "
                                + "record");
                    }
                    sawTree = true;
                    ASN1Sequence lists = ASN1Sequence.getInstance(tagged, false);
                    for (int level = 0; level < lists.size(); level++) {
                        ASN1Sequence partial = asSequence(lists.getObjectAt(level),
                                "a PartialHashtree is a SEQUENCE OF OCTET STRING");
                        List<byte[]> values = new ArrayList<>();
                        for (int k = 0; k < partial.size(); k++) {
                            values.add(ASN1OctetString.getInstance(partial.getObjectAt(k))
                                    .getOctets());
                        }
                        if (values.isEmpty()) {
                            throw new NotAnEvidenceRecord("a PartialHashtree holds no hash "
                                    + "value, so a level of the reduced tree commits to nothing");
                        }
                        tree.add(values);
                    }
                }
                continue;
            }
            if (token != null) {
                throw new NotAnEvidenceRecord("an ArchiveTimeStamp carries more than one "
                        + "timeStamp, so which one fixes its time is not decided by the record");
            }
            try {
                token = ASN1Sequence.getInstance(element).getEncoded(ASN1Encoding.DER);
            } catch (Exception notAContentInfo) {
                throw new NotAnEvidenceRecord("an ArchiveTimeStamp's timeStamp field is a "
                        + "ContentInfo; this one is " + notAContentInfo);
            }
        }
        if (token == null) {
            throw new NotAnEvidenceRecord("an ArchiveTimeStamp carries no timestamp token, so "
                    + "there is nothing in it that fixes a time. RFC 4998 makes every other "
                    + "field optional and this one mandatory");
        }
        // Parsed here, so a "record" whose token is not one is a parse finding rather than a
        // silent absence three checks later.
        TimeStampToken parsed = tokenOf(token);
        if (algorithm == null) {
            // §4.2: "If the optional field digestAlgorithm is not present, the digest algorithm
            // of the timestamp MUST be used." Requiring the field refused every record
            // BouncyCastle's own generator produces for a single data object — and this
            // product's OWN reader (ErsRecord.parse) already implements the fallback, so two
            // readers of one format were answering the same bytes differently (subagent, sixth
            // review, P1).
            algorithm = parsed.getTimeStampInfo().getMessageImprintAlgOID().getId();
        }
        return new ArchiveTimeStamp(algorithm, tree, token, ats);
    }

    private static TimeStampToken tokenOf(byte[] der) {
        try {
            return new TimeStampToken(new CMSSignedData(new ByteArrayInputStream(der)));
        } catch (Exception notAToken) {
            throw new NotAnEvidenceRecord("an ArchiveTimeStamp's timeStamp is not an RFC 3161 "
                    + "token: " + notAToken);
        }
    }

    // ------------------------------------------------------------------ the checks

    /** The anchored root, or the answer that stands in for it when the target cannot give one. */
    private record AnchoredRoot(byte[] value, Outcome.Check refusal) {
    }

    /**
     * The root the package says was anchored: absent when it states none, FAILED when the
     * target is there and is not a document or its root is not a hex string (§3.2, §5).
     *
     * <p>Every way of not getting a root used to be null — "states none" — so a target that
     * did not parse made {@code ers data object} NOT_PRESENT, where the sibling readers of the
     * same file (P2, P3) say FAILED (c96 confirmation review, P2).
     */
    private static AnchoredRoot anchoredRoot(Map<String, byte[]> entries) {
        Section.Doc target = Section.read(entries, "anchor-target-checkpoint.json");
        if (target.malformed()) {
            return new AnchoredRoot(null,
                    Section.malformed("ers data object", "anchor-target-checkpoint.json"));
        }
        if (target.value() == null || target.value().get("merkleRoot") == null) {
            return new AnchoredRoot(null, null);
        }
        Object root = target.value().get("merkleRoot");
        if (!(root instanceof String hex) || hex.isBlank()) {
            return new AnchoredRoot(null, Outcome.Check.failed("ers data object",
                    "the anchor target's merkleRoot is not a string (§5: a field of the wrong type)"));
        }
        try {
            return new AnchoredRoot(unhex(hex), null);
        } catch (RuntimeException notHex) {
            return new AnchoredRoot(null, Outcome.Check.failed("ers data object",
                    "the anchor target's merkleRoot is not hex: " + notHex.getMessage()));
        }
    }

    private static byte[] unhex(String hex) {
        String value = hex.trim();
        if (value.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("not hex");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /**
     * RFC 4998 §4.3 over the first Archive Timestamp, with {@code h} = the anchored root.
     *
     * <p>Case-insensitive, because hex case is not meaning: the product writes lowercase and
     * accepts either when it anchors, so a package stating an uppercase root would otherwise be
     * reported as covering something else (both reviews, fifth round).
     */
    static Outcome.Check dataObject(ArchiveTimeStamp first, byte[] wanted) {
        if (wanted == null) {
            return Outcome.Check.absent("ers data object",
                    "the package states no anchor target Merkle root, so what the record should "
                            + "cover is not in the package");
        }
        byte[] imprint;
        try {
            imprint = tokenOf(first.tokenDer()).getTimeStampInfo().getMessageImprintDigest();
        } catch (NotAnEvidenceRecord unreadable) {
            return Outcome.Check.failed("ers data object", unreadable.getMessage());
        }
        if (first.tree().isEmpty()) {
            // §4.2: "An Archive Timestamp may consist ... only of a timestamp with no hash value
            // lists." §4.3 then degenerates to "the root hash value must correspond to
            // hashedMessage", and the root IS h.
            if (Arrays.equals(imprint, wanted)) {
                return Outcome.Check.passed("ers data object");
            }
            return Outcome.Check.failed("ers data object", mismatch(wanted, imprint));
        }
        if (first.tree().get(0).stream().noneMatch(value -> Arrays.equals(value, wanted))) {
            return Outcome.Check.failed("ers data object",
                    "the first hash list does not contain " + Canonical.hex(wanted)
                            + ", the Merkle root this package says was anchored, so this "
                            + "Archive Timestamp is about something else");
        }
        byte[] root;
        try {
            root = walk(first);
        } catch (Uncheckable cannot) {
            return Outcome.Check.unavailable("ers data object", "UNKNOWN_ALGORITHM",
                    cannot.getMessage());
        }
        if (!Arrays.equals(root, imprint)) {
            return Outcome.Check.failed("ers data object",
                    "the reduced hash tree reduces to " + Canonical.hex(root) + " and the token "
                            + "covers " + Canonical.hex(imprint) + ", so the tree and the "
                            + "timestamp are not about the same thing");
        }
        return Outcome.Check.passed("ers data object");
    }

    private static String mismatch(byte[] wanted, byte[] imprint) {
        return "the record's first timestamp covers " + Canonical.hex(imprint) + " and this "
                + "package says the anchored Merkle root is " + Canonical.hex(wanted)
                + ". An evidence record beside a document is NOT a signature on the document, "
                + "and one covering something else is not this package's evidence";
    }

    /**
     * §4.3 step 3: hash the first list, and let the result join each next list in turn.
     *
     * <p>The computed value BECOMES a member of the next list; it is not stored there. Requiring
     * it to be stored rejects every standard record — the same reading the product's own
     * {@code ErsVerifier} had to be corrected on.
     */
    private static byte[] walk(ArchiveTimeStamp ats) {
        byte[] current = nodeHash(ats.digestOid(), ats.tree().get(0));
        for (int level = 1; level < ats.tree().size(); level++) {
            List<byte[]> withParent = new ArrayList<>(ats.tree().get(level));
            withParent.add(current);
            current = nodeHash(ats.digestOid(), withParent);
        }
        return current;
    }

    /**
     * A hash list's node hash — §4.2.
     *
     * <p>"For each data group containing MORE THAN ONE document, its respective document hashes
     * are binary sorted in ascending order, concatenated, and hashed." A list of one is
     * therefore its own node hash, and hashing it anyway produces a root no conformant record
     * carries. BouncyCastle's {@code ERSUtil.computeNodeHash} returns {@code values[0]} for a
     * one-element list; this hashed unconditionally, so a record built by any standard tool
     * reduced to the wrong value and was reported as "the tree and the timestamp are not about
     * the same thing" (subagent, sixth review, P1 — measured against BouncyCastle's bytecode).
     */
    private static byte[] nodeHash(String digestOid, List<byte[]> values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        return digest(digestOid, sortedConcat(values));
    }

    /**
     * {@code ERS_CHAIN} — every Archive Timestamp after the first covers the one before it (§14).
     *
     * <p>Two operations, and they are not the same one (RFC 4998 §5.2, §5.3):
     *
     * <ul>
     *   <li><b>Timestamp renewal</b> stays in the chain: the new token's imprint is
     *       {@code H(previous timeStamp field's DER)}, under the chain's algorithm.</li>
     *   <li><b>Hash-tree renewal</b> starts a chain: the new Archive Timestamp's first list
     *       holds {@code H(sorted(h, ha))} where {@code ha} is the digest of the DER of every
     *       previous chain. Without the {@code ha} term the new chain commits to nothing that
     *       came before — an unrelated timestamp filed beside the record.</li>
     * </ul>
     *
     * <p>A record with one Archive Timestamp — which is all this product writes — has nothing to
     * chain, and that is a PASS with the reason stated rather than a silent one.
     */
    static Outcome.Check chain(List<List<ArchiveTimeStamp>> chains, ASN1Sequence sequence,
            byte[] wanted) {
        int total = chains.stream().mapToInt(List::size).sum();
        if (total == 1) {
            return Outcome.Check.passed("ers chain",
                    "the record holds one Archive Timestamp, so there is no renewal to follow. "
                            + "What that timestamp covers is reported by 'ers data object'");
        }
        try {
            for (int c = 0; c < chains.size(); c++) {
                List<ArchiveTimeStamp> chain = chains.get(c);
                if (c > 0) {
                    Outcome.Check started = chainStart(chains, sequence, c, wanted);
                    if (started != null) {
                        return started;
                    }
                }
                for (int i = 1; i < chain.size(); i++) {
                    ArchiveTimeStamp previous = chain.get(i - 1);
                    ArchiveTimeStamp current = chain.get(i);
                    if (!current.digestOid().equals(previous.digestOid())) {
                        return Outcome.Check.failed("ers chain",
                                "chain " + c + " changes digest algorithm at position " + i
                                        + " (" + previous.digestOid() + " to "
                                        + current.digestOid() + "). §5.2 keeps the algorithm; a "
                                        + "new one starts a new chain");
                    }
                    byte[] expected = digest(current.digestOid(), previous.tokenDer());
                    byte[] imprint = tokenOf(current.tokenDer()).getTimeStampInfo()
                            .getMessageImprintDigest();
                    // §5.2 allows a renewal WITH a reduced hash tree: "The new Archive
                    // Timestamp MAY not contain a reducedHashtree field, if the timestamp only
                    // simply covers the previous timestamp" — may not, not must not. When there
                    // is a tree, the previous token's hash goes in the first list and the
                    // token covers the tree's reduction. Comparing the imprint directly in both
                    // cases refused every renewal of the first shape and accepted any tree at
                    // all in the second (Codex, sixth review, P1 — both directions at once).
                    if (!current.tree().isEmpty()) {
                        if (current.tree().get(0).stream()
                                .noneMatch(value -> Arrays.equals(value, expected))) {
                            return Outcome.Check.failed("ers chain",
                                    "the renewal at chain " + c + " position " + i + " carries a "
                                            + "hash tree whose first list does not hold "
                                            + Canonical.hex(expected) + ", the digest of the "
                                            + "timestamp it renews, so it renews nothing");
                        }
                        byte[] renewalRoot = walk(current);
                        if (!Arrays.equals(renewalRoot, imprint)) {
                            return Outcome.Check.failed("ers chain",
                                    "the renewal at chain " + c + " position " + i + " reduces "
                                            + "to " + Canonical.hex(renewalRoot) + " and its "
                                            + "token covers " + Canonical.hex(imprint) + ", so "
                                            + "the tree and the timestamp are not about the "
                                            + "same thing");
                        }
                    } else if (!Arrays.equals(expected, imprint)) {
                        return Outcome.Check.failed("ers chain",
                                "the timestamp at chain " + c + " position " + i + " covers "
                                        + Canonical.hex(imprint) + " and §5.2 requires it to "
                                        + "cover " + Canonical.hex(expected) + ", the digest of "
                                        + "the timestamp before it. A renewal that does not "
                                        + "cover what it renews is a timestamp filed beside the "
                                        + "record, not part of it");
                    }
                }
            }
        } catch (Uncheckable cannot) {
            return Outcome.Check.unavailable("ers chain", "UNKNOWN_ALGORITHM", cannot.getMessage());
        } catch (NotAnEvidenceRecord unreadable) {
            return Outcome.Check.failed("ers chain", unreadable.getMessage());
        }
        return Outcome.Check.passed("ers chain");
    }

    /** §5.3: the first Archive Timestamp of chain {@code c} commits to every earlier chain. */
    private static Outcome.Check chainStart(List<List<ArchiveTimeStamp>> chains,
            ASN1Sequence sequence, int c, byte[] wanted) {
        ArchiveTimeStamp first = chains.get(c).get(0);
        if (wanted == null) {
            return Outcome.Check.absent("ers chain",
                    "the package states no anchor target Merkle root, so the h term of §5.3's "
                            + "H(sorted(h, ha)) is not available to recompute");
        }
        org.bouncycastle.asn1.ASN1EncodableVector earlier =
                new org.bouncycastle.asn1.ASN1EncodableVector();
        for (int i = 0; i < c; i++) {
            earlier.add(sequence.getObjectAt(i));
        }
        byte[] previousSequenceDer;
        try {
            previousSequenceDer = new DERSequence(earlier).getEncoded(ASN1Encoding.DER);
        } catch (Exception cannotEncode) {
            return Outcome.Check.unavailable("ers chain", "ANCHOR_NOT_PARSED",
                    "the earlier chains could not be re-encoded to recompute §5.3's ha term: "
                            + cannotEncode);
        }
        byte[] ha = digest(first.digestOid(), previousSequenceDer);
        byte[] expected = digest(first.digestOid(), sortedConcat(List.of(wanted, ha)));
        byte[] imprint = tokenOf(first.tokenDer()).getTimeStampInfo().getMessageImprintDigest();
        if (first.tree().isEmpty()) {
            // The DEGENERATE form, which §4.2 allows anywhere ("An Archive Timestamp may
            // consist ... only of a timestamp with no hash value lists") and which
            // BouncyCastle's renewHash actually produces: h' goes straight into the token's
            // imprint and there is no list. Requiring a tree here reported every §5.3 renewal a
            // standard tool builds as FAILED — exit 2, "checked and wrong" — while this
            // product's OWN ErsVerifier accepted the same bytes one function over (subagent,
            // seventh review, P1, measured against BouncyCastle).
            if (!Arrays.equals(expected, imprint)) {
                return Outcome.Check.failed("ers chain",
                        "chain " + c + " covers " + Canonical.hex(imprint) + " and §5.3 requires "
                                + "it to cover " + Canonical.hex(expected)
                                + ", H(sorted(h, ha)) — so it does not commit to the chains "
                                + "before it and is a timestamp filed beside them");
            }
            return null;
        }
        if (first.tree().get(0).stream().noneMatch(value -> Arrays.equals(value, expected))) {
            return Outcome.Check.failed("ers chain",
                    "chain " + c + " does not commit to the chains before it: its first hash "
                            + "list holds no " + Canonical.hex(expected) + ", which §5.3 "
                            + "computes as H(sorted(h, ha))");
        }
        // And the TOKEN has to cover that tree. Checking membership alone left the newest
        // chain's time claim tied to nothing: a tree naming the right value beside a timestamp
        // about anything at all passed, and the whole profile composed to VERIFIED (subagent,
        // sixth review, P2).
        byte[] root = walk(first);
        if (!Arrays.equals(root, imprint)) {
            return Outcome.Check.failed("ers chain",
                    "chain " + c + "'s first hash tree reduces to " + Canonical.hex(root)
                            + " and its timestamp covers " + Canonical.hex(imprint) + ", so the "
                            + "timestamp that starts this chain is not about the chain");
        }
        return null;
    }

    /**
     * Every digest algorithm the record USES must be one this version can compute.
     *
     * <p>The declared set, each Archive Timestamp's own, and each token's message imprint
     * algorithm. Not every OID in the DER: a timestamp authority signing its CMS under SHA-384
     * does not make the record's hash tree a SHA-384 tree, and reporting it as uncheckable made
     * a conformant record {@code INDETERMINATE} over something the record never hashes with.
     */
    static Outcome.Check algorithms(List<String> declared,
            List<List<ArchiveTimeStamp>> chains) {
        Set<String> used = new LinkedHashSet<>(declared);
        for (List<ArchiveTimeStamp> chain : chains) {
            for (ArchiveTimeStamp ats : chain) {
                used.add(ats.digestOid());
                try {
                    used.add(tokenOf(ats.tokenDer()).getTimeStampInfo()
                            .getMessageImprintAlgOID().getId());
                } catch (NotAnEvidenceRecord unreadable) {
                    return Outcome.Check.failed("ers algorithms", unreadable.getMessage());
                }
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String oid : used) {
            if (!SHA256_OID.equals(oid) && !unknown.contains(oid)) {
                unknown.add(oid);
            }
        }
        if (!unknown.isEmpty()) {
            return Outcome.Check.unavailable("ers algorithms", "UNKNOWN_ALGORITHM",
                    "the record uses " + unknown + ", which this version cannot compute. "
                            + "That is NOT a mismatch — it means the record has not been checked");
        }
        return Outcome.Check.passed("ers algorithms");
    }

    // ------------------------------------------------------------------ shared hashing

    /** RFC 4998 §4.2/§4.3: binary ascending sort, then concatenate. No prefixes, no lengths. */
    static byte[] sortedConcat(List<byte[]> values) {
        List<byte[]> sorted = new ArrayList<>(values);
        sorted.sort(Arrays::compareUnsigned);
        int total = 0;
        for (byte[] value : sorted) {
            total += value.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] value : sorted) {
            System.arraycopy(value, 0, out, at, value.length);
            at += value.length;
        }
        return out;
    }

    private static byte[] digest(String oid, byte[] input) {
        if (!SHA256_OID.equals(oid)) {
            throw new Uncheckable("this version computes SHA-256 only and the record uses "
                    + oid + ", so nothing here recomputed its hashes");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new Uncheckable("this JVM does not provide SHA-256");
        }
    }

    /** Kept so a caller can name the OID this version implements. */
    static ASN1ObjectIdentifier sha256() {
        return new ASN1ObjectIdentifier(SHA256_OID);
    }

    /**
     * The evidence record, wherever the package puts it.
     *
     * <p>Two places are accepted because two exist: the legacy layout writes
     * {@code metadata/other/ers.der}, and §4.2 puts it under the evidence directory.
     */
    private static byte[] ersIn(Map<String, byte[]> entries) {
        byte[] inSection = fileIn(entries, "anchors/ers.der");
        if (inSection != null) {
            return inSection;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            // PAYLOAD excluded here too. This arm was the eighth lookup, and the only one left
            // scanning: a package carrying no record of its own answered "ers parse PASSED"
            // off a copy sitting in its content (subagent, ninth review, P2, measured).
            if (!Section.isPayload(entry.getKey())
                    && (entry.getKey().endsWith("/metadata/other/ers.der")
                            || entry.getKey().endsWith("metadata/other/ers.der"))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Delegated to {@link Section}, which is the ONE place that excludes payload. */
    private static byte[] fileIn(Map<String, byte[]> entries, String name) {
        return Section.fileIn(entries, name);
    }
}
