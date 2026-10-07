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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1, and the substitutions it exists to refuse.
 *
 * <p>Every check here RECOMPUTES. The interesting failures are the pairs that are each
 * internally consistent and are not about each other: a statement about these bytes beside an
 * entry about a different statement, a proof against a checkpoint that does not cover the
 * entry. Both look perfect to a verifier that only reads stated values back.
 */
class TheLedgerIsRecomputedNotReadBackTest {

    private static final String ROOT = "sip/";
    private static final String DIR = ROOT + "metadata/other/nemaki-evidence/";

    private static String json(Map<String, Object> document) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : document.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                out.append("null");
            } else if (v instanceof String s) {
                out.append('"').append(s).append('"');
            } else if (v instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) list.get(i);
                    out.append(json(nested));
                }
                out.append(']');
            } else {
                out.append(v);
            }
        }
        return out.append('}').toString();
    }

    private static Map<String, Object> statement(String payload) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("repositoryId", "bedroom");
        doc.put("objectId", "doc-1");
        doc.put("versionObjectId", "doc-1");
        doc.put("contentStreamId", "att-1");
        doc.put("contentDigest",
                Canonical.hex(Canonical.sha256(payload.getBytes(StandardCharsets.UTF_8))));
        doc.put("contentLength", (long) payload.getBytes(StandardCharsets.UTF_8).length);
        doc.put("commitmentKind", "CAPTURED");
        doc.put("captureIntentId", null);
        doc.put("recordedAt", "2026-09-20T00:00:00Z");
        return doc;
    }

    private static Map<String, Object> entryFor(String statementDigest) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("domain", "record-content");
        entry.put("sequence", 1L);
        entry.put("subjectKind", "RECORD_CONTENT_STATE");
        entry.put("subjectId", "doc-1");
        entry.put("payloadDigest", statementDigest);
        entry.put("occurredAt", "2026-09-20T00:00:00Z");
        entry.put("prevEntryHash", null);
        entry.put("entryHash", Canonical.hash("LEDGER_ENTRY_V1", "record-content", 1L,
                "RECORD_CONTENT_STATE", "doc-1", statementDigest, "2026-09-20T00:00:00Z", null));
        return entry;
    }

    private static Map<String, Object> checkpointOver(String entryHash, long from, long to) {
        String root = Merkle.root(List.of(entryHash));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("domain", "record-content");
        checkpoint.put("fromSequence", from);
        checkpoint.put("toSequence", to);
        checkpoint.put("merkleRoot", root);
        checkpoint.put("prevCheckpointHash", null);
        checkpoint.put("createdAt", "2026-09-20T00:00:00Z");
        checkpoint.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content",
                from, to, root, null, "2026-09-20T00:00:00Z"));
        return checkpoint;
    }

    /** A package whose P1 section is internally consistent. */
    private static Map<String, byte[]> goodEntries(String payload) {
        Map<String, Object> statement = statement(payload);
        String statementDigest = Canonical.documentDigest(statement);
        Map<String, Object> entry = entryFor(statementDigest);
        Map<String, Object> covering =
                checkpointOver(String.valueOf(entry.get("entryHash")), 1L, 1L);

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("leafHash", Merkle.hashLeaf(String.valueOf(entry.get("entryHash"))));
        proof.put("steps", List.<Map<String, Object>>of());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "representations/rep1/data/minutes.txt",
                payload.getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "record-content-statement.json",
                json(statement).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "record-content-statement.c14n", Canonical.encode(statement));
        entries.put(DIR + "ledger-entry.json", json(entry).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "covering-checkpoint.json",
                json(covering).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "inclusion-proof.json", json(proof).getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<Outcome.Check> required(List<Outcome.Check> checks) {
        List<Outcome.Check> out = new ArrayList<>();
        for (String name : RecordLedger.REQUIRED) {
            out.add(named(checks, name));
        }
        return out;
    }

    @Test
    @DisplayName("a consistent P1 section passes every required check")
    void aConsistentSectionPasses() {
        List<Outcome.Check> checks = RecordLedger.check(goodEntries("the minutes"));
        for (String name : RecordLedger.REQUIRED) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(),
                    name + " did not pass: " + checks);
        }
        assertEquals(Outcome.Verdict.VERIFIED, Outcome.combine(checks, required(checks)));
    }

    @Test
    @DisplayName("a statement about OTHER bytes fails content binding")
    void aStatementAboutOtherBytesFails() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        entries.put(ROOT + "representations/rep1/data/minutes.txt",
                "the EDITED minutes".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        assertEquals(Outcome.FAILED, named(checks, "content binding").outcome(), checks + "");
        assertEquals(Outcome.Verdict.FAILED, Outcome.combine(checks, required(checks)));
    }

    @Test
    @DisplayName("an entry about a DIFFERENT statement fails, though both are well formed")
    void anEntryAboutAnotherStatementFails() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        // A perfectly valid entry — its own hash recomputes — about a statement that is not
        // the one in the package. Each half is internally consistent; only comparing the two
        // finds it, which is why ENTRY_BINDS_STATEMENT is its own required check.
        Map<String, Object> other = entryFor("f".repeat(64));
        entries.put(DIR + "ledger-entry.json", json(other).getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        assertEquals(Outcome.PASSED, named(checks, "entry recompute").outcome(),
                "the entry itself is well formed — that is the point");
        assertEquals(Outcome.FAILED, named(checks, "entry binds statement").outcome(),
                "a statement about these bytes beside an entry about a different statement is "
                        + "two true things that are not about each other");
    }

    @Test
    @DisplayName("an edited entry field fails the recomputation")
    void anEditedEntryFieldFails() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        Map<String, Object> entry = entryFor(Canonical.documentDigest(statement("the minutes")));
        entry.put("occurredAt", "2020-01-01T00:00:00Z");
        entries.put(DIR + "ledger-entry.json", json(entry).getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED,
                named(RecordLedger.check(entries), "entry recompute").outcome(),
                "the recorded hash is left alone and a field is moved under it — the shape a "
                        + "rewrite takes");
    }

    @Test
    @DisplayName("a checkpoint that does not cover the entry fails, however well it hashes")
    void aCheckpointThatDoesNotCoverTheEntryFails() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        Map<String, Object> entry = entryFor(Canonical.documentDigest(statement("the minutes")));
        // A well-formed checkpoint over sequences 10..20. Its own hash recomputes; it simply
        // does not commit to an entry at sequence 1.
        Map<String, Object> elsewhere =
                checkpointOver(String.valueOf(entry.get("entryHash")), 10L, 20L);
        entries.put(DIR + "covering-checkpoint.json",
                json(elsewhere).getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        assertEquals(Outcome.PASSED, named(checks, "checkpoint recompute").outcome());
        assertEquals(Outcome.FAILED, named(checks, "covering range").outcome(),
                "a proof against a checkpoint that does not cover the entry proves nothing "
                        + "about it, however well the hashes chain");
    }

    @Test
    @DisplayName("a .c14n that is not the canonical form of its .json fails")
    void aWrongCanonicalFormFails() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        entries.put(DIR + "record-content-statement.c14n",
                Canonical.encode(statement("something else")));

        assertEquals(Outcome.FAILED,
                named(RecordLedger.check(entries), "statement canonical form").outcome(),
                "the shipped canonical form is checked, never trusted: it exists so a reader "
                        + "can compare it with their own recomputation");
    }

    @Test
    @DisplayName("the legacy layout is UNAVAILABLE with a reason, not seven silent absences")
    void theLegacyLayoutSaysWhy() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "representations/rep1/data/minutes.txt",
                "the minutes".getBytes(StandardCharsets.UTF_8));
        entries.put(ROOT + "metadata/other/nemaki-evidence.json", "{}".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        for (String name : RecordLedger.REQUIRED) {
            Outcome.Check check = named(checks, name);
            assertEquals(Outcome.UNAVAILABLE, check.outcome(), name + ": " + checks);
            assertEquals("LEGACY_PACKAGE_LAYOUT", check.reasonCode());
        }
        assertEquals(Outcome.Verdict.INDETERMINATE, Outcome.combine(checks, required(checks)),
                "a package this verifier cannot evaluate above P0 is INDETERMINATE there — "
                        + "never verified, and never failed either");
    }

    @Test
    @DisplayName("an entry missing a field is NOT_PRESENT, not a mismatch")
    void aMissingFieldIsNotAMismatch() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        Map<String, Object> entry = entryFor(Canonical.documentDigest(statement("the minutes")));
        entry.remove("subjectId");
        entries.put(DIR + "ledger-entry.json", json(entry).getBytes(StandardCharsets.UTF_8));

        Outcome.Check check = named(RecordLedger.check(entries), "entry recompute");

        assertEquals(Outcome.NOT_PRESENT, check.outcome(),
                "a hash that cannot be recomputed has not been checked; reporting a mismatch "
                        + "would name a defect nobody found");
        assertTrue(check.detail().contains("subjectId"), check.detail());
    }

    /**
     * A package whose entry is the LEFT leaf of a two-leaf tree, so its proof has one step whose
     * sibling is on the right — {@code siblingIsLeft: false}, the value a reader that defaults a
     * missing field to false would invent.
     */
    private static Map<String, byte[]> twoLeafEntries(String payload) {
        Map<String, byte[]> entries = goodEntries(payload);
        Map<String, Object> entry = entryFor(Canonical.documentDigest(statement(payload)));
        String entryHash = String.valueOf(entry.get("entryHash"));
        String other = Canonical.hash("LEDGER_ENTRY_V1", "record-content", 2L,
                "RECORD_CONTENT_STATE", "doc-2", "0".repeat(64), "2026-09-20T00:00:01Z", entryHash);
        String root = Merkle.root(List.of(entryHash, other));
        Map<String, Object> covering = new LinkedHashMap<>();
        covering.put("domain", "record-content");
        covering.put("fromSequence", 1L);
        covering.put("toSequence", 2L);
        covering.put("merkleRoot", root);
        covering.put("prevCheckpointHash", null);
        covering.put("createdAt", "2026-09-20T00:00:00Z");
        covering.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content",
                1L, 2L, root, null, "2026-09-20T00:00:00Z"));
        entries.put(DIR + "covering-checkpoint.json",
                json(covering).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "inclusion-proof.json",
                json(proof(Merkle.hashLeaf(entryHash), Merkle.hashLeaf(other), false))
                        .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static Map<String, Object> proof(Object leafHash, Object siblingHash, Object siblingIsLeft) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("siblingHash", siblingHash);
        step.put("siblingIsLeft", siblingIsLeft);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("leafHash", leafHash);
        proof.put("steps", List.of(step));
        return proof;
    }

    private static Outcome.Check proofCheckWith(Map<String, Object> proof) {
        Map<String, byte[]> entries = twoLeafEntries("the minutes");
        entries.put(DIR + "inclusion-proof.json", json(proof).getBytes(StandardCharsets.UTF_8));
        return named(RecordLedger.check(entries), "inclusion proof");
    }

    private static String entryLeaf() {
        return Merkle.hashLeaf(String.valueOf(
                entryFor(Canonical.documentDigest(statement("the minutes"))).get("entryHash")));
    }

    private static String siblingLeaf() {
        String entryHash = String.valueOf(
                entryFor(Canonical.documentDigest(statement("the minutes"))).get("entryHash"));
        return Merkle.hashLeaf(Canonical.hash("LEDGER_ENTRY_V1", "record-content", 2L,
                "RECORD_CONTENT_STATE", "doc-2", "0".repeat(64), "2026-09-20T00:00:01Z", entryHash));
    }

    @Test
    @DisplayName("a proof with a right-hand sibling passes when it says so")
    void aRightSiblingStatedPasses() {
        List<Outcome.Check> checks = RecordLedger.check(twoLeafEntries("the minutes"));
        assertEquals(Outcome.PASSED, named(checks, "inclusion proof").outcome(), checks + "");
        assertEquals(Outcome.Verdict.VERIFIED, Outcome.combine(checks, required(checks)));
    }

    @Test
    @DisplayName("a step that does not say which side its sibling is on is NOT_PRESENT, not a right sibling")
    void aStepWithoutItsSideIsNotPresent() {
        Map<String, Object> proof = proof(entryLeaf(), siblingLeaf(), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) ((List<Object>) proof.get("steps")).get(0);
        step.remove("siblingIsLeft");

        Outcome.Check check = proofCheckWith(proof);

        // It used to be read as false — the right answer for THIS step, so the proof walked to
        // the root and passed with a required field missing (9-6 review, P1).
        assertEquals(Outcome.NOT_PRESENT, check.outcome(), check.detail());
    }

    @Test
    @DisplayName("a siblingIsLeft that is not a boolean is a FAILURE")
    void aSideThatIsNotABooleanFails() {
        Outcome.Check check = proofCheckWith(proof(entryLeaf(), siblingLeaf(), "false"));
        assertEquals(Outcome.FAILED, check.outcome(), check.detail());
    }

    @Test
    @DisplayName("a step without its sibling hash is NOT_PRESENT")
    void aStepWithoutItsSiblingIsNotPresent() {
        Map<String, Object> proof = proof(entryLeaf(), siblingLeaf(), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) ((List<Object>) proof.get("steps")).get(0);
        step.remove("siblingHash");
        Outcome.Check check = proofCheckWith(proof);
        assertEquals(Outcome.NOT_PRESENT, check.outcome(), check.detail());
    }

    @Test
    @DisplayName("a proof without its leafHash is NOT_PRESENT, though the walk would still reach the root")
    void aProofWithoutItsLeafIsNotPresent() {
        Map<String, Object> proof = proof(entryLeaf(), siblingLeaf(), false);
        proof.remove("leafHash");
        Outcome.Check check = proofCheckWith(proof);
        assertEquals(Outcome.NOT_PRESENT, check.outcome(), check.detail());
    }

    @Test
    @DisplayName("a leafHash that is not the entry's leaf, or not a string, is a FAILURE")
    void aLeafThatIsNotTheEntrysFails() {
        Outcome.Check other = proofCheckWith(proof(Merkle.hashLeaf("some other entry"), siblingLeaf(), false));
        assertEquals(Outcome.FAILED, other.outcome(), other.detail());
        Outcome.Check number = proofCheckWith(proof(7L, siblingLeaf(), false));
        assertEquals(Outcome.FAILED, number.outcome(), number.detail());
    }

    @Test
    @DisplayName("a ledger entry that is not a well-formed document is FAILED for every check that reads it, not absent")
    void aMalformedLedgerEntryIsFailedNotAbsent() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        entries.put(DIR + "ledger-entry.json",
                "{\"sequence\":1,\"sequence\":1}".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        for (String name : List.of("entry recompute", "entry binds statement", "content binding",
                "covering range", "inclusion proof", "transition continuity")) {
            Outcome.Check check = named(checks, name);
            assertEquals(Outcome.FAILED, check.outcome(), name + ": a duplicate key makes the "
                    + "entry malformed (§3.2), which is FAILED for every check that reads it. "
                    + "Folded into null it read as 'the package carries no ledger entry' and "
                    + "exit 3 (9-6 review, P1)");
            assertTrue(check.detail().contains("ledger-entry.json"), check.detail());
        }
        // The checks that do not read the entry are untouched by it.
        assertEquals(Outcome.PASSED, named(checks, "statement canonical form").outcome());
        assertEquals(Outcome.PASSED, named(checks, "checkpoint recompute").outcome());
        // And an entry that is NOT there is still absent, not failed.
        entries.remove(DIR + "ledger-entry.json");
        assertEquals(Outcome.NOT_PRESENT,
                named(RecordLedger.check(entries), "entry recompute").outcome());
    }

    @Test
    @DisplayName("a statement that is not a well-formed document is FAILED for every check that reads it")
    void aMalformedStatementIsFailedNotAbsent() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        entries.put(DIR + "record-content-statement.json", "[]".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = RecordLedger.check(entries);

        for (String name : List.of("statement canonical form", "content binding",
                "entry binds statement")) {
            assertEquals(Outcome.FAILED, named(checks, name).outcome(), name);
            assertTrue(named(checks, name).detail().contains("record-content-statement.json"),
                    named(checks, name).detail());
        }
        assertEquals(Outcome.PASSED, named(checks, "entry recompute").outcome(),
                "the entry recomputes from its own fields whatever the statement beside it is");
    }

    @Test
    @DisplayName("a shipped .c14n beside the entry or the covering checkpoint has to be its canonical form")
    @SuppressWarnings("unchecked")
    void aShippedC14nBesideTheEntryOrCheckpointIsChecked() {
        Map<String, byte[]> entries = goodEntries("the minutes");
        Map<String, Object> entry = (Map<String, Object>) Json.parse(
                new String(entries.get(DIR + "ledger-entry.json"), StandardCharsets.UTF_8));
        entries.put(DIR + "ledger-entry.c14n", Canonical.encode(entry));
        assertEquals(Outcome.PASSED, named(RecordLedger.check(entries), "entry recompute").outcome(),
                "the honest .c14n beside the entry");

        entries.put(DIR + "ledger-entry.c14n", Canonical.encode(Map.of("not", "the entry")));
        Outcome.Check recompute = named(RecordLedger.check(entries), "entry recompute");
        assertEquals(Outcome.FAILED, recompute.outcome(),
                "§3.2 makes every shipped .c14n a thing to check against the .json beside it, and "
                        + "only the statement's was (9-6 review, P3)");
        assertTrue(recompute.detail().contains("ledger-entry.c14n"), recompute.detail());

        Map<String, byte[]> withCheckpoint = goodEntries("the minutes");
        withCheckpoint.put(DIR + "covering-checkpoint.c14n",
                Canonical.encode(Map.of("not", "the checkpoint")));
        Outcome.Check checkpoint = named(RecordLedger.check(withCheckpoint), "checkpoint recompute");
        assertEquals(Outcome.FAILED, checkpoint.outcome(), checkpoint.detail());
        assertTrue(checkpoint.detail().contains("covering-checkpoint.c14n"), checkpoint.detail());
    }
}
