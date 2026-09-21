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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A package whose statement says what HAPPENED to the bytes (spec §5.3b), read by P1.
 *
 * <p>The kind comes from the ledger entry. A transition claims no bytes, so {@code content
 * binding} is NOT_PRESENT without a payload and FAILED with one; and a transition that copied
 * a prior digest is checked against the source it cites when the package ships it under
 * {@code prior/} — copied, so the check is that the copy and the source agree.
 */
class TransitionPackagesAreReadTest {

    private static final String ROOT = "sip/";
    private static final String DIR = ROOT + "metadata/other/nemaki-evidence/";
    private static final String AT = "2026-09-22T00:00:00Z";

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

    /** The state statement E1 recorded for the bytes, at sequence 1. */
    private static Map<String, Object> stateStatement(String payload) {
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

    private static Map<String, Object> transitionStatement(String priorDigest, Long from) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("repositoryId", "bedroom");
        doc.put("objectId", "doc-1");
        doc.put("versionObjectId", "doc-1");
        doc.put("transition", "ARCHIVE_DESTROYED");
        doc.put("bytesNow", "NONE");
        doc.put("priorContentDigest", priorDigest);
        doc.put("priorStatementEntrySequence", from);
        doc.put("recordedAt", AT);
        return doc;
    }

    private static Map<String, Object> entry(long sequence, String kind, String payloadDigest,
            String prev) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("domain", "record-content");
        entry.put("sequence", sequence);
        entry.put("subjectKind", kind);
        entry.put("subjectId", "doc-1");
        entry.put("payloadDigest", payloadDigest);
        entry.put("occurredAt", AT);
        entry.put("prevEntryHash", prev);
        entry.put("entryHash", Canonical.hash("LEDGER_ENTRY_V1", "record-content", sequence,
                kind, "doc-1", payloadDigest, AT, prev));
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
        checkpoint.put("createdAt", AT);
        checkpoint.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content",
                from, to, root, null, AT));
        return checkpoint;
    }

    /** A fixture: the prior state (entry 1) and a transition citing it (entry 2), both consistent. */
    private static final class Fixture {
        final Map<String, Object> prior = stateStatement("minutes of the meeting");
        final String priorDigest = Canonical.documentDigest(prior);
        final Map<String, Object> priorEntry = entry(1L, "RECORD_CONTENT_STATE", priorDigest, null);
        Map<String, Object> transition = transitionStatement(
                String.valueOf(prior.get("contentDigest")), 1L);
        Map<String, Object> transitionEntry;
        boolean shipPrior = true;
        String payload;

        Map<String, byte[]> entries() {
            String digest = Canonical.documentDigest(transition);
            transitionEntry = entry(2L, "RECORD_CONTENT_TRANSITION", digest,
                    String.valueOf(priorEntry.get("entryHash")));
            Map<String, Object> covering = checkpointOver(
                    String.valueOf(transitionEntry.get("entryHash")), 2L, 2L);
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("leafHash", Merkle.hashLeaf(String.valueOf(transitionEntry.get("entryHash"))));
            proof.put("steps", List.<Map<String, Object>>of());

            Map<String, byte[]> entries = new LinkedHashMap<>();
            if (payload != null) {
                entries.put(ROOT + "representations/rep1/data/minutes.txt",
                        payload.getBytes(StandardCharsets.UTF_8));
            }
            entries.put(DIR + "record-content-statement.json", bytes(json(transition)));
            entries.put(DIR + "record-content-statement.c14n", Canonical.encode(transition));
            entries.put(DIR + "ledger-entry.json", bytes(json(transitionEntry)));
            entries.put(DIR + "covering-checkpoint.json", bytes(json(covering)));
            entries.put(DIR + "inclusion-proof.json", bytes(json(proof)));
            if (shipPrior) {
                // FOUR files, which is what EvidenceBundleWriter ships (§4.2). The first
                // version of this fixture left out prior/ledger-entry.c14n and every test here
                // passed — a fixture measuring a package the product does not build (Codex,
                // second review, P2: the .c14n were shipped and never read).
                entries.put(DIR + "prior/record-content-statement.json", bytes(json(prior)));
                entries.put(DIR + "prior/record-content-statement.c14n", Canonical.encode(prior));
                entries.put(DIR + "prior/ledger-entry.json", bytes(json(priorEntry)));
                entries.put(DIR + "prior/ledger-entry.c14n", Canonical.encode(priorEntry));
            }
            return entries;
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** The verdict the CLI would give at P1: FAILED over everything, else the required set. */
    private static Outcome.Verdict p1Verdict(List<Outcome.Check> checks) {
        List<Outcome.Check> required = new ArrayList<>();
        for (String name : RecordLedger.REQUIRED) {
            required.add(named(checks, name));
        }
        return Outcome.combine(checks, required);
    }

    @Test
    @DisplayName("a consistent transition package: everything recomputes, the copy agrees, and P1 is INDETERMINATE — not FAILED")
    void aConsistentTransitionIsIndeterminateNotFailed() {
        List<Outcome.Check> checks = RecordLedger.check(new Fixture().entries());

        assertEquals(Outcome.NOT_PRESENT, named(checks, "content binding").outcome(),
                "a transition claims no bytes; with no payload there is nothing to bind, and "
                        + "that is NOT_PRESENT — reporting PASSED would say bytes were bound "
                        + "that the statement says are gone");
        assertEquals(Outcome.PASSED, named(checks, "transition continuity").outcome(),
                named(checks, "transition continuity").detail());
        for (String recomputed : List.of("statement canonical form", "entry recompute",
                "entry binds statement", "inclusion proof", "covering range",
                "checkpoint recompute")) {
            assertEquals(Outcome.PASSED, named(checks, recomputed).outcome(),
                    recomputed + ": " + named(checks, recomputed).detail());
        }
        assertEquals(Outcome.Verdict.INDETERMINATE, p1Verdict(checks),
                "a transition package cannot be VERIFIED at P1 — it has no content to bind — "
                        + "and it is not FAILED either: nothing about it is wrong");
    }

    @Test
    @DisplayName("a transition beside a payload is FAILED: the two contradict each other")
    void aTransitionWithAPayloadIsFailed() {
        Fixture fixture = new Fixture();
        fixture.payload = "minutes of the meeting";
        List<Outcome.Check> checks = RecordLedger.check(fixture.entries());

        assertEquals(Outcome.FAILED, named(checks, "content binding").outcome(),
                "the statement says the bytes were destroyed and the package carries them. "
                        + "A reader who took the payload at face value would verify content the "
                        + "ledger says is gone");
        assertEquals(Outcome.Verdict.FAILED, p1Verdict(checks));
    }

    @Test
    @DisplayName("a copied prior that disagrees with its cited source is FAILED")
    void aCopyThatDisagreesWithItsSourceIsFailed() {
        Fixture fixture = new Fixture();
        fixture.transition = transitionStatement("f".repeat(64), 1L);
        List<Outcome.Check> checks = RecordLedger.check(fixture.entries());

        Outcome.Check continuity = named(checks, "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
        assertTrue(continuity.detail().contains("copied"), continuity.detail());
        assertEquals(Outcome.Verdict.FAILED, p1Verdict(checks),
                "a wrong copy is a finding; FAILED is over the whole set (§15)");
    }

    @Test
    @DisplayName("a prior that is cited but not shipped is UNAVAILABLE with its reason code, not FAILED and not PASSED")
    void aCitedPriorNotShippedIsUnavailable() {
        Fixture fixture = new Fixture();
        fixture.shipPrior = false;
        List<Outcome.Check> checks = RecordLedger.check(fixture.entries());

        Outcome.Check continuity = named(checks, "transition continuity");
        assertEquals(Outcome.UNAVAILABLE, continuity.outcome(), continuity.detail());
        assertEquals("TRANSITION_PRIOR_NOT_IN_PACKAGE", continuity.reasonCode(),
                "the copy could not be checked; that is a different fact from the copy being "
                        + "wrong, and it has to carry the code a reader branches on");
        assertEquals(Outcome.Verdict.INDETERMINATE, p1Verdict(checks));
    }

    @Test
    @DisplayName("a transition whose prior is not known is NOT_PRESENT — 'not known' is a statement, not a defect")
    void anUnknownPriorIsNotPresent() {
        Fixture fixture = new Fixture();
        fixture.transition = transitionStatement(null, null);
        fixture.shipPrior = false;
        List<Outcome.Check> checks = RecordLedger.check(fixture.entries());

        Outcome.Check continuity = named(checks, "transition continuity");
        assertEquals(Outcome.NOT_PRESENT, continuity.outcome(), continuity.detail());
        assertNull(continuity.reasonCode());
        assertEquals(Outcome.Verdict.INDETERMINATE, p1Verdict(checks));
    }

    @Test
    @DisplayName("a prior digest without its source entry (or the reverse) is FAILED: the pair rule")
    void aHalfPairIsFailed() {
        Fixture fixture = new Fixture();
        fixture.transition = transitionStatement("a".repeat(64), null);
        assertEquals(Outcome.FAILED,
                named(RecordLedger.check(fixture.entries()), "transition continuity").outcome());

        Fixture other = new Fixture();
        other.transition = transitionStatement(null, 1L);
        assertEquals(Outcome.FAILED,
                named(RecordLedger.check(other.entries()), "transition continuity").outcome());
    }

    @Test
    @DisplayName("a shipped prior that entry 1 does not commit to is FAILED — the source must be the cited one")
    void aShippedPriorTheEntryDoesNotCommitToIsFailed() {
        Fixture fixture = new Fixture();
        Map<String, byte[]> entries = fixture.entries();
        // Another statement under prior/, with the same contentDigest: the copy would agree
        // with it, but entry 1 does not commit to it.
        Map<String, Object> other = stateStatement("minutes of the meeting");
        other.put("recordedAt", "2026-09-21T00:00:00Z");
        entries.put(DIR + "prior/record-content-statement.json", bytes(json(other)));
        entries.put(DIR + "prior/record-content-statement.c14n", Canonical.encode(other));

        Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
        assertTrue(continuity.detail().contains("commits to"), continuity.detail());
    }

    @Test
    @DisplayName("a shipped prior entry with a different sequence than the one cited is FAILED")
    void aPriorEntryWithTheWrongSequenceIsFailed() {
        Fixture fixture = new Fixture();
        fixture.transition = transitionStatement(
                String.valueOf(fixture.prior.get("contentDigest")), 7L);
        Outcome.Check continuity = named(RecordLedger.check(fixture.entries()),
                "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
        assertTrue(continuity.detail().contains("cites entry 7"), continuity.detail());
    }

    @Test
    @DisplayName("the kind comes from the entry: a transition-shaped document under a STATE entry is not read as a transition")
    void theKindComesFromTheEntry() {
        Fixture fixture = new Fixture();
        Map<String, byte[]> entries = fixture.entries();
        Map<String, Object> relabelled = entry(2L, "RECORD_CONTENT_STATE",
                Canonical.documentDigest(fixture.transition),
                String.valueOf(fixture.priorEntry.get("entryHash")));
        entries.put(DIR + "ledger-entry.json", bytes(json(relabelled)));

        List<Outcome.Check> checks = RecordLedger.check(entries);
        assertEquals(Outcome.NOT_PRESENT, named(checks, "transition continuity").outcome(),
                "the entry says STATE, so the document's keys must not promote it to a "
                        + "transition and run the continuity check");
        assertEquals(Outcome.NOT_PRESENT, named(checks, "content binding").outcome(),
                "read as a state statement it records no content digest, which is NOT_PRESENT");
        // And the state-side checks still recompute the relabelled entry, which is consistent
        // with itself; nothing here is FAILED, which is why the kind has to be the entry's.
        assertEquals(Outcome.PASSED, named(checks, "entry recompute").outcome());
    }

    /**
     * The prior's ENTRY names another record. Two arms guard this — the entry's subjectId and
     * the statement's own fields — and the first version of the fixture moved both at once, so
     * breaking either left the test green (control ND3 did not fire). Each arm now has a
     * fixture only it can catch.
     */
    @Test
    @DisplayName("a prior ENTRY about another record is FAILED, even when the statement is this record's")
    void aPriorEntryAboutAnotherRecordIsFailed() {
        Fixture fixture = new Fixture();
        Map<String, byte[]> entries = fixture.entries();
        // The statement stays doc-1 — the statement-side arm passes — and only the entry says
        // it is about doc-2.
        Map<String, Object> otherSubject = entry(1L, "RECORD_CONTENT_STATE",
                fixture.priorDigest, null);
        otherSubject.put("subjectId", "doc-2");
        otherSubject.put("entryHash", Canonical.hash("LEDGER_ENTRY_V1", "record-content", 1L,
                "RECORD_CONTENT_STATE", "doc-2", fixture.priorDigest, AT, null));
        entries.put(DIR + "prior/ledger-entry.json", bytes(json(otherSubject)));
        entries.put(DIR + "prior/ledger-entry.c14n", Canonical.encode(otherSubject));

        Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
        assertTrue(continuity.detail().contains("another record"), continuity.detail());
    }

    /**
     * The prior's STATEMENT is another record's, and the entry that commits to it says what
     * this transition says. Everything numeric agrees; two records with equal bytes are
     * ordinary (Codex, second review).
     */
    @Test
    @DisplayName("a prior STATEMENT about another record is FAILED, even when the entry names this one")
    void aPriorStatementAboutAnotherRecordIsFailed() {
        Fixture fixture = new Fixture();
        Map<String, byte[]> entries = fixture.entries();
        Map<String, Object> otherRecord = stateStatement("minutes of the meeting");
        otherRecord.put("objectId", "doc-2");
        otherRecord.put("versionObjectId", "doc-2");
        String otherDigest = Canonical.documentDigest(otherRecord);
        // subjectId stays doc-1, so the entry-side arm passes.
        Map<String, Object> entryForOther = entry(1L, "RECORD_CONTENT_STATE", otherDigest, null);
        entries.put(DIR + "prior/record-content-statement.json", bytes(json(otherRecord)));
        entries.put(DIR + "prior/record-content-statement.c14n", Canonical.encode(otherRecord));
        entries.put(DIR + "prior/ledger-entry.json", bytes(json(entryForOther)));
        entries.put(DIR + "prior/ledger-entry.c14n", Canonical.encode(entryForOther));

        Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
        assertTrue(continuity.detail().contains("different records"), continuity.detail());
    }

    @Test
    @DisplayName("a prior whose shipped .c14n is not its canonical form is FAILED")
    void aPriorWhoseCanonicalFormIsWrongIsFailed() {
        // The writer ships four files and the first version of this check read two, so the
        // other two could say anything (Codex, second review, P2).
        for (String path : List.of("prior/record-content-statement.c14n", "prior/ledger-entry.c14n")) {
            Fixture fixture = new Fixture();
            Map<String, byte[]> entries = fixture.entries();
            entries.put(DIR + path, "not the canonical form".getBytes(StandardCharsets.UTF_8));

            Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
            assertEquals(Outcome.FAILED, continuity.outcome(),
                    path + " was replaced with arbitrary bytes and continuity answered "
                            + continuity.outcome() + ": " + continuity.detail());
        }
    }

    @Test
    @DisplayName("a prior shipped without its canonical form is FAILED — prior/ is four files or none")
    void aPriorMissingItsCanonicalFormIsFailed() {
        Fixture fixture = new Fixture();
        Map<String, byte[]> entries = fixture.entries();
        entries.remove(DIR + "prior/ledger-entry.c14n");

        Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(), continuity.detail());
    }

    @Test
    @DisplayName("two evidence sections in one package FAIL at P0 — zip order must not choose the answers")
    void twoEvidenceSectionsFailAtP0() {
        // Both sections are internally consistent; they disagree about the record. Which one a
        // verifier reads was decided by the order of entries in the zip, which the party that
        // built it chooses (Codex, second review, P1).
        Map<String, byte[]> entries = new Fixture().entries();
        Map<String, byte[]> second = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            second.put(e.getKey().replace("sip/", "other-sip/"), e.getValue());
        }
        entries.putAll(second);

        Outcome.Check one = named(PackageIntegrity.check(entries), "one evidence section");
        assertEquals(Outcome.FAILED, one.outcome(),
                "a package with two evidence sections was read as one: " + one.detail());
        assertTrue(PackageIntegrity.REQUIRED.contains("one evidence section"),
                "the check is not required, so a package with two sections would still verify");
    }

    @Test
    @DisplayName("a second section NESTED inside the first is caught too — counting roots missed it")
    void aNestedSecondSectionFailsAtP0() {
        // sip/metadata/other/nemaki-evidence/prior/x/metadata/other/nemaki-evidence/... has the
        // same root as the first section, so counting directories said "one". The suffix
        // lookups match both (Codex, third review, P1).
        Map<String, byte[]> entries = new Fixture().entries();
        entries.put(DIR + "prior/x/metadata/other/nemaki-evidence/record-content-statement.json",
                bytes("{}"));

        Outcome.Check one = named(PackageIntegrity.check(entries), "one evidence section");
        assertEquals(Outcome.FAILED, one.outcome(),
                "a nested second section was not counted: " + one.detail());
    }

    @Test
    @DisplayName("a payload folder that merely looks like the evidence section does not fail the package")
    void aPayloadFolderWithASimilarNameIsNotASecondSection() {
        // Counting every path containing the directory name refused a legitimate package whose
        // payload happened to carry one (Codex, third review, P2). What matters is whether two
        // entries answer the same LOOKUP.
        Map<String, byte[]> entries = new Fixture().entries();
        entries.put(ROOT + "representations/rep1/data/metadata/other/nemaki-evidence/note.txt",
                bytes("a note that happens to live there"));

        assertEquals(Outcome.PASSED,
                named(PackageIntegrity.check(entries), "one evidence section").outcome(),
                "a payload file under a similarly named folder was read as a second evidence "
                        + "section");
    }

    @Test
    @DisplayName("a prior with no identity at all is FAILED — two absences are not the same record")
    void aPriorWithNoIdentityIsFailed() {
        // Objects.equals let two nulls agree (Codex, third review, P2). §5.3 requires text.
        Fixture fixture = new Fixture();
        fixture.transition = transitionStatement(
                String.valueOf(fixture.prior.get("contentDigest")), 1L);
        fixture.transition.put("repositoryId", null);
        fixture.transition.put("objectId", null);
        Map<String, byte[]> entries = fixture.entries();
        Map<String, Object> anonymous = stateStatement("minutes of the meeting");
        anonymous.put("repositoryId", null);
        anonymous.put("objectId", null);
        Map<String, Object> entryForIt = entry(1L, "RECORD_CONTENT_STATE",
                Canonical.documentDigest(anonymous), null);
        entries.put(DIR + "prior/record-content-statement.json", bytes(json(anonymous)));
        entries.put(DIR + "prior/record-content-statement.c14n", Canonical.encode(anonymous));
        entries.put(DIR + "prior/ledger-entry.json", bytes(json(entryForIt)));
        entries.put(DIR + "prior/ledger-entry.c14n", Canonical.encode(entryForIt));

        Outcome.Check continuity = named(RecordLedger.check(entries), "transition continuity");
        assertEquals(Outcome.FAILED, continuity.outcome(),
                "a transition and a prior that both omit their identity were read as the same "
                        + "record: " + continuity.detail());
    }

    @Test
    @DisplayName("one section, and none at all, both pass that check")
    void oneOrZeroSectionsPass() {
        assertEquals(Outcome.PASSED,
                named(PackageIntegrity.check(new Fixture().entries()), "one evidence section").outcome());
        Map<String, byte[]> legacy = new LinkedHashMap<>();
        legacy.put(ROOT + "representations/rep1/data/minutes.txt", bytes("x"));
        assertEquals(Outcome.PASSED,
                named(PackageIntegrity.check(legacy), "one evidence section").outcome(),
                "a legacy package with no evidence section was failed by a check about having "
                        + "TWO of them");
    }

    @Test
    @DisplayName("a state package reports continuity as NOT_PRESENT and is still VERIFIED at P1")
    void aStatePackageIsStillVerified() {
        // The good P1 package: a state statement bound to its payload.
        Map<String, Object> statement = stateStatement("minutes of the meeting");
        String digest = Canonical.documentDigest(statement);
        Map<String, Object> stateEntry = entry(1L, "RECORD_CONTENT_STATE", digest, null);
        Map<String, Object> covering = checkpointOver(String.valueOf(stateEntry.get("entryHash")),
                1L, 1L);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("leafHash", Merkle.hashLeaf(String.valueOf(stateEntry.get("entryHash"))));
        proof.put("steps", List.<Map<String, Object>>of());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "representations/rep1/data/minutes.txt", bytes("minutes of the meeting"));
        entries.put(DIR + "record-content-statement.json", bytes(json(statement)));
        entries.put(DIR + "record-content-statement.c14n", Canonical.encode(statement));
        entries.put(DIR + "ledger-entry.json", bytes(json(stateEntry)));
        entries.put(DIR + "covering-checkpoint.json", bytes(json(covering)));
        entries.put(DIR + "inclusion-proof.json", bytes(json(proof)));

        List<Outcome.Check> checks = RecordLedger.check(entries);
        assertEquals(Outcome.NOT_PRESENT, named(checks, "transition continuity").outcome(),
                "a state statement has nothing to be continuous with; the check is reported "
                        + "(not hidden) and does not count against the package");
        assertEquals(Outcome.Verdict.VERIFIED, p1Verdict(checks),
                "adding the continuity check made a good state package stop verifying");
    }
}
