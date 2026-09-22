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

import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code ANCHORED_CHECKPOINT_V1} — the checks of {@code evidence-profile-v1.md} §11.
 *
 * <h2>What a linked chain does and does not buy</h2>
 *
 * <p>The anchor commits to the anchor target's {@code merkleRoot}, <b>not</b> to its
 * {@code checkpointHash}. So the anchor by itself says nothing about {@code prevCheckpointHash}
 * — the link to earlier periods lives only in {@code checkpoint-chain.json}, which travels
 * inside the package, which is the thing being examined. A holder who can rewrite the ledger
 * can rewrite the chain to agree with it.
 *
 * <p>What survives that: the set of entries under the anchored root was fixed when the anchor
 * was made. Detecting a rewrite of an EARLIER period needs a checkpoint held outside the
 * package — {@code --expected-checkpoint} — and without one the rollback check is
 * {@code NOT_CHECKED} rather than passed.
 */
public final class AnchoredCheckpoint {

    /** The checks this profile will not pass without — §11. */
    public static final List<String> REQUIRED = List.of(
            "chain recompute", "chain linked", "chain forward", "chain ends",
            "anchor commits root");

    private AnchoredCheckpoint() {
    }

    /**
     * @param expectedCheckpointHash a checkpoint hash the caller holds from outside the
     *        package, or null. Null makes the rollback check {@code NOT_PRESENT}: without an
     *        external reference there is nothing to detect a rollback against, and saying so is
     *        the honest answer
     */
    public static List<Outcome.Check> check(Map<String, byte[]> entries,
            String expectedCheckpointHash) {
        List<Outcome.Check> checks = new ArrayList<>();

        Map<String, Object> chainDoc = documentIn(entries, "checkpoint-chain.json");
        Map<String, Object> covering = documentIn(entries, "covering-checkpoint.json");
        Map<String, Object> target = documentIn(entries, "anchor-target-checkpoint.json");

        if (chainDoc == null) {
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.absent(name,
                        "the package carries no checkpoint chain"));
            }
            checks.add(rollback(null, expectedCheckpointHash));
            return checks;
        }

        List<Map<String, Object>> links = linksIn(chainDoc);
        if (links == null) {
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.failed(name,
                        "the checkpoint chain is not a list of links"));
            }
            checks.add(rollback(null, expectedCheckpointHash));
            return checks;
        }
        if (links.isEmpty()) {
            // An empty chain is a FAILURE, not an absence: the file is there and says the walk
            // has no steps, which cannot connect anything to anything.
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.failed(name,
                        "the checkpoint chain is empty, so nothing connects the covering "
                                + "checkpoint to an anchored one"));
            }
            checks.add(rollback(links, expectedCheckpointHash));
            return checks;
        }

        checks.add(chainRecompute(links));
        checks.add(chainLinked(links));
        checks.add(chainForward(links));
        checks.add(chainEnds(links, covering, target));
        checks.add(anchorCommitsRoot(entries, links));
        checks.add(rollback(links, expectedCheckpointHash));
        return checks;
    }

    static Outcome.Check chainRecompute(List<Map<String, Object>> links) {
        for (int i = 0; i < links.size(); i++) {
            Map<String, Object> link = links.get(i);
            for (String field : List.of("domain", "fromSequence", "toSequence", "merkleRoot",
                    "prevCheckpointHash", "createdAt", "checkpointHash")) {
                if (!link.containsKey(field)) {
                    return Outcome.Check.absent("chain recompute",
                            "link " + i + " omits " + field + ", so its hash cannot be "
                                    + "recomputed");
                }
            }
            String recomputed = Canonical.hash("LEDGER_CHECKPOINT_V1", link.get("domain"),
                    link.get("fromSequence"), link.get("toSequence"), link.get("merkleRoot"),
                    link.get("prevCheckpointHash"), link.get("createdAt"));
            if (!recomputed.equals(link.get("checkpointHash"))) {
                return Outcome.Check.failed("chain recompute",
                        "link " + i + " hashes to " + recomputed + " and records "
                                + link.get("checkpointHash") + ": its fields and its hash "
                                + "disagree, which is what a rewrite under an untouched hash "
                                + "looks like");
            }
        }
        return Outcome.Check.passed("chain recompute");
    }

    static Outcome.Check chainLinked(List<Map<String, Object>> links) {
        for (int i = 1; i < links.size(); i++) {
            Object prior = links.get(i - 1).get("checkpointHash");
            Object names = links.get(i).get("prevCheckpointHash");
            if (prior == null || !prior.equals(names)) {
                return Outcome.Check.failed("chain linked",
                        "link " + i + " names " + names + " as its predecessor and link "
                                + (i - 1) + " hashes to " + prior);
            }
        }
        return Outcome.Check.passed("chain linked");
    }

    static Outcome.Check chainForward(List<Map<String, Object>> links) {
        for (int i = 1; i < links.size(); i++) {
            if (!(links.get(i - 1).get("toSequence") instanceof Long prior)
                    || !(links.get(i).get("toSequence") instanceof Long here)) {
                return Outcome.Check.absent("chain forward",
                        "link " + i + " or the one before it records no end sequence");
            }
            // Strictly increasing. Two checkpoints ending at the same sequence are two answers
            // for one period, and taking either is choosing which ledger to believe.
            if (here <= prior) {
                return Outcome.Check.failed("chain forward",
                        "link " + i + " ends at " + here + ", which does not come after link "
                                + (i - 1) + "'s " + prior);
            }
        }
        return Outcome.Check.passed("chain forward");
    }

    static Outcome.Check chainEnds(List<Map<String, Object>> links, Map<String, Object> covering,
            Map<String, Object> target) {
        if (covering == null || target == null) {
            return Outcome.Check.absent("chain ends",
                    "the package carries no covering checkpoint or no anchor target, so there "
                            + "is nothing to check the chain's ends against");
        }
        // The documents are recomputed FIRST. Comparing a stated hash with a key that is not
        // there reports "the walk does not begin where the entry was proved" for a document
        // that simply omits the field — §5 calls that NOT_PRESENT, and the recompute below
        // says so (Codex, fourth review, P2).
        Outcome.Check coveringSelf = selfConsistent("chain ends", covering, "covering checkpoint");
        if (coveringSelf != null) {
            return coveringSelf;
        }
        Outcome.Check targetSelf = selfConsistent("chain ends", target, "anchor target checkpoint");
        if (targetSelf != null) {
            return targetSelf;
        }
        Object first = links.get(0).get("checkpointHash");
        Object last = links.get(links.size() - 1).get("checkpointHash");
        if (!java.util.Objects.equals(first, covering.get("checkpointHash"))) {
            return Outcome.Check.failed("chain ends",
                    "the chain starts at " + first + " and the covering checkpoint is "
                            + covering.get("checkpointHash") + ", so the walk does not begin "
                            + "where the entry was proved");
        }
        if (!java.util.Objects.equals(last, target.get("checkpointHash"))) {
            return Outcome.Check.failed("chain ends",
                    "the chain ends at " + last + " and the anchor target is "
                            + target.get("checkpointHash") + ", so the walk does not reach what "
                            + "was anchored");
        }
        return Outcome.Check.passed("chain ends");
    }

    /** The failure when {@code document}'s own fields do not hash to its checkpointHash. */
    private static Outcome.Check selfConsistent(String name, Map<String, Object> document,
            String what) {
        for (String field : List.of("domain", "fromSequence", "toSequence", "merkleRoot",
                "prevCheckpointHash", "createdAt", "checkpointHash")) {
            if (!document.containsKey(field)) {
                return Outcome.Check.absent(name, "the " + what + " omits " + field
                        + ", so its hash cannot be recomputed");
            }
        }
        String recomputed = Canonical.hash("LEDGER_CHECKPOINT_V1", document.get("domain"),
                document.get("fromSequence"), document.get("toSequence"),
                document.get("merkleRoot"), document.get("prevCheckpointHash"),
                document.get("createdAt"));
        if (!recomputed.equals(document.get("checkpointHash"))) {
            return Outcome.Check.failed(name, "the " + what + "'s fields hash to " + recomputed
                    + " and it records " + document.get("checkpointHash")
                    + ", so the hash it presents is not the hash of what it says");
        }
        return null;
    }

    /**
     * The anchor material commits to the anchor target's Merkle root.
     *
     * <p>Reads the manifest's record of what each rung holds, then READS the material it can:
     * an RFC 3161 token is parsed and its imprint compared with the CHAIN'S last Merkle root.
     * The imprint IS that root — the root is already a SHA-256 digest and the product
     * timestamps its bytes — and the token's own signature is verified against the certificate
     * it carries. This javadoc said "SHA-256 over the root's UTF-8 bytes, which is what the
     * product anchors" until both reviewers pointed out that the code below had stopped saying
     * so (2026-09-22). Until 2026-09-22
     * the material was only checked for presence and the outcome was always UNAVAILABLE
     * ({@code ANCHOR_NOT_PARSED}), so no package could reach VERIFIED at P2 (release condition
     * 3). OTS and ERS material stays unread here — their own profiles read them — and a package
     * whose only material is of those kinds is still UNAVAILABLE, not passed. P3 repeats the
     * imprint comparison under its own name because P3 also checks WHO signed; the two agree
     * by construction.
     */
    static Outcome.Check anchorCommitsRoot(Map<String, byte[]> entries,
            List<Map<String, Object>> links) {
        Map<String, Object> manifest = documentIn(entries, "bundle-manifest.json");
        if (manifest == null) {
            return Outcome.Check.absent("anchor commits root",
                    "the package carries no bundle manifest, so what each rung holds is unknown");
        }
        Object anchors = manifest.get("anchors");
        if (!(anchors instanceof List<?> rungs) || rungs.isEmpty()) {
            return Outcome.Check.absent("anchor commits root",
                    "the manifest records no anchor rungs");
        }
        List<String> present = new ArrayList<>();
        List<String> rfc3161 = new ArrayList<>();
        for (Object raw : rungs) {
            if (raw instanceof Map<?, ?> rung && "PRESENT".equals(rung.get("state"))) {
                Object path = rung.get("path");
                if (path == null) {
                    return Outcome.Check.failed("anchor commits root",
                            "a rung is recorded PRESENT and names no file");
                }
                if (!hasFile(entries, String.valueOf(path))) {
                    return Outcome.Check.failed("anchor commits root",
                            "the manifest records " + path + " as present and the package does "
                                    + "not carry it");
                }
                present.add(String.valueOf(path));
                // The RFC 3161 rung is the one the manifest CALLS one, at the path §4.2 names.
                // Reading anything ending in rfc3161.der, whatever kind claimed it, let a rung
                // marked OPENTIMESTAMPS carry a matching token at a decoy path and pass here
                // while P3 — which reads anchors/rfc3161.der only — found no token at all
                // (Codex, third review, P2).
                if ("RFC3161_TSA".equals(rung.get("kind"))
                        && "anchors/rfc3161.der".equals(String.valueOf(path))) {
                    rfc3161.add(String.valueOf(path));
                }
            }
        }
        if (present.isEmpty()) {
            // Every rung absent. NOT a failure — an unanchored checkpoint is a legitimate
            // state — but it is not this profile either.
            return Outcome.Check.absent("anchor commits root",
                    "no rung carries anchor material, so nothing external commits to this "
                            + "checkpoint");
        }
        // The CHAIN'S root, not the target document's. chainRecompute has already recomputed
        // every link's hash from its own fields, so this value is one the walk established;
        // the target document's field is checked against it by chainEnds (Codex, third review).
        Object root = links.get(links.size() - 1).get("merkleRoot");
        if (!(root instanceof String merkleRoot)) {
            return Outcome.Check.absent("anchor commits root",
                    "the chain's last link records no Merkle root, so there is nothing the "
                            + "material could commit to");
        }
        // The imprint IS the root (§11, §12): the Merkle root is already a SHA-256 digest and
        // what gets timestamped is its BYTES. The first version of this check hashed the hex
        // string a second time — copying the same mistake P3 had — which refused every token
        // the product produces (third review, P1). A fixture that made its tokens the same
        // wrong way agreed with it, which is why both were green.
        String expected = merkleRoot;
        List<String> committing = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        for (String path : present) {
            if (!rfc3161.contains(path)) {
                // OTS and ERS material is read by their own profiles (P4 / P5); Atlas is a
                // catalogue reference, not a commitment. Recorded as unread, not as passed.
                unread.add(path);
                continue;
            }
            TimeStampToken token;
            try {
                token = new TimeStampToken(new CMSSignedData(bytesOf(entries, path)));
            } catch (Exception notAToken) {
                // A file recorded as a token and not parsing as one is a FINDING about the
                // package, the same reading P3 makes of it — not "unknown".
                return Outcome.Check.failed("anchor commits root",
                        path + " is recorded as an RFC 3161 token and does not parse as one: "
                                + notAToken.getMessage());
            }
            String algorithm = String.valueOf(token.getTimeStampInfo().getMessageImprintAlgOID());
            if (!SHA256_OID.equals(algorithm)) {
                // A digest this version does not compute is one it has not checked.
                return Outcome.Check.unavailable("anchor commits root", "UNKNOWN_ALGORITHM",
                        path + " carries an imprint under " + algorithm + " and this version "
                                + "computes SHA-256 only, so whether it commits to the root "
                                + "was not established");
            }
            String imprint = Canonical.hex(token.getTimeStampInfo().getMessageImprintDigest());
            if (!imprint.equals(expected)) {
                return Outcome.Check.failed("anchor commits root",
                        path + " is over " + imprint + " and the chain's Merkle root hashes to "
                                + expected + ", so the anchor commits to something else");
            }
            // "An anchor COMMITS" is a claim about a signature, and reading a field out of an
            // unverified structure is not one: anyone can write a TSTInfo. The token's own
            // signature is verified here against the certificate it carries — WHO that
            // certificate belongs to is P3's question, and this check does not answer it
            // (Codex, third review, P1).
            org.bouncycastle.cert.X509CertificateHolder signer = null;
            for (Object held : token.getCertificates().getMatches(token.getSID())) {
                signer = (org.bouncycastle.cert.X509CertificateHolder) held;
                break;
            }
            // The SHARED classification (TokenSignature). Deciding here what a failure meant
            // is how one token came to exit 3 at this profile and 2 at P3: the two profiles
            // disagreed about one input, first about an absent certificate and then about an
            // algorithm this JVM has no provider for (both reviews, fourth and fifth rounds).
            Outcome.Check signature =
                    TokenSignature.verify("anchor commits root", path, token, signer);
            if (signature.outcome() != Outcome.PASSED) {
                return signature;
            }
            committing.add(path);
        }
        if (committing.isEmpty()) {
            // The material is there and this version cannot read what it commits to.
            // UNAVAILABLE, not PASSED: saying the anchor commits to the root would assert
            // something no check here performed.
            return Outcome.Check.unavailable("anchor commits root", "ANCHOR_NOT_PARSED",
                    "the package carries anchor material (" + unread + ") that this profile does "
                            + "not read. Whether it commits to " + merkleRoot
                            + " is therefore UNKNOWN, not established");
        }
        return Outcome.Check.passed("anchor commits root", committing + " commit(s) to "
                + merkleRoot + (unread.isEmpty() ? "" : "; " + unread + " not read by this "
                + "profile"));
    }

    /** SHA-256, the one imprint algorithm this version computes (id-sha256). */
    static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    /** Delegated to {@link Section}, which is the ONE place that excludes payload. */
    private static byte[] bytesOf(Map<String, byte[]> entries, String relative) {
        byte[] found = Section.fileIn(entries, relative);
        return found == null ? new byte[0] : found;
    }

    static Outcome.Check rollback(List<Map<String, Object>> links, String expected) {
        if (expected == null) {
            // NOT_PRESENT, and the detail says why. Without a checkpoint held OUTSIDE the
            // package there is nothing a rollback could be detected against — the package's own
            // chain agrees with itself by construction.
            return Outcome.Check.absent("rollback",
                    "no expected checkpoint was supplied. A rollback can only be detected "
                            + "against a checkpoint held outside the package; the package's own "
                            + "chain agrees with itself whatever it holds");
        }
        if (links == null || links.isEmpty()) {
            return Outcome.Check.failed("rollback",
                    "an expected checkpoint was supplied and the package carries no chain to "
                            + "look for it in");
        }
        for (Map<String, Object> link : links) {
            if (expected.equals(link.get("checkpointHash"))) {
                return Outcome.Check.passed("rollback");
            }
        }
        return Outcome.Check.failed("rollback",
                "the checkpoint " + expected + " is not on this package's chain, so the package "
                        + "presents a history that does not include what the holder already saw");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> linksIn(Map<String, Object> chainDoc) {
        Object links = chainDoc.get("links");
        if (!(links instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) {
                return null;
            }
            out.add((Map<String, Object>) raw);
        }
        return out;
    }

    /**
     * Delegated to {@link Section} too.
     *
     * <p>Five copies were folded into Section and TWO were missed — this one and
     * {@code bytesOf}. With them still scanning, a substituted token placed in the PAYLOAD and
     * first in the zip was read by P2 while P3 read the real one: the same name, two answers,
     * and the composed P2 verdict came back VERIFIED (subagent, eighth review, P1, measured).
     */
    private static boolean hasFile(Map<String, byte[]> entries, String relative) {
        return Section.fileIn(entries, relative) != null;
    }

    /** Delegated to {@link Section}: one lookup, payload excluded, every parse failure caught. */
    private static Map<String, Object> documentIn(Map<String, byte[]> entries, String name) {
        return Section.documentIn(entries, name);
    }
}
