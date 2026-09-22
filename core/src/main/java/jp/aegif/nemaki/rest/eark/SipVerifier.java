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
package jp.aegif.nemaki.rest.eark;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks an exported SIP without asking the repository that made it (P4-1).
 *
 * <h2>What "without asking" has to mean</h2>
 *
 * <p>A verification that phones home establishes nothing an operator could not have got by
 * asking politely. So every check here runs on the ZIP alone, with SHA-256 and the rules written
 * down in {@code docs/design/p3-1-eark-sip.md} §5 — no database, no network, no NemakiWare
 * service. That is also why the two rules it needs (the Merkle leaf/node prefixes, and the
 * message digest) are stated in the design document rather than only living in code: a third
 * party has to be able to reimplement this, and a verifier only we can build is not independent.
 *
 * <h2>What it can and cannot say</h2>
 *
 * <p>It can say that the payload bytes hash to the digest the package records, and that the audit
 * path in the package leads from the entry's leaf to the checkpoint's Merkle root. Both are real
 * and both are checkable by anyone.
 *
 * <p>It cannot say the checkpoint was not rewritten — that needs the checkpoint hash to exist
 * outside the repository's own database, which is what an external anchor is for and what this
 * package does not yet carry. It cannot say the capture was complete or its metadata true. And
 * it cannot say the record is genuine: a package built from a tampered repository verifies
 * perfectly, because everything in it came from that repository. <b>What it establishes is
 * internal consistency, not truth.</b>
 *
 * <p>Design: {@code docs/design/p3-1-eark-sip.md} §5.
 */
public final class SipVerifier {

    /** Matches {@code MerkleTree}. Stated here too, because a verifier must not import it. */
    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;

    private SipVerifier() {
    }

    /** One thing that was checked, and how it came out. */
    public record Check(String name, Outcome outcome, String detail) {}

    /** Deliberately four values: "could not check" is not "failed" and not "passed". */
    public enum Outcome {
        /** Checked and correct. */
        PASSED,
        /** Checked and wrong. */
        FAILED,
        /** The package does not carry what this check needs. Says nothing either way. */
        NOT_PRESENT,
        /** The check could not be carried out. Says nothing either way. */
        UNAVAILABLE
    }

    /**
     * What the whole set of checks amounts to. Three values, because two cannot carry it:
     * "we found something wrong" and "we could not tell" are different answers and a reader
     * acts differently on each.
     */
    public enum Verdict {
        /** Every REQUIRED check ran and passed. */
        VERIFIED,
        /** A check ran and found the package inconsistent. */
        FAILED,
        /** Something required was absent, unreadable or unsupported. Not a finding either way. */
        INDETERMINATE
    }

    /**
     * The checks this verifier will not call a package verified without.
     *
     * <p>Both of the substantive checks are required: a payload whose digest matches says
     * nothing about whether the audit path ties it to a checkpoint, and an audit path that
     * resolves says nothing about whether the bytes are the ones it covers. Either alone is
     * half a sentence.
     */
    private static final List<String> REQUIRED_CHECKS = List.of("payload digest", "audit path");

    /** Everything checked, plus what the set of it amounts to. */
    public record Result(List<Check> checks, String limits) {

        /**
         * The verdict for the set.
         *
         * <p>It used to be "at least one check passed and none failed", which promoted a
         * package whose audit path was NOT_PRESENT to success on the strength of its payload
         * digest alone. That is the failure this whole verifier exists to prevent, one level
         * up: absence read as assurance.
         */
        public Verdict verdict() {
            // Composed by ProfileVerdict so the rule has ONE definition. The profile vectors
            // measure that method, and a second copy here would let the two drift — which is
            // the defect R52 was, in a place where drifting means a package reads as verified
            // in one layer and indeterminate in the next.
            List<Outcome> all = checks.stream().map(Check::outcome).toList();
            List<Outcome> required = REQUIRED_CHECKS.stream()
                    .map(name -> checks.stream()
                            .filter(c -> name.equals(c.name()))
                            .map(Check::outcome)
                            // A required check the package does not carry is NOT_PRESENT, not
                            // absent from the list: an empty required list means "nothing was
                            // required", which is a different and much weaker statement.
                            .findFirst().orElse(Outcome.NOT_PRESENT))
                    .toList();
            return jp.aegif.nemaki.evidence.ProfileVerdict.of(all, required);
        }

        /**
         * Kept for the callers that read a boolean, and true for exactly one verdict.
         *
         * <p>Not "not FAILED": that is how INDETERMINATE becomes success.
         */
        public boolean allPassed() {
            return verdict() == Verdict.VERIFIED;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("verified", allPassed());
            body.put("verdict", verdict().name());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Check check : checks) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("check", check.name());
                row.put("outcome", check.outcome().name());
                row.put("detail", check.detail());
                rows.add(row);
            }
            body.put("checks", rows);
            body.put("limits", limits);
            return body;
        }
    }

    /** What a passing result does NOT establish. Travels with every result. */
    public static final String LIMITS =
            "These checks establish that the package is INTERNALLY CONSISTENT: the bytes hash "
                    + "to the digest recorded beside them, and the audit path leads to the "
                    + "Merkle root it claims. They do NOT establish that the record is genuine. "
                    + "Everything checked here came out of the same repository, so a package "
                    + "built from a tampered one verifies perfectly. Independence needs the "
                    + "checkpoint hash to exist somewhere that repository does not control — an "
                    + "external anchor — and this package does not carry one.";

    /** Runs every check this package supports. */
    public static Result verify(Path sip) {
        List<Check> checks = new ArrayList<>();
        Map<String, byte[]> entries;
        try {
            entries = read(sip);
        } catch (Exception e) {
            checks.add(new Check("package readable", Outcome.UNAVAILABLE,
                    "the package could not be read: " + e.getMessage()));
            return new Result(List.copyOf(checks), LIMITS);
        }
        if (entries.isEmpty()) {
            // A file that is not a ZIP does not make ZipInputStream throw — it simply yields no
            // entries. Left alone, that came out as two NOT_PRESENT checks, i.e. "there was
            // nothing to check", which is a statement about a package. This is a statement
            // about a FILE: it is not one.
            checks.add(new Check("package readable", Outcome.UNAVAILABLE,
                    "the file contains no entries, so it is not a readable package. Nothing "
                            + "about any record is established either way."));
            return new Result(List.copyOf(checks), LIMITS);
        }
        checks.add(payloadDigestCheck(entries));
        checks.add(auditPathCheck(entries));
        return new Result(List.copyOf(checks), LIMITS);
    }

    /**
     * Do the packaged bytes hash to the digest PREMIS records for them?
     *
     * <p>The strongest check available here, and the only one that needs nothing but SHA-256.
     */
    private static Check payloadDigestCheck(Map<String, byte[]> entries) {
        String premis = textOf(entries, "premis.xml");
        if (premis == null) {
            int matches = countMatching(entries, "premis.xml");
            if (matches > 1) {
                // Ambiguity is UNAVAILABLE, not NOT_PRESENT: the package HAS fixity metadata
                // and this verifier cannot tell which document is about the payload beside it.
                // Picking one would be checking the bytes against a digest chosen by zip order.
                return new Check("payload digest", Outcome.UNAVAILABLE,
                        "the package carries " + matches + " PREMIS documents and this verifier "
                                + "cannot tell which one describes the payload. Choosing by "
                                + "path order would check the bytes against whichever happened "
                                + "to be written first");
            }
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "the package carries no PREMIS document");
        }
        // Read as XML, and with the same rules the independent verifier applies
        // (evidence-verifier-core's PackageIntegrity.payloadFixity and Premis). An operator
        // reaching THIS endpoint and a receiving organisation running the CLI must not get
        // different answers about one file: this method used to take the first
        // "<premis:messageDigest>" it could find as text and to assume SHA-256 when no
        // algorithm was stated, so an adversarial PREMIS that the CLI answered UNAVAILABLE was
        // answered PASSED here (subagent, fifth review, P2).
        List<String> digests;
        List<String> algorithms;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // No DTD, no entity resolution: a package is untrusted input and this runs inside
            // the product.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(premis)));
            digests = premisTexts(document.getDocumentElement(), "messageDigest");
            algorithms = premisTexts(document.getDocumentElement(), "messageDigestAlgorithm");
        } catch (Exception notXml) {
            return new Check("payload digest", Outcome.UNAVAILABLE,
                    "the package presents a PREMIS document this verifier could not read as "
                            + "XML (" + notXml.getMessage() + "), so nothing about the bytes is "
                            + "established either way.");
        }
        if (digests.size() > 1) {
            return new Check("payload digest", Outcome.UNAVAILABLE,
                    "the PREMIS records " + digests.size() + " message digests and this "
                            + "verifier cannot tell which one describes the payload. Taking the "
                            + "first would check the bytes against whichever was written first.");
        }
        String recorded = digests.isEmpty() ? null : digests.get(0);
        if (recorded == null || recorded.isBlank()) {
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "PREMIS records no message digest for this object, so there is nothing to "
                            + "check the bytes against. That is a gap in what was captured, not "
                            + "a failure of this check.");
        }
        if (algorithms.size() > 1) {
            return new Check("payload digest", Outcome.UNAVAILABLE,
                    "the PREMIS records " + algorithms.size() + " digest algorithms for one "
                            + "digest, so which function produced it is not stated.");
        }
        String algorithm = algorithms.isEmpty() ? null : algorithms.get(0);
        if (algorithm == null || algorithm.isBlank()) {
            // NOT an assumption of SHA-256. It happens to be right for packages this product
            // writes, which is no reason to accept it from someone else.
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "PREMIS records a digest and no algorithm, so which function produced it "
                            + "is not stated and nothing here can recompute it.");
        }
        if (!"SHA-256".equalsIgnoreCase(algorithm.trim())) {
            return new Check("payload digest", Outcome.UNAVAILABLE,
                    "the digest is recorded as " + algorithm + ", which this verifier does not "
                            + "compute. Nothing about the bytes is established either way.");
        }
        List<Map.Entry<String, byte[]>> payloads = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            // The payload lives under representations/<id>/data. Metadata and METS do not.
            if (entry.getKey().contains("/representations/") && entry.getKey().contains("/data/")
                    && !entry.getKey().endsWith("/")) {
                payloads.add(entry);
            }
        }
        if (payloads.isEmpty()) {
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "the package carries no payload file under a representation");
        }
        if (payloads.size() > 1) {
            // ONE recorded digest cannot describe two payloads, and taking whichever of them
            // matches would let a second, uncommitted file ride along inside a package this
            // endpoint calls verified. The independent verifier answers AMBIGUOUS_PAYLOAD for
            // exactly this, and an operator reaches THIS endpoint far more often (subagent,
            // sixth review, P2 — the PREMIS reading was aligned and this was not).
            return new Check("payload digest", Outcome.UNAVAILABLE,
                    "the package carries " + payloads.size() + " payload files and PREMIS "
                            + "records one digest, so the one-to-one relationship it requires "
                            + "does not hold. Checked: "
                            + payloads.stream().map(Map.Entry::getKey).toList());
        }
        for (Map.Entry<String, byte[]> payload : payloads) {
            String computed = sha256Hex(payload.getValue());
            if (computed.equalsIgnoreCase(recorded.trim())) {
                return new Check("payload digest", Outcome.PASSED,
                        "the bytes of " + payload.getKey() + " hash to the digest PREMIS "
                                + "records (" + computed + ")");
            }
        }
        // Every payload was hashed and none matched. Named as a mismatch, not as "not found":
        // the package DOES carry bytes and a digest, and they disagree.
        return new Check("payload digest", Outcome.FAILED,
                "no packaged payload hashes to the recorded digest " + recorded.trim()
                        + ". Checked: " + payloads.stream().map(Map.Entry::getKey).toList()
                        + ". That is not by itself evidence of tampering — a re-packaged or "
                        + "converted payload produces the same result — but the bytes in this "
                        + "package are not the bytes the digest was taken over.");
    }

    /**
     * Does the audit path lead from the entry's leaf to the checkpoint's Merkle root?
     *
     * <p>Recomputed here rather than trusted, using the leaf/node prefixes the design document
     * states. A path that "verifies" because we accepted the root as given would check nothing.
     */
    private static Check auditPathCheck(Map<String, byte[]> entries) {
        String evidence = textOf(entries, "nemaki-evidence.json");
        if (evidence == null) {
            return new Check("audit path", Outcome.NOT_PRESENT,
                    "the package carries no evidence package");
        }
        Map<String, Object> document;
        try {
            document = readJsonObject(evidence);
        } catch (Exception malformed) {
            return new Check("audit path", Outcome.UNAVAILABLE,
                    "the evidence package is not readable JSON (" + malformed.getMessage()
                            + "). Nothing about the entry's inclusion is established either way.");
        }
        Object proofValue = document.get("inclusionProof");
        if (!(proofValue instanceof Map)) {
            return noProofCheck(document, proofValue);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> proof = (Map<String, Object>) proofValue;
        String leaf = asString(proof.get("leafHash"));
        String root = asString(proof.get("merkleRoot"));
        if (leaf == null || root == null) {
            // The package usually says WHY there is no path, and it may write that reason
            // INSIDE the proof object, beside it, or both — so the same rule reads both places
            // (review, 2026-09-20).
            Check reason = reasonFor(document, proof);
            if (reason != null) {
                return reason;
            }
            // Both fields are described, each in its own words. Naming only the first problem
            // said "the rest is fine" about a field that might be unusable too — the same
            // conflation one level down (review, 2026-09-20).
            return new Check("audit path", Outcome.UNAVAILABLE,
                    "the evidence package's inclusion proof cannot be read: "
                            + fieldState(proof, "leafHash") + ", "
                            + fieldState(proof, "merkleRoot")
                            + ". Nothing about the entry's inclusion is established either way.");
        }
        if (!proof.containsKey("auditPath")) {
            // An absent path walked as an empty one compares leaf(leafHash) with merkleRoot
            // directly, so a package carrying a leaf and a root it computed FROM that leaf comes
            // back PASSED without carrying a proof of anything. "There is no path" is not "the
            // path is empty" (found by review, 2026-09-19).
            return new Check("audit path", Outcome.NOT_PRESENT,
                    "the evidence package names a leaf and a root but carries no auditPath, so "
                            + "there is nothing to walk. A root that equals the leaf's own hash "
                            + "says only that the two were written together.");
        }
        if (!(proof.get("auditPath") instanceof List)) {
            return new Check("audit path", Outcome.UNAVAILABLE,
                    "the auditPath could not be read: it is not a JSON array. Nothing about the "
                            + "entry's inclusion is established either way.");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object element : (List<?>) proof.get("auditPath")) {
            if (!(element instanceof Map)) {
                return new Check("audit path", Outcome.UNAVAILABLE,
                        "the auditPath could not be read: a step is not an object. Nothing about "
                                + "the entry's inclusion is established either way.");
            }
            Map<?, ?> step = (Map<?, ?>) element;
            String sibling = asString(step.get("siblingHash"));
            Object side = step.get("siblingIsLeft");
            if (sibling == null || !(side instanceof Boolean)) {
                // Dropping the step silently shortens the path, lands on a different root and
                // reports FAILED — "the entry was not in that span" — about a package this
                // verifier did not manage to read.
                return new Check("audit path", Outcome.UNAVAILABLE,
                        "the auditPath could not be read: a step "
                                + (sibling == null ? "carries no readable siblingHash"
                                        : "does not say which side its sibling is on")
                                + ". Nothing about the entry's inclusion is established "
                                + "either way.");
            }
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("siblingHash", sibling);
            read.put("siblingIsLeft", side);
            steps.add(read);
        }
        if (steps.isEmpty()) {
            // An empty path makes the check arithmetic-free: it would compare leaf(leafHash)
            // with merkleRoot, and BOTH are values the package supplies. A checkpoint that
            // sealed a single entry genuinely produces this shape (MerkleTree.root of one leaf
            // IS that leaf's hash), so the package is not wrong — but nothing inside it tells
            // the two apart, and the first version reported the fabricable one as PASSED.
            // Whoever holds the checkpoint can settle it in one look; this verifier cannot.
            return new Check("audit path", Outcome.UNAVAILABLE,
                    "the auditPath is empty. That is what a checkpoint which sealed a SINGLE "
                            + "entry produces — and it is also what a package gets by writing a "
                            + "leaf and the hash of that same leaf as the root. Both values come "
                            + "from this package, so nothing here separates them. Settling it "
                            + "needs the checkpoint's own span, which this package does not "
                            + "carry.");
        }
        // The leaf hash is applied FIRST. `leafHash` in the proof is the entry's own hash, and
        // the tree is built over hashLeaf(entryHash) — walking the path from the raw value
        // would report every genuine package as broken, which is the failure mode a verifier
        // can least afford.
        String current = leaf(leaf);
        for (Map<String, Object> step : steps) {
            String sibling = String.valueOf(step.get("siblingHash"));
            boolean siblingIsLeft = Boolean.TRUE.equals(step.get("siblingIsLeft"));
            current = siblingIsLeft ? node(sibling, current) : node(current, sibling);
        }
        if (current.equalsIgnoreCase(root)) {
            return new Check("audit path", Outcome.PASSED,
                    "the audit path leads from the entry's leaf to the Merkle root the "
                            + "checkpoint claims (" + root + ")");
        }
        return new Check("audit path", Outcome.FAILED,
                "the audit path leads to " + current + ", not to the claimed root " + root
                        + ". The entry named in this package was not in the span that "
                        + "checkpoint sealed.");
    }

    // ---- the two rules, restated ----

    /** {@code SHA-256(0x01 || left || right)} as hex. Mirrors {@code MerkleTree.node}. */
    static String node(String left, String right) {
        return sha256(NODE_PREFIX, (left == null ? "" : left) + (right == null ? "" : right));
    }

    /** {@code SHA-256(0x00 || value)} as hex. Mirrors {@code MerkleTree.leaf}. */
    static String leaf(String value) {
        return sha256(LEAF_PREFIX, value == null ? "" : value);
    }

    private static String sha256(byte prefix, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    // ---- reading the package ----

    private static Map<String, byte[]> read(Path sip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(sip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * The one entry whose path ends with {@code suffix}, or null.
     *
     * <p><b>Null when there is more than one, too.</b> The first version returned whichever the
     * zip iteration reached first, so a package carrying two PREMIS documents — a derived copy
     * brings its own — was verified against an arbitrary one of them, and which one depended on
     * the order the entries happened to be written in. That is a verifier choosing the evidence
     * it likes; {@link #countMatching} lets the caller say so instead.
     */
    private static String textOf(Map<String, byte[]> entries, String suffix) {
        String found = null;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                if (found != null) {
                    return null;
                }
                found = new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return found;
    }

    /** How many entries end with {@code suffix}, so absence and ambiguity are told apart. */
    private static int countMatching(Map<String, byte[]> entries, String suffix) {
        int n = 0;
        for (String key : entries.keySet()) {
            if (key.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(close, start + open.length());
        return end < 0 ? null : text.substring(start + open.length(), end);
    }

    /**
     * The PREMIS namespaces, so a prefix bound to something else is not read as PREMIS.
     *
     * <p>v2 as well as v3 — the twin of {@code Premis.NAMESPACES} in the independent verifier.
     * Reading only v3 turned a PREMIS 2.x document into "no digest recorded", which the string
     * matching this replaced did not do.
     */
    private static final java.util.Set<String> PREMIS_NAMESPACES = java.util.Set.of(
            "http://www.loc.gov/premis/v3", "info:lc/xmlns/premis-v2");

    /**
     * The text of every PREMIS element with this local name, in document order.
     *
     * <p>Local name, never prefix: a document may bind the PREMIS namespace to any prefix it
     * likes, and matching the literal {@code "<premis:messageDigest>"} counted two digests as
     * one. The twin of this method is {@code Premis} in {@code evidence-verifier-core}, which
     * that module cannot share because it may not depend on this one.
     */
    private static List<String> premisTexts(org.w3c.dom.Element element, String localName) {
        List<String> found = new ArrayList<>();
        collectPremis(element, localName, found);
        return found;
    }

    private static void collectPremis(org.w3c.dom.Element element, String localName,
            List<String> found) {
        if (element == null) {
            return;
        }
        String name = element.getLocalName() == null ? element.getNodeName()
                : element.getLocalName();
        String namespace = element.getNamespaceURI();
        if (localName.equals(name)
                && (namespace == null || PREMIS_NAMESPACES.contains(namespace))) {
            found.add(element.getTextContent());
        }
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                collectPremis(child, localName, found);
            }
        }
    }

    /**
     * The evidence document, read with a JSON parser.
     *
     * <p>This used to be hand-rolled string scanning, on the argument that a verifier a third
     * party is meant to reimplement should not need our object mapper. That argument was about
     * the wrong dependency. The independence that matters is {@link MerkleTree}: the Merkle rule
     * is RESTATED here so the verifier does not agree with the product by construction. Reading
     * JSON is not part of the specification — a third party uses whatever parser they have.
     *
     * <p>What the hand-rolled version cost: a P1 in three consecutive review rounds, each a
     * different way of answering "could not read that" with a value. The first key with a
     * matching name anywhere in the document won, so a proof nested under {@code inclusionProof}
     * could be shadowed by loose keys beside it; a non-string value returned the NEXT KEY'S
     * NAME; {@code true} was matched by prefix; a {@code null} in the step array was absorbed
     * into its neighbour; and escapes were not decoded, so a legitimate value came back
     * truncated. None of those survive a parser, and the shape checks below are explicit.
     *
     * <p>A parser does NOT settle duplicate keys by itself: Jackson's default takes the LAST
     * one, so {@code "siblingHash": null, "siblingHash": "<the real one>"} would be read
     * differently here than by a first-wins reader — the same disagreement between readers that
     * the hand-rolled version had, with the winner flipped (review, 2026-09-20). Strict
     * duplicate detection makes it an error instead, the way {@code LineageSpoolCodec} already
     * reads spool JSON. A leading BOM is dropped: Jackson skips it when reading bytes and not
     * when reading a String, and a re-zipped package can acquire one.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readJsonObject(String json) {
        String text = json;
        while (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Object parsed = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
                .readValue(text, Object.class);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("the evidence package is not a JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    /** The value when it is a string, null when it is absent, null, a number or an object. */
    static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** "leafHash is read" / "leafHash is not written" / "leafHash is not a string". */
    private static String fieldState(Map<String, Object> proof, String field) {
        if (asString(proof.get(field)) != null) {
            return field + " is read";
        }
        return proof.containsKey(field) ? field + " is not a string"
                : field + " is not written";
    }

    /**
     * What to say when the package carries no usable {@code inclusionProof}.
     *
     * <p>Four different states used to come out as one sentence about the chain — including the
     * two where the EXPORTER had written, in as many words, "This is NOT a statement that the
     * record was never chained" (review, 2026-09-20). The package says which one it is; the
     * verifier only had to read it.
     */
    private static Check noProofCheck(Map<String, Object> document, Object proofValue) {
        // null, not an empty Map: "there is no proof object" and "there is one and it
        // is empty" are different things, and `isEmpty()` could not tell them apart
        // (review, 2026-09-20).
        Check reason = reasonFor(document, null);
        if (reason != null) {
            return reason;
        }
        if (proofValue != null) {
            return new Check("audit path", Outcome.UNAVAILABLE,
                    "the evidence package's inclusionProof is not an object, so there is "
                            + "nothing to read. Nothing about the entry's inclusion is "
                            + "established either way.");
        }
        return new Check("audit path", Outcome.NOT_PRESENT,
                "the evidence package carries no inclusion proof, and does not say why. The "
                        + "chain only holds what was written to it, with no back-fill, so this "
                        + "says nothing about whether the record is genuine.");
    }

    /**
     * What the package says about why there is no usable proof, or null when it says nothing.
     *
     * <p>ONE rule, used wherever a proof is missing or half-written. It was two — the copy
     * inside {@code auditPathCheck} treated every non-success status as unreadable, including
     * {@code not-chained} — so a package written to the canon got a different answer from this
     * verifier than the canon's own table says, depending on WHERE it put the reason
     * (review, 2026-09-20).
     *
     * <p>{@code proof} is null when the package carries no proof OBJECT at all, which is a
     * different thing from an empty one.
     *
     * <p>The reason is read from ONE place: the proof object when it carries a {@code status}
     * or a {@code message}, otherwise the document. Not merged — pairing a status from one with
     * a message from the other explains a state the package did not report.
     *
     * <p>"Says something" is decided by the key being PRESENT, not by its value being a readable
     * string: a package whose {@code message} is an object for translations has still said
     * something, and calling that "does not say why" asserts the opposite of what happened.
     * When part of what it says cannot be read, EVERY answer here says so — the note used to
     * hang off one arm of four, so three of them dropped the fact that something was there
     * (both reviews, 2026-09-20).
     */
    private static Check reasonFor(Map<String, Object> document, Map<String, Object> proof) {
        if (document.containsKey("inclusionProofFailed")) {
            String couldNotBuild = asString(document.get("inclusionProofFailed"));
            return reason(Outcome.UNAVAILABLE,
                    couldNotBuild != null
                            ? "the package says the audit path could not be built: "
                                    + couldNotBuild
                            : "the package says the audit path could not be built, and the "
                                    + "reason it gives is not a readable string. Nothing about "
                                    + "the entry's inclusion is established either way.",
                    false);
        }
        // ONE source, not two fields resolved separately: taking the status from the proof and
        // the message from the document pairs a state with an explanation of a different one
        // (review, 2026-09-20). The proof's own words win when it has any.
        Map<String, Object> source = proof != null
                && (proof.containsKey("status") || proof.containsKey("message")) ? proof : document;
        if (!source.containsKey("status") && !source.containsKey("message")) {
            return null;
        }
        String status = asString(source.get("status"));
        String message = asString(source.get("message"));
        // "part of it could not be read" is not "it could not be read". Reporting the second
        // when only one field is unreadable threw away the one that WAS read — and the token a
        // reader acts on is usually the status (review, 2026-09-20).
        boolean partlyUnreadable = (source.containsKey("status") && status == null)
                || (source.containsKey("message") && message == null);
        if (status == null && message == null) {
            return reason(Outcome.UNAVAILABLE,
                    "the evidence package carries no usable inclusion proof, and the reason it "
                            + "gives is not a readable string. Nothing about the entry's "
                            + "inclusion is established either way.",
                    false);
        }
        if ("not-chained".equals(status)) {
            return reason(Outcome.NOT_PRESENT,
                    message != null ? message
                            : "the package says no ledger entry names this object. The chain "
                                    + "only holds what was written to it, with no back-fill, so "
                                    + "this says nothing about whether the record is genuine.",
                    partlyUnreadable);
        }
        if ("unavailable".equals(status) || "error".equals(status)) {
            return reason(Outcome.UNAVAILABLE,
                    "the package says its own evidence could not be read"
                            + (message == null ? "" : ": " + message),
                    partlyUnreadable);
        }
        if ("success".equals(status)) {
            if (proof == null) {
                // It says the proof succeeded and there is no proof object at all. That is the
                // package contradicting itself, not a reason — and falling through here reached
                // "carries no inclusion proof, and does NOT SAY WHY" about a package that had
                // said something (review, 2026-09-20).
                return reason(Outcome.UNAVAILABLE,
                        "the package says its inclusion proof succeeded and carries no proof to "
                                + "read. Nothing about the entry's inclusion is established "
                                + "either way.",
                        partlyUnreadable);
            }
            // A proof object IS there and is not usable; the sentence about its own fields says
            // more than this one would. The note would be lost here, so it is carried into that
            // sentence by the caller instead — see auditPathCheck.
            return null;
        }
        // It says SOMETHING, and it is not one of the states this verifier knows. Calling that
        // "no proof is present" would classify a sentence we did not understand — a third-party
        // or older package saying "ledger temporarily unreachable" is not a package saying the
        // record was never chained.
        return reason(Outcome.UNAVAILABLE,
                "the evidence package carries no usable inclusion proof and gives a reason this "
                        + "verifier does not recognise"
                        + (status == null ? "" : " (status " + status + ")")
                        + (message == null ? "" : ": " + message)
                        + ". Nothing about the entry's inclusion is established either way.",
                partlyUnreadable);
    }

    /** One exit, so the "part of it is unreadable" note cannot be left off an arm. */
    private static Check reason(Outcome outcome, String detail, boolean partlyUnreadable) {
        return new Check("audit path", outcome, partlyUnreadable
                ? detail + " Part of what the package says is not a readable string."
                : detail);
    }
}
