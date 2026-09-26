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
    @DisplayName("an expected checkpoint that is not on the chain is a FAILURE")
    void anExpectedCheckpointNotOnTheChainFails() {
        Outcome.Check rollback = named(
                AnchoredCheckpoint.check(chainOf(twoLinked()), "9".repeat(64)), "rollback");

        assertEquals(Outcome.FAILED, rollback.outcome(),
                "the package presents a history that does not include what the holder already "
                        + "saw — which is exactly what a rollback looks like");
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
}
