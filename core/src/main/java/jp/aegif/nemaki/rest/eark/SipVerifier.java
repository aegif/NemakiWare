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

    /** Everything checked, plus what the set of it amounts to. */
    public record Result(List<Check> checks, String limits) {

        /** True only when at least one check ran and none failed. */
        public boolean allPassed() {
            boolean any = false;
            for (Check check : checks) {
                if (check.outcome() == Outcome.FAILED) {
                    return false;
                }
                if (check.outcome() == Outcome.PASSED) {
                    any = true;
                }
            }
            // An all-NOT_PRESENT package must not report success. "Nothing was wrong" and
            // "nothing was checked" are the same sentence with opposite meanings.
            return any;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("verified", allPassed());
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
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "the package carries no PREMIS document");
        }
        String recorded = between(premis, "<premis:messageDigest>", "</premis:messageDigest>");
        if (recorded == null || recorded.isBlank()) {
            return new Check("payload digest", Outcome.NOT_PRESENT,
                    "PREMIS records no message digest for this object, so there is nothing to "
                            + "check the bytes against. That is a gap in what was captured, not "
                            + "a failure of this check.");
        }
        String algorithm = between(premis, "<premis:messageDigestAlgorithm>",
                "</premis:messageDigestAlgorithm>");
        if (algorithm != null && !"SHA-256".equalsIgnoreCase(algorithm.trim())) {
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
        String leaf = jsonString(evidence, "leafHash");
        String root = jsonString(evidence, "merkleRoot");
        if (leaf == null || root == null) {
            return new Check("audit path", Outcome.NOT_PRESENT,
                    "the evidence package carries no inclusion proof. The chain only holds what "
                            + "was written to it, with no back-fill, so this says nothing about "
                            + "whether the record is genuine.");
        }
        List<Map<String, Object>> steps = auditSteps(evidence);
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

    private static String textOf(Map<String, byte[]> entries, String suffix) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
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
     * One string field out of the JSON, without a JSON library.
     *
     * <p>Deliberately dependency-free: a verifier a third party is meant to reimplement should
     * not need our object mapper, and the shapes read here are flat.
     */
    static String jsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        int end = json.indexOf('"', quote + 1);
        return end < 0 ? null : json.substring(quote + 1, end);
    }

    /** The audit path steps, in order, read out of the flat JSON. */
    static List<Map<String, Object>> auditSteps(String json) {
        List<Map<String, Object>> steps = new ArrayList<>();
        int at = json.indexOf("\"auditPath\"");
        if (at < 0) {
            return steps;
        }
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return steps;
        }
        String body = json.substring(open + 1, close);
        for (String chunk : body.split("\\}")) {
            String sibling = jsonString(chunk, "siblingHash");
            if (sibling == null) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("siblingHash", sibling);
            step.put("siblingIsLeft", chunk.contains("\"siblingIsLeft\" : true")
                    || chunk.contains("\"siblingIsLeft\":true"));
            steps.add(step);
        }
        return steps;
    }
}
