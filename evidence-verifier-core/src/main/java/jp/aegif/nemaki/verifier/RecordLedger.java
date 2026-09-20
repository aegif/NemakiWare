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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code RECORD_LEDGER_V1} — the checks of {@code evidence-profile-v1.md} §10.
 *
 * <p>Everything here is a RECOMPUTATION. The package states a digest, an entry hash, a
 * checkpoint hash; this class works each one out from the fields beside it and compares. A
 * check that read a stated value and reported it back would be verifying that the package
 * agrees with itself, which every package does.
 *
 * <p>What P1 still does not buy: any of it could have been written by whoever holds the ledger.
 * Independence arrives with the anchor (P2 and above), and this class is careful never to
 * describe its own result in words that suggest otherwise.
 */
public final class RecordLedger {

    /** Where §4.2 puts the evidence section. */
    static final String DIR = "/metadata/other/nemaki-evidence/";

    /** The checks this profile will not pass without — §10. */
    public static final List<String> REQUIRED = List.of(
            "statement canonical form", "content binding", "entry recompute",
            "entry binds statement", "inclusion proof", "covering range",
            "checkpoint recompute");

    private RecordLedger() {
    }

    /** Runs P1 over a package that has already been read. */
    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();

        byte[] statementJson = fileIn(entries, "record-content-statement.json");
        byte[] statementC14n = fileIn(entries, "record-content-statement.c14n");
        byte[] entryJson = fileIn(entries, "ledger-entry.json");
        byte[] proofJson = fileIn(entries, "inclusion-proof.json");
        byte[] coveringJson = fileIn(entries, "covering-checkpoint.json");

        if (statementJson == null && entryJson == null && coveringJson == null) {
            // The legacy layout, or a package built before E1. Every P1 check is absent for the
            // same reason, and saying so once with a reason code is more use than seven
            // identical NOT_PRESENTs with no explanation.
            for (String name : REQUIRED) {
                checks.add(Outcome.Check.unavailable(name, "LEGACY_PACKAGE_LAYOUT",
                        "the package carries no " + DIR.substring(1) + " section, so nothing "
                                + "above PACKAGE_INTEGRITY_V1 can be evaluated"));
            }
            return checks;
        }

        Map<String, Object> statement = parseOrNull(statementJson);
        checks.add(statementCanonicalForm(statementJson, statementC14n));
        checks.add(contentBinding(entries, statement));

        Map<String, Object> entry = parseOrNull(entryJson);
        checks.add(entryRecompute(entry));
        checks.add(entryBindsStatement(entry, statementJson));

        Map<String, Object> covering = parseOrNull(coveringJson);
        checks.add(checkpointRecompute(covering));
        checks.add(coveringRange(entry, covering));
        checks.add(inclusionProof(parseOrNull(proofJson), entry, covering));
        return checks;
    }

    static Outcome.Check statementCanonicalForm(byte[] json, byte[] shipped) {
        if (json == null) {
            return Outcome.Check.absent("statement canonical form",
                    "the package carries no record content statement");
        }
        if (shipped == null) {
            return Outcome.Check.absent("statement canonical form",
                    "the statement ships without its canonical form, so there is nothing to "
                            + "compare a recomputation against");
        }
        byte[] recomputed;
        try {
            recomputed = Canonical.encode(Json.parse(new String(json, StandardCharsets.UTF_8)));
        } catch (Json.NotCanonicalisable | Canonical.NotEncodable e) {
            // Malformed is FAILED, not UNAVAILABLE: the document was read and found to have no
            // canonical form, which is a finding about the package.
            return Outcome.Check.failed("statement canonical form",
                    "the statement has no canonical form: " + e.getMessage());
        }
        if (!Arrays.equals(recomputed, shipped)) {
            return Outcome.Check.failed("statement canonical form",
                    "the shipped .c14n is not the canonical form of the .json beside it");
        }
        return Outcome.Check.passed("statement canonical form");
    }

    static Outcome.Check contentBinding(Map<String, byte[]> entries,
            Map<String, Object> statement) {
        if (statement == null) {
            return Outcome.Check.absent("content binding", "there is no statement to bind");
        }
        Object digest = statement.get("contentDigest");
        Object length = statement.get("contentLength");
        if (!(digest instanceof String recorded) || !(length instanceof Long recordedLength)) {
            return Outcome.Check.absent("content binding",
                    "the statement records no content digest or length");
        }
        List<Map.Entry<String, byte[]>> payloads = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = "/" + entry.getKey();
            if (path.contains("/representations/") && path.contains("/data/")) {
                payloads.add(entry);
            }
        }
        if (payloads.isEmpty()) {
            return Outcome.Check.absent("content binding", "the package carries no payload");
        }
        if (payloads.size() > 1) {
            return Outcome.Check.unavailable("content binding", "AMBIGUOUS_PAYLOAD",
                    "the package carries " + payloads.size() + " payloads and one statement");
        }
        byte[] bytes = payloads.get(0).getValue();
        String actual = Canonical.hex(Canonical.sha256(bytes));
        if (!actual.equalsIgnoreCase(recorded)) {
            return Outcome.Check.failed("content binding",
                    "the payload hashes to " + actual + " and the statement records " + recorded);
        }
        if (bytes.length != recordedLength) {
            return Outcome.Check.failed("content binding",
                    "the payload is " + bytes.length + " bytes and the statement records "
                            + recordedLength);
        }
        return Outcome.Check.passed("content binding");
    }

    static Outcome.Check entryRecompute(Map<String, Object> entry) {
        if (entry == null) {
            return Outcome.Check.absent("entry recompute", "the package carries no ledger entry");
        }
        for (String field : List.of("domain", "sequence", "subjectKind", "subjectId",
                "payloadDigest", "occurredAt", "prevEntryHash", "entryHash")) {
            if (!entry.containsKey(field)) {
                // Absent, not failed. A hash that cannot be recomputed has not been checked,
                // and reporting a mismatch would name a defect nobody found (§6).
                return Outcome.Check.absent("entry recompute",
                        "the entry omits " + field + ", so its hash cannot be recomputed");
            }
        }
        String recomputed = Canonical.hash("LEDGER_ENTRY_V1", entry.get("domain"),
                entry.get("sequence"), entry.get("subjectKind"), entry.get("subjectId"),
                entry.get("payloadDigest"), entry.get("occurredAt"), entry.get("prevEntryHash"));
        if (!recomputed.equals(entry.get("entryHash"))) {
            return Outcome.Check.failed("entry recompute",
                    "the entry's fields hash to " + recomputed + " and it records "
                            + entry.get("entryHash"));
        }
        return Outcome.Check.passed("entry recompute");
    }

    static Outcome.Check entryBindsStatement(Map<String, Object> entry, byte[] statementJson) {
        if (entry == null || statementJson == null) {
            return Outcome.Check.absent("entry binds statement",
                    "the package carries no entry or no statement");
        }
        String digest;
        try {
            digest = Canonical.documentDigest(
                    Json.parse(new String(statementJson, StandardCharsets.UTF_8)));
        } catch (Json.NotCanonicalisable | Canonical.NotEncodable e) {
            return Outcome.Check.failed("entry binds statement",
                    "the statement has no canonical form, so nothing can bind to it");
        }
        Object payloadDigest = entry.get("payloadDigest");
        if (!(payloadDigest instanceof String recorded)) {
            return Outcome.Check.absent("entry binds statement",
                    "the entry records no payload digest");
        }
        if (!digest.equals(recorded)) {
            // The check that makes the pair a pair. Without it a package can hold a statement
            // about these bytes and an entry about a DIFFERENT statement, each internally
            // consistent.
            return Outcome.Check.failed("entry binds statement",
                    "the statement digests to " + digest + " and the ledger entry commits to "
                            + recorded + ", so they are about different things");
        }
        return Outcome.Check.passed("entry binds statement");
    }

    static Outcome.Check checkpointRecompute(Map<String, Object> checkpoint) {
        if (checkpoint == null) {
            return Outcome.Check.absent("checkpoint recompute",
                    "the package carries no covering checkpoint");
        }
        for (String field : List.of("domain", "fromSequence", "toSequence", "merkleRoot",
                "prevCheckpointHash", "createdAt", "checkpointHash")) {
            if (!checkpoint.containsKey(field)) {
                return Outcome.Check.absent("checkpoint recompute",
                        "the checkpoint omits " + field + ", so its hash cannot be recomputed");
            }
        }
        String recomputed = Canonical.hash("LEDGER_CHECKPOINT_V1", checkpoint.get("domain"),
                checkpoint.get("fromSequence"), checkpoint.get("toSequence"),
                checkpoint.get("merkleRoot"), checkpoint.get("prevCheckpointHash"),
                checkpoint.get("createdAt"));
        if (!recomputed.equals(checkpoint.get("checkpointHash"))) {
            return Outcome.Check.failed("checkpoint recompute",
                    "the checkpoint's fields hash to " + recomputed + " and it records "
                            + checkpoint.get("checkpointHash"));
        }
        return Outcome.Check.passed("checkpoint recompute");
    }

    static Outcome.Check coveringRange(Map<String, Object> entry,
            Map<String, Object> checkpoint) {
        if (entry == null || checkpoint == null) {
            return Outcome.Check.absent("covering range",
                    "the package carries no entry or no covering checkpoint");
        }
        if (!(entry.get("sequence") instanceof Long sequence)
                || !(checkpoint.get("fromSequence") instanceof Long from)
                || !(checkpoint.get("toSequence") instanceof Long to)) {
            return Outcome.Check.absent("covering range",
                    "the entry or the checkpoint records no sequence numbers");
        }
        if (sequence < from || sequence > to) {
            // A proof against a checkpoint that does not cover the entry proves nothing about
            // it, however well the hashes chain.
            return Outcome.Check.failed("covering range",
                    "the entry is at sequence " + sequence + " and the checkpoint covers "
                            + from + ".." + to + ", so it does not commit to this entry");
        }
        return Outcome.Check.passed("covering range");
    }

    @SuppressWarnings("unchecked")
    static Outcome.Check inclusionProof(Map<String, Object> proof, Map<String, Object> entry,
            Map<String, Object> checkpoint) {
        if (entry == null || checkpoint == null) {
            return Outcome.Check.absent("inclusion proof",
                    "the package carries no entry or no covering checkpoint");
        }
        if (proof == null) {
            return Outcome.Check.absent("inclusion proof",
                    "the package carries no inclusion proof");
        }
        Object steps = proof.get("steps");
        if (!(steps instanceof List<?> rawSteps)) {
            // "No proof" and "the proof does not hold" are different answers (§8), and the
            // absent arm carries the package's own reason when it gave one.
            Object because = proof.get("unavailableBecause");
            return Outcome.Check.absent("inclusion proof",
                    because == null ? "the proof carries no steps and no reason"
                            : String.valueOf(because));
        }
        List<Merkle.Step> path = new ArrayList<>();
        for (Object raw : rawSteps) {
            if (!(raw instanceof Map<?, ?> step)) {
                return Outcome.Check.failed("inclusion proof",
                        "a step in the audit path is not an object");
            }
            Map<String, Object> one = (Map<String, Object>) step;
            if (!(one.get("siblingHash") instanceof String sibling)) {
                return Outcome.Check.failed("inclusion proof",
                        "a step in the audit path records no sibling hash");
            }
            path.add(new Merkle.Step(sibling, Boolean.TRUE.equals(one.get("siblingIsLeft"))));
        }
        if (!(entry.get("entryHash") instanceof String entryHash)
                || !(checkpoint.get("merkleRoot") instanceof String root)) {
            return Outcome.Check.absent("inclusion proof",
                    "the entry or the checkpoint records no hash to walk between");
        }
        if (!Merkle.verifies(entryHash, path, root)) {
            return Outcome.Check.failed("inclusion proof",
                    "the audit path does not carry the entry to the checkpoint's Merkle root");
        }
        return Outcome.Check.passed("inclusion proof");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOrNull(byte[] json) {
        if (json == null) {
            return null;
        }
        try {
            Object value = Json.parse(new String(json, StandardCharsets.UTF_8));
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (Json.NotCanonicalisable malformed) {
            return null;
        }
    }

    private static byte[] fileIn(Map<String, byte[]> entries, String name) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (("/" + entry.getKey()).endsWith(DIR + name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
