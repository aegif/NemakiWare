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
}
