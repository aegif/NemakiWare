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

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An RFC 4998 Evidence Record, built and parsed with ASN.1 primitives (P2-3).
 *
 * <h2>What this is</h2>
 *
 * <p>{@link ErsFormat} recorded the DECISION to use RFC 4998. This produces one. The structure
 * is the specification's, abbreviated to the parts this product can fill honestly:
 *
 * <pre>
 * EvidenceRecord ::= SEQUENCE {
 *     version                  INTEGER { v1(1) },
 *     digestAlgorithms         SEQUENCE OF AlgorithmIdentifier,
 *     cryptoInfos          [0] CryptoInfos OPTIONAL,      -- not produced
 *     encryptionInfo       [1] EncryptionInfo OPTIONAL,   -- not produced
 *     archiveTimeStampSequence ArchiveTimeStampSequence }
 *
 * ArchiveTimeStampSequence ::= SEQUENCE OF ArchiveTimeStampChain
 * ArchiveTimeStampChain    ::= SEQUENCE OF ArchiveTimeStamp
 * ArchiveTimeStamp ::= SEQUENCE {
 *     digestAlgorithm  [0] AlgorithmIdentifier OPTIONAL,
 *     attributes       [1] Attributes OPTIONAL,           -- not produced
 *     reducedHashtree  [2] SEQUENCE OF PartialHashtree OPTIONAL,
 *     timeStamp            ContentInfo }
 * PartialHashtree ::= SEQUENCE OF OCTET STRING
 * </pre>
 *
 * <h2>What the data object is, and why that matters</h2>
 *
 * <p><b>The data object is the CHECKPOINT HASH, not the document.</b> RFC 4998 §4.2 reduces a
 * hash tree by hashing the <i>sorted concatenation</i> of the values in each partial hash tree,
 * with no domain separation. This product's Merkle tree is RFC 6962-style: leaves are hashed
 * with a {@code 0x00} prefix and nodes with {@code 0x01}. Those are different trees. An audit
 * path from ours, walked by an RFC 4998 verifier under RFC 4998's rules, computes a different
 * root and rejects — so emitting our path as a {@code reducedHashtree} would produce a record
 * that looks standard and fails every standard tool.
 *
 * <p>So the reduced hash tree here has one node holding the checkpoint hash, and the timestamp
 * covers exactly that. A standard verifier can check it, and what it checks is true: this
 * checkpoint hash existed by the time in the token. The tie from a document to the checkpoint
 * is this product's own inclusion proof, which travels beside the record and which a standard
 * ERS verifier does not check. {@link #LIMITS} says so, and travels with it.
 *
 * <p>The alternative — reducing our entries under RFC 4998's rules — needs a SECOND root per
 * checkpoint and a second anchor over it, because the token we hold covers the RFC 6962 root.
 * That is a change to the anchoring design and is deliberately not taken here.
 *
 * <h2>Why it is worth producing anyway</h2>
 *
 * <p>The chain is the point. A bare RFC 3161 token proves one thing once; an
 * {@code ArchiveTimeStampChain} is where renewals accumulate, which is what
 * {@link RenewalNeed} exists to demand. {@code TIMESTAMP_RENEWAL} appends to the current chain;
 * {@code HASH_TREE_RENEWAL} starts a new one. Both are operations on this structure.
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md} §8.
 */
public final class ErsRecord {

    /** RFC 4998 §4: the only version defined. */
    public static final int VERSION = 1;

    /**
     * What a reader must not conclude from an evidence record this product produced.
     *
     * <p>Travels with the record wherever it is reported, for the same reason
     * {@link ErsFormat#LIMITS} does: naming a standard is read as conforming to everything a
     * reader associates with it.
     */
    public static final String LIMITS =
            "The data object of this evidence record is a CHECKPOINT HASH of this repository's "
                    + "evidence ledger — not a document. It establishes that that value existed "
                    + "by the time in its timestamp token, and nothing else. Which records were "
                    + "under that checkpoint is shown by this product's own inclusion proof, "
                    + "which travels separately and which a standard RFC 4998 verifier does NOT "
                    + "check. This product also does not verify the timestamp authority's "
                    + "certificate chain or its revocation status: it holds no trust anchors, "
                    + "so the token's signature is carried for a verifier that does.";

    private final byte[] der;
    private final List<List<ArchiveTimeStamp>> chains;

    private ErsRecord(byte[] der, List<List<ArchiveTimeStamp>> chains) {
        this.der = der;
        this.chains = chains;
    }

    /** One archive timestamp: what it covers, and the token over it. */
    public record ArchiveTimeStamp(List<byte[]> reducedHashtreeFirstNode, byte[] timeStampDer) {}

    /** The DER bytes. This is the file that goes in a package. */
    public byte[] der() {
        return der.clone();
    }

    /** The chains, outermost first. Empty is possible only for a record we did not build. */
    public List<List<ArchiveTimeStamp>> chains() {
        return chains;
    }

    /** How many timestamps are in the newest chain — the renewal depth a reader asks about. */
    public int timestampsInCurrentChain() {
        return chains.isEmpty() ? 0 : chains.get(chains.size() - 1).size();
    }

    /**
     * Builds the first evidence record over a checkpoint hash.
     *
     * @param checkpointHash the value the timestamp token covers, as raw bytes
     * @param timeStampTokenDer a DER-encoded RFC 3161 {@code TimeStampToken} (a CMS
     *        {@code ContentInfo}); this is what {@code Rfc3161AnchorTarget} stores as its proof
     */
    public static ErsRecord first(byte[] checkpointHash, byte[] timeStampTokenDer) {
        require(checkpointHash, "checkpointHash");
        require(timeStampTokenDer, "timeStampTokenDer");
        List<List<ArchiveTimeStamp>> chains = new ArrayList<>();
        List<ArchiveTimeStamp> chain = new ArrayList<>();
        chain.add(new ArchiveTimeStamp(List.of(checkpointHash.clone()),
                timeStampTokenDer.clone()));
        chains.add(chain);
        return new ErsRecord(encode(chains), chains);
    }

    /**
     * Appends a timestamp renewal to the newest chain (RFC 4998 §5.2).
     *
     * <p>The renewal timestamps the PREVIOUS token, which is what keeps the original time
     * reachable. Doing it after the old algorithm has broken re-dates the evidence to the
     * renewal — {@link RenewalNeed#limits()} says that, and this method cannot check it: it
     * does not know when the break happened.
     *
     * @param renewalTokenDer a token whose message imprint is over the previous token's bytes
     */
    public ErsRecord withTimestampRenewal(byte[] renewalTokenDer) {
        require(renewalTokenDer, "renewalTokenDer");
        if (chains.isEmpty()) {
            throw new IllegalStateException("there is no chain to renew");
        }
        List<List<ArchiveTimeStamp>> next = deepCopy();
        List<ArchiveTimeStamp> current = next.get(next.size() - 1);
        byte[] previous = current.get(current.size() - 1).timeStampDer();
        current.add(new ArchiveTimeStamp(List.of(previous.clone()), renewalTokenDer.clone()));
        return new ErsRecord(encode(next), next);
    }

    /**
     * Starts a new chain for a hash-tree renewal (RFC 4998 §5.3).
     *
     * <p>A new chain rather than another link, because the digest algorithm changed: the old
     * chain stays as it is and the new one begins from a value computed under the new
     * algorithm. Collapsing the two into one chain would say the new algorithm had been in use
     * all along.
     */
    public ErsRecord withHashTreeRenewal(byte[] newCheckpointHash, byte[] timeStampTokenDer) {
        require(newCheckpointHash, "newCheckpointHash");
        require(timeStampTokenDer, "timeStampTokenDer");
        List<List<ArchiveTimeStamp>> next = deepCopy();
        List<ArchiveTimeStamp> chain = new ArrayList<>();
        chain.add(new ArchiveTimeStamp(List.of(newCheckpointHash.clone()),
                timeStampTokenDer.clone()));
        next.add(chain);
        return new ErsRecord(encode(next), next);
    }

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
        return new ErsRecord(der.clone(), chains);
    }

    private static ArchiveTimeStamp parseArchiveTimeStamp(ASN1Sequence ats) throws IOException {
        List<byte[]> firstNode = new ArrayList<>();
        byte[] token = null;
        for (int i = 0; i < ats.size(); i++) {
            Object element = ats.getObjectAt(i);
            if (element instanceof ASN1TaggedObject tagged) {
                if (tagged.getTagNo() == 2) {
                    ASN1Sequence tree = ASN1Sequence.getInstance(tagged, false);
                    if (tree.size() > 0) {
                        ASN1Sequence partial = ASN1Sequence.getInstance(tree.getObjectAt(0));
                        for (int k = 0; k < partial.size(); k++) {
                            firstNode.add(ASN1OctetString.getInstance(partial.getObjectAt(k))
                                    .getOctets());
                        }
                    }
                }
                continue;
            }
            // The only untagged element is the ContentInfo holding the token.
            token = ContentInfo.getInstance(element).getEncoded(ASN1Encoding.DER);
        }
        if (token == null) {
            throw new IOException("an ArchiveTimeStamp with no timeStamp is not one; RFC 4998 "
                    + "makes every other field optional and this one mandatory");
        }
        return new ArchiveTimeStamp(firstNode, token);
    }

    private List<List<ArchiveTimeStamp>> deepCopy() {
        List<List<ArchiveTimeStamp>> copy = new ArrayList<>();
        for (List<ArchiveTimeStamp> chain : chains) {
            copy.add(new ArrayList<>(chain));
        }
        return copy;
    }

    private static byte[] encode(List<List<ArchiveTimeStamp>> chains) {
        try {
            ASN1EncodableVector record = new ASN1EncodableVector();
            record.add(new ASN1Integer(VERSION));
            // SHA-256, declared once at the record level. Stated rather than left absent: a
            // reader of an evidence record has to know which digest the tree was built with,
            // and RFC 4998 lets the field be a list precisely so a renewal can add to it.
            record.add(new DERSequence(new AlgorithmIdentifier(
                    NISTObjectIdentifiers.id_sha256)));
            ASN1EncodableVector sequence = new ASN1EncodableVector();
            for (List<ArchiveTimeStamp> chain : chains) {
                ASN1EncodableVector chainVector = new ASN1EncodableVector();
                for (ArchiveTimeStamp ats : chain) {
                    chainVector.add(encodeArchiveTimeStamp(ats));
                }
                sequence.add(new DERSequence(chainVector));
            }
            record.add(new DERSequence(sequence));
            return new DERSequence(record).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            // Encoding a structure we just built in memory cannot fail for a reason a caller
            // could act on, and a checked exception here would push a meaningless catch into
            // every call site.
            throw new IllegalStateException("the evidence record could not be encoded: "
                    + e.getMessage(), e);
        }
    }

    private static DERSequence encodeArchiveTimeStamp(ArchiveTimeStamp ats) throws IOException {
        ASN1EncodableVector vector = new ASN1EncodableVector();
        vector.add(new DERTaggedObject(false, 0,
                new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)));
        ASN1EncodableVector partial = new ASN1EncodableVector();
        for (byte[] value : ats.reducedHashtreeFirstNode()) {
            partial.add(new DEROctetString(value));
        }
        vector.add(new DERTaggedObject(false, 2,
                new DERSequence(new DERSequence(partial))));
        vector.add(ContentInfo.getInstance(
                ASN1Primitive.fromByteArray(ats.timeStampDer())));
        return new DERSequence(vector);
    }

    private static void require(byte[] value, String name) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(name + " is required and must not be empty; an "
                    + "evidence record with an empty component is not a weaker record, it is one "
                    + "that no verifier can read");
        }
    }
}
