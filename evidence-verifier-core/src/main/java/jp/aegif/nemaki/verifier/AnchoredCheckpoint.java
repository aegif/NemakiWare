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

    /**
     * The anchor material commits to the anchor target's Merkle root.
     *
     * <p>This version reads the manifest's record of what each rung holds rather than parsing
     * the DER: parsing an RFC 3161 token is P3's job and needs an ASN.1 reader. What it CAN
     * establish is whether the package claims an anchor at all, and a claim with no material is
     * reported as absent rather than passed.
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
            }
        }
        if (present.isEmpty()) {
            // Every rung absent. NOT a failure — an unanchored checkpoint is a legitimate
            // state — but it is not this profile either.
            return Outcome.Check.absent("anchor commits root",
                    "no rung carries anchor material, so nothing external commits to this "
                            + "checkpoint");
        }
        // The material is there and this version cannot read what it commits to. UNAVAILABLE,
        // not PASSED: saying the anchor commits to the root would assert something no check
        // here performed.
        return Outcome.Check.unavailable("anchor commits root", "ANCHOR_NOT_PARSED",
                "the package carries anchor material (" + present + ") and this version does "
                        + "not parse it. Whether it commits to "
                        + links.get(links.size() - 1).get("merkleRoot")
                        + " is therefore UNKNOWN, not established");
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

    private static boolean hasFile(Map<String, byte[]> entries, String relative) {
        String wanted = RecordLedger.DIR + relative;
        return entries.keySet().stream().anyMatch(path -> ("/" + path).endsWith(wanted));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> documentIn(Map<String, byte[]> entries, String name) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (("/" + entry.getKey()).endsWith(RecordLedger.DIR + name)) {
                try {
                    Object value = Json.parse(
                            new String(entry.getValue(), StandardCharsets.UTF_8));
                    return value instanceof Map ? (Map<String, Object>) value : null;
                } catch (Json.NotCanonicalisable malformed) {
                    return null;
                }
            }
        }
        return null;
    }
}
