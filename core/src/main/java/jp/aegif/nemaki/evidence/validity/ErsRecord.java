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

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An RFC 4998 Evidence Record, built and parsed with ASN.1 primitives (P2-3).
 *
 * <pre>
 * EvidenceRecord ::= SEQUENCE {
 *     version                  INTEGER { v1(1) },
 *     digestAlgorithms         SEQUENCE OF AlgorithmIdentifier,
 *     cryptoInfos          [0] CryptoInfos OPTIONAL,      -- not produced
 *     encryptionInfo       [1] EncryptionInfo OPTIONAL,   -- not produced
 *     archiveTimeStampSequence ArchiveTimeStampSequence }
 * ArchiveTimeStamp ::= SEQUENCE {
 *     digestAlgorithm  [0] AlgorithmIdentifier OPTIONAL,
 *     attributes       [1] Attributes OPTIONAL,           -- not produced
 *     reducedHashtree  [2] SEQUENCE OF PartialHashtree OPTIONAL,
 *     timeStamp            ContentInfo }
 * </pre>
 *
 * <p>The module is {@code DEFINITIONS IMPLICIT TAGS}, so the tagged fields are implicit —
 * checked against the RFC text, not assumed.
 *
 * <h2>The data object is the checkpoint's canonical bytes</h2>
 *
 * <p>Not "the checkpoint hash". The distinction decides whether a standard verifier can read
 * this at all, and the first version got it wrong. RFC 4998 §4.3 has a verifier compute
 * {@code h = H(d)} and then look for {@code h} in the first hash list. Putting the checkpoint
 * hash {@code C} in that list while ALSO calling {@code C} the data object means a verifier
 * searches for {@code H(C)}, finds {@code C}, and rejects — before it ever looks at the token.
 *
 * <p>What is true is simpler: {@code C} is already {@code H(canonical checkpoint bytes)}, and
 * this repository's RFC 3161 anchor is a token whose message imprint is exactly {@code C}. So
 * the data object is those canonical bytes, {@code h = C}, and the record needs <b>no reduced
 * hash tree at all</b> — which RFC 4998 §4.2 explicitly allows: "An Archive Timestamp may
 * consist ... only of a timestamp with no hash value lists." §4.3 then degenerates to "the root
 * hash value must correspond to hashedMessage", and the root IS {@code h}.
 *
 * <p>That form is conformant AND reuses the anchor this product already has. The alternative —
 * a one-node tree holding {@code H(C)} — would need a NEW token over {@code H(H(C))}, because
 * §4.3 step 3 hashes the list even when it has one member.
 *
 * <h2>Renewals follow §5.2 and §5.3, which are not the same operation</h2>
 *
 * <p><b>Timestamp renewal</b> (§5.2): the content of the old {@code timeStamp} field is hashed
 * and timestamped. "The new Archive Timestamp MAY not contain a reducedHashtree field, if the
 * timestamp only simply covers the previous timestamp" — so this produces none, and the new
 * token's imprint must be {@code H(previous ContentInfo DER)}. It stays in the SAME chain and
 * MUST use the same hash algorithm.
 *
 * <p><b>Hash-tree renewal</b> (§5.3): the data object AND every previous chain are hashed under
 * a NEW algorithm. {@code ha = H(DER of the whole previous ArchiveTimeStampSequence)},
 * {@code h' = H(sorted concat of h(d) and ha)}, the new Archive Timestamp's first list holds
 * {@code h'}, and it starts a NEW chain. Without the {@code ha} term the new chain would not
 * commit to the old ones — it would be an unrelated timestamp filed next to them, which is what
 * the first version produced.
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md} §8.
 */
public final class ErsRecord {

    /** RFC 4998 §4: the only version defined. */
    public static final int VERSION = 1;

    /** SHA-256, the algorithm this repository's ledger and anchors use. */
    public static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    /**
     * What a reader must not conclude from an evidence record this product produced.
     *
     * <p>Travels with the record wherever it is reported.
     */
    public static final String LIMITS =
            "The data object of this evidence record is the canonical serialisation of one "
                    + "CHECKPOINT of this repository's evidence ledger — not a document. It "
                    + "establishes that that value existed by the time in its timestamp token, "
                    + "and nothing else. Which records were under that checkpoint is shown by "
                    + "this product's own inclusion proof, which travels separately and which a "
                    + "standard RFC 4998 verifier does NOT check. This product also does not "
                    + "verify the timestamp authority's certificate chain or its revocation "
                    + "status: it holds no trust anchors, so the token's signature is carried "
                    + "for a verifier that does.";

    private final byte[] der;
    private final List<List<ArchiveTimeStamp>> chains;
    private final List<String> digestAlgorithmOids;

    private ErsRecord(byte[] der, List<List<ArchiveTimeStamp>> chains,
            List<String> digestAlgorithmOids) {
        this.der = der;
        this.chains = chains;
        this.digestAlgorithmOids = digestAlgorithmOids;
    }

    /**
     * One archive timestamp.
     *
     * @param firstHashList the members of the first {@code PartialHashtree}, or empty when the
     *        Archive Timestamp carries no reduced hash tree
     * @param digestAlgorithmOid the algorithm of THIS timestamp's tree
     */
    public record ArchiveTimeStamp(List<List<byte[]>> hashTree, byte[] timeStampDer,
            String digestAlgorithmOid) {

        /** The first {@code PartialHashtree}, or empty when there is no tree at all. */
        public List<byte[]> firstHashList() {
            return hashTree.isEmpty() ? List.of() : hashTree.get(0);
        }
    }

    /** The DER bytes. This is the file that goes in a package. */
    public byte[] der() {
        return der.clone();
    }

    public List<List<ArchiveTimeStamp>> chains() {
        return chains;
    }

    /** Every digest algorithm this record declares, in the order they were introduced. */
    public List<String> digestAlgorithmOids() {
        return List.copyOf(digestAlgorithmOids);
    }

    /** How many timestamps are in the newest chain — the renewal depth a reader asks about. */
    public int timestampsInCurrentChain() {
        return chains.isEmpty() ? 0 : chains.get(chains.size() - 1).size();
    }

    // ---- what a token must cover, so a caller can ask a TSA for the right thing ----

    /**
     * The message imprint the FIRST token must carry: the data object's hash, unchanged.
     *
     * <p>Exposed rather than left implicit because getting it wrong produces a record that
     * looks right and no standard tool accepts. A caller asks its TSA for a token over this.
     */
    public static byte[] imprintForFirst(byte[] dataObjectHash) {
        require(dataObjectHash, "dataObjectHash");
        return dataObjectHash.clone();
    }

    /** The imprint a §5.2 timestamp renewal must carry: {@code H(previous ContentInfo DER)}. */
    public byte[] imprintForTimestampRenewal() {
        if (chains.isEmpty() || chains.get(chains.size() - 1).isEmpty()) {
            throw new IllegalStateException("there is no timestamp to renew");
        }
        List<ArchiveTimeStamp> current = chains.get(chains.size() - 1);
        ArchiveTimeStamp previous = current.get(current.size() - 1);
        return digest(previous.digestAlgorithmOid(), previous.timeStampDer());
    }

    /**
     * The imprint a §5.3 hash-tree renewal must carry, and the {@code h'} that goes in its tree.
     *
     * @param dataObjectHashUnderNewAlgorithm {@code H(d)} recomputed with the new algorithm
     * @param algorithmOid the new algorithm
     */
    public HashTreeRenewalInputs inputsForHashTreeRenewal(byte[] dataObjectHashUnderNewAlgorithm,
            String algorithmOid) {
        require(dataObjectHashUnderNewAlgorithm, "dataObjectHashUnderNewAlgorithm");
        byte[] ha = digest(algorithmOid, encodeSequence(chains));
        byte[] hPrime = digest(algorithmOid,
                sortedConcat(List.of(dataObjectHashUnderNewAlgorithm, ha)));
        return new HashTreeRenewalInputs(hPrime, digest(algorithmOid, hPrime), ha);
    }

    /**
     * What a §5.3 renewal needs.
     *
     * @param hPrime the value that goes in the new Archive Timestamp's first hash list
     * @param imprint what the new token must cover — {@code H(h')}, because §4.3 step 3 hashes
     *        the list even when it holds one member
     * @param previousSequenceHash {@code ha}, kept so a caller can show its working
     */
    public record HashTreeRenewalInputs(byte[] hPrime, byte[] imprint,
            byte[] previousSequenceHash) {}

    // ---- construction ----

    /**
     * The first evidence record over a data object whose hash is {@code dataObjectHash}.
     *
     * <p>No reduced hash tree: the token covers the data object's hash directly, which is the
     * form §4.2 allows and the one this repository's existing anchors already fit.
     */
    public static ErsRecord first(byte[] dataObjectHash, byte[] timeStampTokenDer) {
        require(dataObjectHash, "dataObjectHash");
        require(timeStampTokenDer, "timeStampTokenDer");
        List<List<ArchiveTimeStamp>> chains = new ArrayList<>();
        List<ArchiveTimeStamp> chain = new ArrayList<>();
        chain.add(new ArchiveTimeStamp(List.of(), timeStampTokenDer.clone(), SHA256_OID));
        chains.add(chain);
        List<String> algorithms = List.of(SHA256_OID);
        return new ErsRecord(encode(chains, algorithms), chains, algorithms);
    }

    /**
     * Appends a §5.2 timestamp renewal to the newest chain.
     *
     * <p>The token must cover {@link #imprintForTimestampRenewal()}. This does not check that —
     * it cannot without the token's issuer — but {@link ErsVerifier} does, so a wrong one is
     * caught the first time the record is read rather than the first time it is relied on.
     */
    public ErsRecord withTimestampRenewal(byte[] renewalTokenDer) {
        require(renewalTokenDer, "renewalTokenDer");
        if (chains.isEmpty()) {
            throw new IllegalStateException("there is no chain to renew");
        }
        List<List<ArchiveTimeStamp>> next = deepCopy();
        List<ArchiveTimeStamp> current = next.get(next.size() - 1);
        // Same algorithm as the chain it joins: §5.2 requires it, and a chain whose links are
        // hashed under different algorithms cannot be walked.
        current.add(new ArchiveTimeStamp(List.of(), renewalTokenDer.clone(),
                current.get(current.size() - 1).digestAlgorithmOid()));
        return new ErsRecord(encode(next, digestAlgorithmOids), next, digestAlgorithmOids);
    }

    /**
     * Starts a new chain for a §5.3 hash-tree renewal.
     *
     * <p>{@code h'} must come from {@link #inputsForHashTreeRenewal}: it is the only value that
     * commits the new chain to every previous one. Passing an arbitrary hash produces a
     * timestamp filed next to the old chains rather than one that covers them.
     */
    public ErsRecord withHashTreeRenewal(byte[] hPrime, byte[] timeStampTokenDer,
            String algorithmOid) {
        require(hPrime, "hPrime");
        require(timeStampTokenDer, "timeStampTokenDer");
        if (algorithmOid == null || algorithmOid.isBlank()) {
            throw new IllegalArgumentException("a hash-tree renewal happens BECAUSE the "
                    + "algorithm changed, so it has to say which one it is now");
        }
        List<List<ArchiveTimeStamp>> next = deepCopy();
        List<ArchiveTimeStamp> chain = new ArrayList<>();
        chain.add(new ArchiveTimeStamp(List.of(List.of(hPrime.clone())),
                timeStampTokenDer.clone(), algorithmOid));
        next.add(chain);
        Set<String> algorithms = new LinkedHashSet<>(digestAlgorithmOids);
        algorithms.add(algorithmOid);
        List<String> declared = List.copyOf(algorithms);
        return new ErsRecord(encode(next, declared), next, declared);
    }

    // ---- parsing ----

    /** Reads a record back. Throws rather than returning a half-parsed one. */
    public static ErsRecord parse(byte[] der) throws IOException {
        require(der, "der");
        ASN1Sequence record = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der));
        if (record.size() < 3) {
            throw new IOException("an EvidenceRecord has at least version, digestAlgorithms and "
                    + "archiveTimeStampSequence; this one has " + record.size() + " element(s)");
        }
        int version = ASN1Integer.getInstance(record.getObjectAt(0)).intValueExact();
        if (version != VERSION) {
            throw new IOException("RFC 4998 defines version " + VERSION + " only; this record "
                    + "declares " + version);
        }
        List<String> algorithms = new ArrayList<>();
        ASN1Sequence declared = ASN1Sequence.getInstance(record.getObjectAt(1));
        for (int i = 0; i < declared.size(); i++) {
            algorithms.add(AlgorithmIdentifier.getInstance(declared.getObjectAt(i))
                    .getAlgorithm().getId());
        }
        // The sequence is the LAST element: the two optional tagged fields sit between the
        // digest algorithms and it, so counting from the front would read an encryptionInfo as
        // the timestamps on any record that carries one.
        ASN1Sequence sequence = ASN1Sequence.getInstance(record.getObjectAt(record.size() - 1));
        List<List<ArchiveTimeStamp>> chains = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            ASN1Sequence chainSeq = ASN1Sequence.getInstance(sequence.getObjectAt(i));
            List<ArchiveTimeStamp> chain = new ArrayList<>();
            for (int j = 0; j < chainSeq.size(); j++) {
                chain.add(parseArchiveTimeStamp(ASN1Sequence.getInstance(chainSeq.getObjectAt(j))));
            }
            chains.add(chain);
        }
        return new ErsRecord(der.clone(), chains, algorithms);
    }

    private static ArchiveTimeStamp parseArchiveTimeStamp(ASN1Sequence ats) throws IOException {
        // EVERY level, not just the first. Reading only list 0 and discarding the rest means a
        // record can carry [[H(d)], [anything]] and be measured on list 0 alone — §4.3 step 3
        // walks each list in turn and requires the computed parent to be a member of the next.
        List<List<byte[]>> tree = new ArrayList<>();
        byte[] token = null;
        String algorithm = null;
        for (int i = 0; i < ats.size(); i++) {
            ASN1Encodable element = ats.getObjectAt(i);
            if (element instanceof ASN1TaggedObject tagged) {
                if (tagged.getTagNo() == 0) {
                    algorithm = AlgorithmIdentifier.getInstance(tagged, false)
                            .getAlgorithm().getId();
                } else if (tagged.getTagNo() == 2) {
                    ASN1Sequence lists = ASN1Sequence.getInstance(tagged, false);
                    for (int j = 0; j < lists.size(); j++) {
                        ASN1Sequence partial = ASN1Sequence.getInstance(lists.getObjectAt(j));
                        List<byte[]> level = new ArrayList<>();
                        for (int k = 0; k < partial.size(); k++) {
                            level.add(ASN1OctetString.getInstance(partial.getObjectAt(k))
                                    .getOctets());
                        }
                        tree.add(level);
                    }
                }
                continue;
            }
            token = ContentInfo.getInstance(element).getEncoded(ASN1Encoding.DER);
        }
        if (token == null) {
            throw new IOException("an ArchiveTimeStamp with no timeStamp is not one; RFC 4998 "
                    + "makes every other field optional and this one mandatory");
        }
        // §4.1: "If the optional field digestAlgorithm is not present, the digest algorithm of
        // the timestamp MUST be used" — so an absent field is read from the token, not assumed
        // to be SHA-256. Assuming it rejected every valid SHA-384/SHA-512 record.
        return new ArchiveTimeStamp(tree, token,
                algorithm == null ? imprintAlgorithmOf(token) : algorithm);
    }

    /** The imprint algorithm the token itself declares. */
    static String imprintAlgorithmOf(byte[] tokenDer) {
        try {
            return new org.bouncycastle.tsp.TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(tokenDer))
                    .getTimeStampInfo().getMessageImprintAlgOID().getId();
        } catch (Exception e) {
            // Unreadable: SHA-256 is this product's algorithm and the honest default for a
            // record it produced, and a token nobody can read fails the imprint check anyway.
            return SHA256_OID;
        }
    }

    // ---- encoding ----

    private List<List<ArchiveTimeStamp>> deepCopy() {
        List<List<ArchiveTimeStamp>> copy = new ArrayList<>();
        for (List<ArchiveTimeStamp> chain : chains) {
            copy.add(new ArrayList<>(chain));
        }
        return copy;
    }

    private static byte[] encode(List<List<ArchiveTimeStamp>> chains, List<String> algorithms) {
        try {
            ASN1EncodableVector record = new ASN1EncodableVector();
            record.add(new ASN1Integer(VERSION));
            ASN1EncodableVector declared = new ASN1EncodableVector();
            for (String oid : algorithms) {
                declared.add(new AlgorithmIdentifier(new ASN1ObjectIdentifier(oid)));
            }
            record.add(new DERSequence(declared));
            record.add(encodeSequenceObject(chains));
            return new DERSequence(record).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("the evidence record could not be encoded: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The DER of the {@code ArchiveTimeStampSequence} alone — what {@code ha} is computed over.
     *
     * <p>§5.3 step 3 is explicit that the chains are DER encoded "i.e., they contain sequence
     * and length tags", so this is the encoded sequence and not a concatenation of its parts.
     */
    static byte[] encodeSequence(List<List<ArchiveTimeStamp>> chains) {
        try {
            return encodeSequenceObject(chains).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("the timestamp sequence could not be encoded: "
                    + e.getMessage(), e);
        }
    }

    private static DERSequence encodeSequenceObject(List<List<ArchiveTimeStamp>> chains) {
        try {
            ASN1EncodableVector sequence = new ASN1EncodableVector();
            for (List<ArchiveTimeStamp> chain : chains) {
                ASN1EncodableVector chainVector = new ASN1EncodableVector();
                for (ArchiveTimeStamp ats : chain) {
                    chainVector.add(encodeArchiveTimeStamp(ats));
                }
                sequence.add(new DERSequence(chainVector));
            }
            return new DERSequence(sequence);
        } catch (IOException e) {
            throw new IllegalStateException("the timestamp sequence could not be encoded: "
                    + e.getMessage(), e);
        }
    }

    private static DERSequence encodeArchiveTimeStamp(ArchiveTimeStamp ats) throws IOException {
        ASN1EncodableVector vector = new ASN1EncodableVector();
        vector.add(new DERTaggedObject(false, 0,
                new AlgorithmIdentifier(new ASN1ObjectIdentifier(ats.digestAlgorithmOid()))));
        if (!ats.hashTree().isEmpty()) {
            ASN1EncodableVector lists = new ASN1EncodableVector();
            for (List<byte[]> level : ats.hashTree()) {
                ASN1EncodableVector partial = new ASN1EncodableVector();
                for (byte[] value : level) {
                    partial.add(new DEROctetString(value));
                }
                lists.add(new DERSequence(partial));
            }
            vector.add(new DERTaggedObject(false, 2, new DERSequence(lists)));
        }
        vector.add(ContentInfo.getInstance(ASN1Primitive.fromByteArray(ats.timeStampDer())));
        return new DERSequence(vector);
    }

    // ---- shared hashing, so the encoder and the verifier cannot drift ----

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

    /** The JCA name for a digest OID, or null when this build does not know it. */
    static String algorithmNameFor(String oid) {
        return switch (oid == null ? "" : oid) {
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            default -> null;
        };
    }

    static byte[] digest(String oid, byte[] input) {
        String name = algorithmNameFor(oid);
        if (name == null) {
            throw new IllegalArgumentException("this build does not know digest algorithm "
                    + oid + ", so it cannot compute what a token over it should cover");
        }
        try {
            return MessageDigest.getInstance(name).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM does not provide " + name, e);
        }
    }

    private static void require(byte[] value, String name) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(name + " is required and must not be empty; an "
                    + "evidence record with an empty component is not a weaker record, it is one "
                    + "that no verifier can read");
        }
    }
}
