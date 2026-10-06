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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2, and the one thing it must never claim.
 *
 * <p>A chain inside the package agrees with itself whatever it holds. So the tests that matter
 * are the ones about what the chain does NOT establish: the rollback check without an external
 * checkpoint, and the anchor material this version cannot parse. Both are reported as unknown,
 * and a verifier that reported either as a pass would be asserting independence it never had.
 */
class TheChainIsWalkedNotAssumedTest {

    private static final String DIR = "sip/metadata/other/nemaki-evidence/";

    private static Map<String, Object> link(long from, long to, String root, String prev) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("domain", "record-content");
        checkpoint.put("fromSequence", from);
        checkpoint.put("toSequence", to);
        checkpoint.put("merkleRoot", root);
        checkpoint.put("prevCheckpointHash", prev);
        checkpoint.put("createdAt", "2026-09-20T00:00:00Z");
        checkpoint.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content",
                from, to, root, prev, "2026-09-20T00:00:00Z"));
        return checkpoint;
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(e.getKey()).append("\":").append(json(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(json(list.get(i)));
            }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** Two linked checkpoints, an anchor target, and a manifest with no material. */
    private static Map<String, byte[]> chainOf(List<Map<String, Object>> links) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("links", links);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "RFC3161_TSA");
        rung.put("state", "NOT_PRESENT");
        manifest.put("anchors", List.of(rung));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "checkpoint-chain.json", json(chain).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "covering-checkpoint.json",
                json(links.get(0)).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "anchor-target-checkpoint.json",
                json(links.get(links.size() - 1)).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "bundle-manifest.json",
                json(manifest).getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static List<Map<String, Object>> twoLinked() {
        Map<String, Object> first = link(1, 10, "aa", null);
        Map<String, Object> second =
                link(11, 20, "bb", String.valueOf(first.get("checkpointHash")));
        List<Map<String, Object>> links = new ArrayList<>();
        links.add(first);
        links.add(second);
        return links;
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a linked chain passes the walk, and the anchor stays UNKNOWN")
    void aLinkedChainWalksAndTheAnchorStaysUnknown() {
        List<Outcome.Check> checks = AnchoredCheckpoint.check(chainOf(twoLinked()), null);

        for (String name : List.of("chain recompute", "chain linked", "chain forward",
                "chain ends")) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(), name + ": " + checks);
        }
        Outcome.Check anchor = named(checks, "anchor commits root");
        assertEquals(Outcome.NOT_PRESENT, anchor.outcome(),
                "no rung carries material, so nothing external commits to this checkpoint. That "
                        + "is a legitimate state and it is not this profile");
        assertEquals(Outcome.Verdict.INDETERMINATE,
                Outcome.combine(checks, requiredOf(checks)),
                "a chain that agrees with itself is not an anchored checkpoint");
    }

    private static List<Outcome.Check> requiredOf(List<Outcome.Check> checks) {
        List<Outcome.Check> required = new ArrayList<>();
        for (String name : AnchoredCheckpoint.REQUIRED) {
            required.add(named(checks, name));
        }
        return required;
    }

    @Test
    @DisplayName("a broken link fails the walk")
    void aBrokenLinkFails() {
        List<Map<String, Object>> links = twoLinked();
        links.get(1).put("prevCheckpointHash", "0".repeat(64));
        // The hash is recomputed from the edited fields, so the chain is internally consistent
        // and simply does not connect. Only the linkage check sees it.
        links.set(1, link(11, 20, "bb", "0".repeat(64)));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(chainOf(links), null);

        assertEquals(Outcome.PASSED, named(checks, "chain recompute").outcome());
        assertEquals(Outcome.FAILED, named(checks, "chain linked").outcome(), checks + "");
    }

    @Test
    @DisplayName("a link whose fields moved under its hash fails the recomputation")
    void anEditedLinkFails() {
        List<Map<String, Object>> links = twoLinked();
        links.get(1).put("merkleRoot", "ff");

        assertEquals(Outcome.FAILED,
                named(AnchoredCheckpoint.check(chainOf(links), null), "chain recompute")
                        .outcome(),
                "the recorded hash is left alone and a field is moved under it — the shape a "
                        + "rewrite takes");
    }

    /** A link hashed and chained correctly over whatever values it is given. */
    private static Map<String, Object> rawLink(Object from, Object to, Object root, String prev) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("domain", "record-content");
        checkpoint.put("fromSequence", from);
        checkpoint.put("toSequence", to);
        checkpoint.put("merkleRoot", root);
        checkpoint.put("prevCheckpointHash", prev);
        checkpoint.put("createdAt", "2026-09-20T00:00:00Z");
        checkpoint.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content",
                from, to, root, prev, "2026-09-20T00:00:00Z"));
        return checkpoint;
    }

    /** covering 1..10, then {@code middle}, then a target 21..30 — every hash and link right. */
    private static List<Outcome.Check> chainThrough(Object from, Object to, Object root) {
        Map<String, Object> first = link(1, 10, "aa", null);
        Map<String, Object> middle = rawLink(from, to, root, String.valueOf(first.get("checkpointHash")));
        Map<String, Object> last = link(21, 30, "cc", String.valueOf(middle.get("checkpointHash")));
        List<Map<String, Object>> links = new ArrayList<>(List.of(first, middle, last));
        return AnchoredCheckpoint.check(chainOf(links), null);
    }

    @Test
    @DisplayName("a link whose range runs backwards fails, however well it hashes and chains")
    void aBackwardsRangeInTheMiddleFails() {
        List<Outcome.Check> checks = chainThrough(100L, 20L, "bb");

        // The walk itself is fine — linked, and every end comes after the one before — so
        // only the link's own validity (§7) can catch it (9-6 review, P1).
        assertEquals(Outcome.PASSED, named(checks, "chain linked").outcome(), checks + "");
        assertEquals(Outcome.PASSED, named(checks, "chain forward").outcome(), checks + "");
        assertEquals(Outcome.FAILED, named(checks, "chain recompute").outcome(), checks + "");
        assertTrue(named(checks, "chain recompute").detail().contains("backwards"),
                named(checks, "chain recompute").detail());
    }

    @Test
    @DisplayName("a link that commits to no root fails")
    void aLinkWithAnEmptyRootFails() {
        List<Outcome.Check> checks = chainThrough(11L, 20L, "");
        assertEquals(Outcome.FAILED, named(checks, "chain recompute").outcome(), checks + "");
        assertTrue(named(checks, "chain recompute").detail().contains("Merkle root"),
                named(checks, "chain recompute").detail());
    }

    @Test
    @DisplayName("a link whose sequence is not an integer fails, though it hashes as written")
    void aLinkWithATextSequenceFails() {
        List<Outcome.Check> checks = chainThrough("11", 20L, "bb");
        assertEquals(Outcome.FAILED, named(checks, "chain recompute").outcome(), checks + "");
        assertTrue(named(checks, "chain recompute").detail().contains("two integers"),
                named(checks, "chain recompute").detail());
    }

    @Test
    @DisplayName("a chain that stands still is not a walk forward")
    void aChainThatStandsStillFails() {
        Map<String, Object> first = link(1, 10, "aa", null);
        Map<String, Object> second =
                link(5, 10, "bb", String.valueOf(first.get("checkpointHash")));
        List<Map<String, Object>> links = new ArrayList<>();
        links.add(first);
        links.add(second);

        assertEquals(Outcome.FAILED,
                named(AnchoredCheckpoint.check(chainOf(links), null), "chain forward").outcome(),
                "two checkpoints ending at the same sequence are two answers for one period, "
                        + "and taking either is choosing which ledger to believe");
    }

    @Test
    @DisplayName("a chain that does not reach the anchor target fails at its ends")
    void aChainThatDoesNotReachTheTargetFails() {
        List<Map<String, Object>> links = twoLinked();
        Map<String, byte[]> entries = chainOf(links);
        // The package names a DIFFERENT anchor target than the one the chain ends at.
        entries.put(DIR + "anchor-target-checkpoint.json",
                json(link(21, 30, "cc", null)).getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED,
                named(AnchoredCheckpoint.check(entries, null), "chain ends").outcome(),
                "a walk that does not reach what was anchored connects the entry to nothing "
                        + "outside the package");
    }

    @Test
    @DisplayName("an empty chain is a FAILURE, not an absence")
    void anEmptyChainFails() {
        // Built by hand: chainOf needs a link for the covering/target documents, and the case
        // under test is a package that SHIPPED a chain file saying the walk has no steps.
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("links", List.of());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "checkpoint-chain.json", json(chain).getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(entries, null);
        assertEquals(Outcome.FAILED, named(checks, "chain linked").outcome(),
                "the file is there and says the walk has no steps, which cannot connect "
                        + "anything to anything. Reporting absence would make a package that "
                        + "SHIPPED an empty chain look like one that shipped none");
    }

    @Test
    @DisplayName("without an expected checkpoint, rollback is NOT_PRESENT — never passed")
    void rollbackWithoutAnExpectedCheckpointIsNotPassed() {
        Outcome.Check rollback =
                named(AnchoredCheckpoint.check(chainOf(twoLinked()), null), "rollback");

        assertEquals(Outcome.NOT_PRESENT, rollback.outcome(),
                "the package's own chain agrees with itself whatever it holds. Reporting a pass "
                        + "would claim a rollback was looked for when nothing could have been "
                        + "detected");
        assertTrue(rollback.detail().contains("outside the package"), rollback.detail());
    }

    @Test
    @DisplayName("an expected checkpoint missing from the period a complete chain presents is a FAILURE — once its toSequence places it there")
    void anExpectedCheckpointNotOnTheChainFails() {
        Outcome.Check rollback = named(
                AnchoredCheckpoint.check(chainOf(twoLinked()), "9".repeat(64), 15L), "rollback");

        assertEquals(Outcome.FAILED, rollback.outcome(),
                "the package presents a linked history over toSequence 1..20 that does not "
                        + "include the checkpoint the holder retained at 15 — which is exactly what "
                        + "a rollback or a fork looks like: " + rollback.detail());
        // The premise of the verdict: this chain's first link has no predecessor, so the
        // package presents the history from the ledger's first checkpoint.
        assertNull(twoLinked().get(0).get("prevCheckpointHash"));
    }

    @Test
    @DisplayName("an expected checkpoint missing from the chain, with no toSequence to place it, is NOT decided — never an accusation")
    void anExpectedCheckpointNotOnTheChainWithoutItsSequenceIsNotDecided() {
        Outcome.Check rollback = named(
                AnchoredCheckpoint.check(chainOf(twoLinked()), "9".repeat(64)), "rollback");

        assertEquals(Outcome.UNAVAILABLE, rollback.outcome(),
                "the hash alone does not say whether the holder's checkpoint lies after this "
                        + "package's target — a package made before the checkpoint was retained "
                        + "cannot carry it — or was removed. FAILED here accused every package "
                        + "older than the holder's record (c96 confirmation review, P2): "
                        + rollback.detail());
        assertEquals("EXPECTED_SEQUENCE_UNKNOWN", rollback.reasonCode());
        assertTrue(rollback.detail().contains("--expected-checkpoint-sequence"), rollback.detail());
    }

    @Test
    @DisplayName("an expected checkpoint after this package's anchor target is NOT decided — the package predates it")
    void anExpectedCheckpointAfterTheTargetIsNotDecided() {
        Outcome.Check rollback = named(
                AnchoredCheckpoint.check(chainOf(twoLinked()), "9".repeat(64), 25L), "rollback");

        assertEquals(Outcome.UNAVAILABLE, rollback.outcome(), rollback.detail());
        assertEquals("EXPECTED_AFTER_TARGET", rollback.reasonCode(),
                "a holder re-verifying last month's package with this week's checkpoint was told "
                        + "'rollback' (c96 confirmation review, P2): " + rollback.detail());
    }

    @Test
    @DisplayName("an expected checkpoint before the ledger's first, on a complete chain, is a FAILURE")
    void anExpectedCheckpointBeforeACompleteChainFails() {
        Outcome.Check rollback = named(
                AnchoredCheckpoint.check(chainOf(twoLinked()), "9".repeat(64), 5L), "rollback");

        assertEquals(Outcome.FAILED, rollback.outcome(),
                "the chain starts at the ledger's first checkpoint (toSequence 10) and the holder "
                        + "retained one at 5: no history has a checkpoint before its first: "
                        + rollback.detail());
    }

    @Test
    @DisplayName("a retained toSequence that disagrees with the chain's record of the same hash is a FAILURE, not a pass")
    void aRetainedSequenceThatDisagreesWithTheChainFails() {
        List<Map<String, Object>> links = twoLinked();
        String firstHash = String.valueOf(links.get(0).get("checkpointHash"));

        assertEquals(Outcome.PASSED, named(AnchoredCheckpoint.check(chainOf(links), firstHash, 10L),
                "rollback").outcome(), "the hash is on the chain at the retained toSequence");
        Outcome.Check disagreeing = named(AnchoredCheckpoint.check(chainOf(links), firstHash, 11L),
                "rollback");
        assertEquals(Outcome.FAILED, disagreeing.outcome(),
                "the holder's record says this hash closed at 11 and the package says 10: the two "
                        + "disagree about the same checkpoint, and passing on the hash alone would "
                        + "read the holder's record as confirmed: " + disagreeing.detail());
    }

    @Test
    @DisplayName("an expected checkpoint missing from a chain that starts after a predecessor is NOT decided — neither a rollback nor a pass")
    void anExpectedCheckpointBeforeAChainWithAPredecessorIsNotDecided() {
        Map<String, Object> first = link(11, 20, "bb", "a".repeat(64));
        Map<String, Object> second =
                link(21, 30, "cc", String.valueOf(first.get("checkpointHash")));
        List<Map<String, Object>> links = new ArrayList<>(List.of(first, second));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(chainOf(links), "9".repeat(64), 5L);
        Outcome.Check rollback = named(checks, "rollback");

        assertEquals(Outcome.UNAVAILABLE, rollback.outcome(),
                "the package carries the chain from the covering checkpoint forward, so a "
                        + "checkpoint the holder saw BEFORE that period cannot be on it however "
                        + "honest the ledger is. Calling that a rollback made every holder of an "
                        + "older checkpoint see exit 2 on a sound package (9-6 review, P1)");
        assertEquals("CHAIN_STARTS_AFTER_EXPECTED", rollback.reasonCode());
        assertTrue(rollback.detail().contains("a".repeat(64)), rollback.detail());
        // The walk itself is untouched: the chain is sound, only the question is undecided.
        assertEquals(Outcome.PASSED, named(checks, "chain recompute").outcome());
        assertEquals(Outcome.PASSED, named(checks, "chain linked").outcome());
    }

    @Test
    @DisplayName("an expected checkpoint with no chain to look in is NOT_PRESENT, not a rollback")
    void anExpectedCheckpointWithNoChainIsAbsentNotFailed() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        entries.remove(DIR + "checkpoint-chain.json");

        Outcome.Check rollback =
                named(AnchoredCheckpoint.check(entries, "9".repeat(64)), "rollback");

        assertEquals(Outcome.NOT_PRESENT, rollback.outcome(),
                "a package with no chain presents no history to compare against. FAILED here "
                        + "made every package without a chain a rollback the moment a holder "
                        + "supplied a checkpoint — and the chain checks beside it already say "
                        + "the package is not anchored");
    }

    @Test
    @DisplayName("with an expected checkpoint the rollback check is required; without one it is not")
    void theRollbackCheckIsRequiredExactlyWhenAnExpectedCheckpointIsSupplied() {
        assertTrue(AnchoredCheckpoint.requiredFor(true).contains("rollback"),
                "a holder who supplied a checkpoint and got 'not decided' would otherwise see "
                        + "VERIFIED — §11 lists the check as required, and the implementation "
                        + "never required it (9-6 review, P1)");
        assertFalse(AnchoredCheckpoint.requiredFor(false).contains("rollback"),
                "without an expected checkpoint the check is NOT_PRESENT by construction, and "
                        + "requiring it would make every run without one INDETERMINATE");
        assertFalse(AnchoredCheckpoint.REQUIRED.contains("rollback"));
        assertTrue(AnchoredCheckpoint.requiredFor(true).containsAll(AnchoredCheckpoint.REQUIRED));
    }

    @Test
    @DisplayName("a chain file that is not a well-formed document is FAILED for every chain check, not 'no chain'")
    void aMalformedChainIsFailedNotAbsent() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        entries.put(DIR + "checkpoint-chain.json",
                "{\"links\":[],\"links\":[]}".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(entries, null);

        for (Outcome.Check check : requiredOf(checks)) {
            assertEquals(Outcome.FAILED, check.outcome(), check.name() + ": a duplicate key "
                    + "makes the document malformed (§3.2), which is FAILED. Read as absent, a "
                    + "package that shipped a broken chain looked like one that shipped none "
                    + "and exited 3 instead of 2 (9-6 review, P1)");
            assertTrue(check.detail().contains("checkpoint-chain.json"), check.detail());
        }
        assertEquals(Outcome.NOT_PRESENT, named(checks, "rollback").outcome());
        // The absent chain stays absent — the two states are told apart.
        entries.remove(DIR + "checkpoint-chain.json");
        for (Outcome.Check check : requiredOf(AnchoredCheckpoint.check(entries, null))) {
            assertEquals(Outcome.NOT_PRESENT, check.outcome(), check.name());
        }
    }

    @Test
    @DisplayName("a covering checkpoint that is not a well-formed document fails the check that reads it — and only that one")
    void aMalformedCoveringCheckpointIsFailed() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        entries.put(DIR + "covering-checkpoint.json", "null".getBytes(StandardCharsets.UTF_8));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(entries, null);

        Outcome.Check ends = named(checks, "chain ends");
        assertEquals(Outcome.FAILED, ends.outcome(), ends.detail());
        assertTrue(ends.detail().contains("covering-checkpoint.json"), ends.detail());
        // The walk itself read only the chain, which is sound: failing it too reported defects
        // in a walk that was never hindered (c96 confirmation review, P2).
        for (String name : List.of("chain recompute", "chain linked", "chain forward")) {
            assertEquals(Outcome.PASSED, named(checks, name).outcome(), name);
        }
    }

    @Test
    @DisplayName("a malformed chain fails the rollback when a checkpoint was supplied to look for in it")
    void aMalformedChainFailsTheRollbackThatWasAsked() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        entries.put(DIR + "checkpoint-chain.json",
                "{\"links\":[],\"links\":[]}".getBytes(StandardCharsets.UTF_8));

        Outcome.Check asked = named(AnchoredCheckpoint.check(entries, "9".repeat(64)), "rollback");
        assertEquals(Outcome.FAILED, asked.outcome(),
                "the holder supplied a checkpoint and the chain it would be looked for in does "
                        + "not parse: §3.2's FAILED, not 'nothing to look in' (c96 confirmation "
                        + "review, P2)");
        Outcome.Check notAsked = named(AnchoredCheckpoint.check(entries, null), "rollback");
        assertEquals(Outcome.NOT_PRESENT, notAsked.outcome(), "no checkpoint, no question");
    }

    @Test
    @DisplayName("a manifest that is not a well-formed document fails the anchor check, not 'no manifest'")
    void aMalformedManifestFailsTheAnchorCheck() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        entries.put(DIR + "bundle-manifest.json", "[1,2]".getBytes(StandardCharsets.UTF_8));

        Outcome.Check anchor = named(AnchoredCheckpoint.check(entries, null), "anchor commits root");

        assertEquals(Outcome.FAILED, anchor.outcome(),
                "a manifest that is there and is not an object is §3.2's FAILED; as absent, a "
                        + "package whose manifest was broken answered 'no manifest' and exit 3");
        assertTrue(anchor.detail().contains("bundle-manifest.json"), anchor.detail());
        entries.remove(DIR + "bundle-manifest.json");
        assertEquals(Outcome.NOT_PRESENT,
                named(AnchoredCheckpoint.check(entries, null), "anchor commits root").outcome());
    }

    @Test
    @DisplayName("an expected checkpoint that IS on the chain passes")
    void anExpectedCheckpointOnTheChainPasses() {
        List<Map<String, Object>> links = twoLinked();
        String known = String.valueOf(links.get(0).get("checkpointHash"));

        assertEquals(Outcome.PASSED,
                named(AnchoredCheckpoint.check(chainOf(links), known), "rollback").outcome());
    }

    @Test
    @DisplayName("material recorded as an RFC 3161 token that does not parse is a FINDING, not unknown")
    void materialRecordedAsATokenThatDoesNotParseIsAFinding() {
        List<Map<String, Object>> links = twoLinked();
        Map<String, byte[]> entries = chainOf(links);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "RFC3161_TSA");
        rung.put("state", "PRESENT");
        rung.put("path", "anchors/rfc3161.der");
        manifest.put("anchors", List.of(rung));
        entries.put(DIR + "bundle-manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "anchors/rfc3161.der", new byte[] { 0x30, 0x03 });

        Outcome.Check anchor =
                named(AnchoredCheckpoint.check(entries, null), "anchor commits root");

        // Until 2026-09-22 this was UNAVAILABLE (ANCHOR_NOT_PARSED): the material was there and
        // nothing read it. Now the RFC 3161 rung IS read, and two bytes that are not a token
        // are a finding about the package — the same reading P3 makes (TheAnchorIsReadNotAssumedTest
        // holds the passing side, with a real token).
        assertEquals(Outcome.FAILED, anchor.outcome(),
                "a file recorded as an RFC 3161 token and not parsing as one was reported as "
                        + anchor.outcome() + ": " + anchor.detail());
        assertNull(anchor.reasonCode());
    }

    @Test
    @DisplayName("a rung whose material this profile does not read is UNKNOWN, never verified")
    void aPresentRungOfAKindNotReadHereIsUnknown() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "OPENTIMESTAMPS");
        rung.put("state", "PRESENT");
        rung.put("path", "anchors/ots.ots");
        manifest.put("anchors", List.of(rung));
        entries.put(DIR + "bundle-manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));
        entries.put(DIR + "anchors/ots.ots", new byte[] { 0x00, 0x4f, 0x70 });

        Outcome.Check anchor =
                named(AnchoredCheckpoint.check(entries, null), "anchor commits root");

        assertEquals(Outcome.UNAVAILABLE, anchor.outcome(),
                "OTS material is read by P4, not here. Saying it commits to the root at P2 "
                        + "would assert something no check in this profile performed");
        assertEquals("ANCHOR_NOT_PARSED", anchor.reasonCode());
    }

    @Test
    @DisplayName("a rung recorded PRESENT whose file is missing is a FAILURE")
    void aPresentRungWithNoFileFails() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "RFC3161_TSA");
        rung.put("state", "PRESENT");
        rung.put("path", "anchors/rfc3161.der");
        manifest.put("anchors", List.of(rung));
        entries.put(DIR + "bundle-manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED,
                named(AnchoredCheckpoint.check(entries, null), "anchor commits root").outcome(),
                "the manifest asserts material the package does not carry, which is the "
                        + "package contradicting itself");
    }

    /**
     * The same contradiction on a rung whose material this check does not go on to read. On an
     * RFC 3161 rung the missing token is refused a second time where the token is read, so the
     * lock above stayed green with the presence check gone — the sixth full sweep found it not
     * firing (JN3). An OpenTimestamps rung's bytes are read by its own profile, not here: only the
     * presence check stands between a missing file and "unread material", which is UNAVAILABLE.
     */
    @Test
    void aPresentRungThisCheckDoesNotReadWithNoFileFails() {
        Map<String, byte[]> entries = chainOf(twoLinked());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "OPENTIMESTAMPS");
        rung.put("state", "PRESENT");
        rung.put("path", "anchors/checkpoint.ots");
        manifest.put("anchors", List.of(rung));
        entries.put(DIR + "bundle-manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.FAILED,
                named(AnchoredCheckpoint.check(entries, null), "anchor commits root").outcome(),
                "the manifest asserts OpenTimestamps material the package does not carry — a "
                        + "contradiction, not material left for another profile to read");
    }

    @Test
    @DisplayName("a shipped .c14n beside the anchor target has to be its canonical form")
    void aShippedTargetC14nIsChecked() {
        List<Map<String, Object>> links = twoLinked();
        Map<String, byte[]> entries = chainOf(links);
        entries.put(DIR + "anchor-target-checkpoint.c14n", Canonical.encode(links.get(1)));
        assertEquals(Outcome.PASSED, named(AnchoredCheckpoint.check(entries, null), "chain ends").outcome());

        entries.put(DIR + "anchor-target-checkpoint.c14n", Canonical.encode(Map.of("x", 1L)));
        Outcome.Check ends = named(AnchoredCheckpoint.check(entries, null), "chain ends");
        assertEquals(Outcome.FAILED, ends.outcome(),
                "the target's .c14n was shipped and never compared with the .json beside it "
                        + "(§3.2; 9-6 review, P3)");
        assertTrue(ends.detail().contains("anchor-target-checkpoint.c14n"), ends.detail());
    }
}
