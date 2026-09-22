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
        Map<String, Object> entry = parseOrNull(entryJson);
        // The KIND comes from the entry, not from the statement's shape: the entry is what the
        // chain commits to, and a statement read as "whatever its keys suggest" would let a
        // document with the wrong keys choose which checks apply to it (§5.3b).
        boolean transition = isTransition(entry);
        checks.add(statementCanonicalForm(statementJson, statementC14n));
        checks.add(contentBinding(entries, statement, transition));
        checks.add(transitionContinuity(entries, statement, transition));

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

    /** The entry kind under which a statement says what HAPPENED to the bytes (§5.3b). */
    static final String TRANSITION_KIND = "RECORD_CONTENT_TRANSITION";

    static boolean isTransition(Map<String, Object> entry) {
        return entry != null && TRANSITION_KIND.equals(entry.get("subjectKind"));
    }

    static Outcome.Check contentBinding(Map<String, byte[]> entries,
            Map<String, Object> statement) {
        return contentBinding(entries, statement, false);
    }

    /**
     * @param transition whether the entry says the statement is a transition. A transition
     *        claims no bytes, so there is nothing to bind: with no payload the check is
     *        NOT_PRESENT — which is why a transition package cannot reach VERIFIED at P1 —
     *        and WITH a payload it is FAILED, because "the bytes were moved or removed" and
     *        "here are the bytes" cannot both be true of one package (design §1.5)
     */
    static Outcome.Check contentBinding(Map<String, byte[]> entries,
            Map<String, Object> statement, boolean transition) {
        if (statement == null) {
            return Outcome.Check.absent("content binding", "there is no statement to bind");
        }
        if (transition) {
            int payloads = payloadsIn(entries).size();
            if (payloads == 0) {
                return Outcome.Check.absent("content binding",
                        "the statement is a transition — it says what happened to the bytes, "
                                + "not what they are — and the package carries no payload. "
                                + "There is nothing to bind, so P1 cannot be VERIFIED for it");
            }
            return Outcome.Check.failed("content binding",
                    "the statement says the bytes were moved or removed and the package carries "
                            + payloads + " payload(s). A transition and a payload in one package "
                            + "contradict each other");
        }
        Object digest = statement.get("contentDigest");
        Object length = statement.get("contentLength");
        if (!(digest instanceof String recorded) || !(length instanceof Long recordedLength)) {
            return Outcome.Check.absent("content binding",
                    "the statement records no content digest or length");
        }
        List<Map.Entry<String, byte[]>> payloads = payloadsIn(entries);
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

    private static List<Map.Entry<String, byte[]>> payloadsIn(Map<String, byte[]> entries) {
        List<Map.Entry<String, byte[]>> payloads = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = "/" + entry.getKey();
            if (path.contains("/representations/") && path.contains("/data/")) {
                payloads.add(entry);
            }
        }
        return payloads;
    }

    /**
     * A transition's copied prior digest is checked against its source (design §1.5).
     *
     * <p>The statement names {@code priorContentDigest} and the ledger entry it was copied
     * from. The package may carry that entry and its statement under {@code prior/} (§4.2);
     * when it does, the copy is checked: the cited entry has that sequence, commits to the
     * shipped prior statement, and that statement's {@code contentDigest} is the copied value.
     * When the transition says the prior is not known (both null) the check is NOT_PRESENT —
     * "not known" is a statement, not a defect. When the prior is cited but not shipped it is
     * UNAVAILABLE with {@code TRANSITION_PRIOR_NOT_IN_PACKAGE}: the copy could not be checked,
     * which is not the same as the copy being wrong.
     *
     * <p>Emitted for every P1 package and required by none: a state statement has nothing to
     * be continuous with (NOT_PRESENT), and a transition package is INDETERMINATE at P1 through
     * {@code content binding} whatever this says. A FAILED here still fails the package (§15:
     * FAILED is over the whole set).
     */
    static Outcome.Check transitionContinuity(Map<String, byte[]> entries,
            Map<String, Object> statement, boolean transition) {
        String name = "transition continuity";
        if (!transition) {
            return Outcome.Check.absent(name, "the statement is a state, not a transition; "
                    + "there is nothing earlier it claims to continue from");
        }
        if (statement == null) {
            return Outcome.Check.absent(name, "there is no statement");
        }
        Object prior = statement.get("priorContentDigest");
        Object from = statement.get("priorStatementEntrySequence");
        if (prior == null && from == null) {
            return Outcome.Check.absent(name, "the transition says the prior digest is not "
                    + "known; a copy nobody made cannot be checked, and 'not known' is not 'none'");
        }
        if (!(prior instanceof String priorDigest) || !(from instanceof Long cited)) {
            return Outcome.Check.failed(name, "the transition names a prior digest without its "
                    + "source entry, or the reverse; the two are set together or not at all");
        }
        byte[] priorStatementJson = fileIn(entries, "prior/record-content-statement.json");
        byte[] priorEntryJson = fileIn(entries, "prior/ledger-entry.json");
        Map<String, Object> priorEntry = parseOrNull(priorEntryJson);
        if (priorStatementJson == null || priorEntry == null) {
            return Outcome.Check.unavailable(name, "TRANSITION_PRIOR_NOT_IN_PACKAGE",
                    "the transition cites ledger entry " + cited + " and the package does not "
                            + "carry that entry and its statement under prior/, so the copied "
                            + "digest could not be checked against its source");
        }
        Map<String, Object> priorStatement;
        String priorStatementDigest;
        try {
            Object parsed = Json.parse(new String(priorStatementJson, StandardCharsets.UTF_8));
            priorStatement = parsed instanceof Map ? (Map<String, Object>) parsed : null;
            priorStatementDigest = Canonical.documentDigest(parsed);
        } catch (Json.NotCanonicalisable | Canonical.NotEncodable e) {
            return Outcome.Check.failed(name, "the prior statement has no canonical form: "
                    + e.getMessage());
        }
        if (priorStatement == null) {
            return Outcome.Check.failed(name, "the prior statement is not a document");
        }
        if (!(priorEntry.get("sequence") instanceof Long at) || at != cited) {
            return Outcome.Check.failed(name, "the transition cites entry " + cited
                    + " and the shipped prior entry is " + priorEntry.get("sequence"));
        }
        for (String field : List.of("domain", "sequence", "subjectKind", "subjectId",
                "payloadDigest", "occurredAt", "prevEntryHash", "entryHash")) {
            if (!priorEntry.containsKey(field)) {
                return Outcome.Check.failed(name, "the shipped prior entry omits " + field
                        + ", so it cannot be the entry it claims to be");
            }
        }
        String recomputed = Canonical.hash("LEDGER_ENTRY_V1", priorEntry.get("domain"),
                priorEntry.get("sequence"), priorEntry.get("subjectKind"),
                priorEntry.get("subjectId"), priorEntry.get("payloadDigest"),
                priorEntry.get("occurredAt"), priorEntry.get("prevEntryHash"));
        if (!recomputed.equals(priorEntry.get("entryHash"))) {
            return Outcome.Check.failed(name, "the shipped prior entry's fields hash to "
                    + recomputed + " and it records " + priorEntry.get("entryHash"));
        }
        if (!priorStatementDigest.equals(priorEntry.get("payloadDigest"))) {
            return Outcome.Check.failed(name, "the shipped prior statement digests to "
                    + priorStatementDigest + " and entry " + cited + " commits to "
                    + priorEntry.get("payloadDigest") + ", so it is not the statement cited");
        }
        if (!"RECORD_CONTENT_STATE".equals(priorEntry.get("subjectKind"))
                || !(priorStatement.get("contentDigest") instanceof String source)) {
            return Outcome.Check.failed(name, "the cited entry is not a state statement with a "
                    + "content digest, so nothing there could have been copied");
        }
        if (!source.equals(priorDigest)) {
            return Outcome.Check.failed(name, "the transition copied " + priorDigest
                    + " and the cited statement's contentDigest is " + source);
        }
        // The prior has to be about THIS version. Everything above agrees on numbers and
        // digests, and none of it is identity: a state statement for ANOTHER record that
        // happens to sit at the cited sequence and to have the same content digest satisfies
        // all of it (Codex, second review). Two records with equal bytes are ordinary.
        Object priorSubject = priorEntry.get("subjectId");
        if (!(priorSubject instanceof String subject)
                || !subject.equals(statement.get("versionObjectId"))) {
            return Outcome.Check.failed(name, "the cited entry is about " + priorSubject
                    + " and this transition is about " + statement.get("versionObjectId")
                    + ", so the prior belongs to another record");
        }
        for (String field : List.of("repositoryId", "objectId", "versionObjectId")) {
            // Present STRINGs, as §5.3 requires of both documents. Objects.equals alone let
            // two absent fields — or two nulls — agree, so a prior with no identity at all
            // passed as this record's (Codex, third review, P2).
            // §5: a MISSING required field makes the check NOT_PRESENT; a field of the wrong
            // TYPE is a finding. The first version failed both, which reported "these are
            // different records" about a package that simply did not carry the field
            // (Codex, fourth review, P2).
            if (!statement.containsKey(field) || !priorStatement.containsKey(field)) {
                return Outcome.Check.absent(name, "the transition or its prior omits " + field
                        + ", so whether they are about the same record cannot be checked");
            }
            Object here = statement.get(field);
            Object there = priorStatement.get(field);
            if (!(here instanceof String mine) || !(there instanceof String theirs)) {
                return Outcome.Check.failed(name, "the transition's " + field + " is " + here
                        + " and the prior statement's is " + there + "; §5.3 requires both to "
                        + "be text");
            }
            if (!mine.equals(theirs)) {
                return Outcome.Check.failed(name, "the prior statement's " + field + " is "
                        + theirs + " and this transition's is " + mine + ", so they are about "
                        + "different records");
            }
        }
        // And the canonical forms shipped beside them are the canonical forms OF them. The
        // package carries four files under prior/ (§4.2); reading two and shipping four means
        // the other two can say anything (Codex, second review, P2).
        Outcome.Check c14n = priorCanonicalForm(entries, name,
                "prior/record-content-statement.c14n", priorStatementJson);
        if (c14n != null) {
            return c14n;
        }
        c14n = priorCanonicalForm(entries, name, "prior/ledger-entry.c14n", priorEntryJson);
        if (c14n != null) {
            return c14n;
        }
        return Outcome.Check.passed(name);
    }

    /**
     * The failure when {@code path} is not the canonical form of {@code json}, or null when it
     * is. A package that carries the JSON and drops its {@code .c14n} is one that shipped half
     * of a pair §4.2 defines as four files.
     */
    private static Outcome.Check priorCanonicalForm(Map<String, byte[]> entries, String name,
            String path, byte[] json) {
        byte[] shipped = fileIn(entries, path);
        if (shipped == null) {
            return Outcome.Check.failed(name, "the package carries " + path.replace(".c14n",
                    ".json") + " and not " + path + "; prior/ is four files or none");
        }
        byte[] recomputed;
        try {
            recomputed = Canonical.encode(Json.parse(new String(json, StandardCharsets.UTF_8)));
        } catch (Json.NotCanonicalisable | Canonical.NotEncodable e) {
            return Outcome.Check.failed(name, path.replace(".c14n", ".json")
                    + " has no canonical form: " + e.getMessage());
        }
        if (!Arrays.equals(recomputed, shipped)) {
            return Outcome.Check.failed(name, "the shipped " + path + " is not the canonical "
                    + "form of the .json beside it");
        }
        return null;
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
