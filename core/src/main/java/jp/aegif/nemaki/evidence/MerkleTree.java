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
package jp.aegif.nemaki.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The Merkle tree a checkpoint's root is computed over (P1-3 §4).
 *
 * <h2>Why a tree and not a linear chain</h2>
 *
 * <p>Design: {@code docs/design/p1-3-evidence-ledger.md}. A linear hash chain gives
 * tamper-evidence but its inclusion proof is every entry between the one being proved and the
 * checkpoint — a million entries on a million-entry ledger. A tree makes that
 * {@code O(log n)} sibling hashes, which is the difference between a proof somebody can carry
 * and one nobody will.
 *
 * <h2>Two rules that are not decoration</h2>
 *
 * <ul>
 *   <li><b>Leaves and interior nodes are domain-separated.</b> A leaf is hashed with a
 *       {@code 0x00} prefix and an interior node with {@code 0x01}. Without that, a leaf whose
 *       value happens to be a concatenation of two hashes can be presented as an interior node
 *       — a second-preimage attack that is a textbook property of naive Merkle trees (RFC 6962
 *       §2.1 spells out the same rule).</li>
 *   <li><b>An odd node is PROMOTED, never duplicated.</b> Duplicating the last node to pad a
 *       level makes two different leaf lists produce the same root (CVE-2012-2459's shape), so
 *       an attacker can construct a second list that a checkpoint appears to commit to.</li>
 * </ul>
 */
public final class MerkleTree {

    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;

    private MerkleTree() {
    }

    /** One step of an audit path: a sibling hash and which side it is on. */
    public record ProofStep(String siblingHash, boolean siblingIsLeft) {
    }

    /**
     * The root over these leaves, in this order.
     *
     * <p>An EMPTY list has no root. Returning some constant would let "nothing was recorded"
     * and "these things were recorded" be committed to by the same value.
     */
    public static String root(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            return null;
        }
        List<String> level = new ArrayList<>(leafHashes.size());
        for (String leaf : leafHashes) {
            level.add(hashLeaf(leaf));
        }
        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return level.get(0);
    }

    /**
     * The audit path proving {@code index} is the leaf at that position under {@link #root}.
     *
     * @return the sibling hashes bottom-up, or null when the index is not in the tree
     */
    public static List<ProofStep> proof(List<String> leafHashes, int index) {
        if (leafHashes == null || index < 0 || index >= leafHashes.size()) {
            return null;
        }
        List<String> level = new ArrayList<>(leafHashes.size());
        for (String leaf : leafHashes) {
            level.add(hashLeaf(leaf));
        }
        List<ProofStep> steps = new ArrayList<>();
        int position = index;
        while (level.size() > 1) {
            if (position % 2 == 0) {
                if (position + 1 < level.size()) {
                    steps.add(new ProofStep(level.get(position + 1), false));
                }
                // No sibling: this node is the odd one out and gets PROMOTED, so there is no
                // step to record. Recording a duplicate of itself here is the padding bug the
                // class javadoc refuses.
            } else {
                steps.add(new ProofStep(level.get(position - 1), true));
            }
            position /= 2;
            level = nextLevel(level);
        }
        return steps;
    }

    /** Whether {@code leafHash} with this audit path reproduces {@code expectedRoot}. */
    public static boolean verify(String leafHash, List<ProofStep> path, String expectedRoot) {
        if (leafHash == null || path == null || expectedRoot == null) {
            return false;
        }
        String current = hashLeaf(leafHash);
        for (ProofStep step : path) {
            if (step == null || step.siblingHash() == null) {
                return false;
            }
            current = step.siblingIsLeft()
                    ? hashNode(step.siblingHash(), current)
                    : hashNode(current, step.siblingHash());
        }
        return expectedRoot.equalsIgnoreCase(current);
    }

    private static List<String> nextLevel(List<String> level) {
        List<String> next = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            if (i + 1 < level.size()) {
                next.add(hashNode(level.get(i), level.get(i + 1)));
            } else {
                // Promoted, not duplicated. See the class javadoc.
                next.add(level.get(i));
            }
        }
        return next;
    }

    static String hashLeaf(String value) {
        return sha256(LEAF_PREFIX, value == null ? "" : value);
    }

    static String hashNode(String left, String right) {
        return sha256(NODE_PREFIX, (left == null ? "" : left) + (right == null ? "" : right));
    }

    private static String sha256(byte prefix, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix);
            md.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; if it is missing the deployment is broken in a
            // way no fallback should paper over.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
