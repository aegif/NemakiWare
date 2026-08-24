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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Merkle tree a checkpoint commits to (P1-3 §4).
 *
 * <p>Golden roots are computed OUTSIDE this codebase (python hashlib over the same rules) —
 * a vector produced by the code it pins proves nothing.
 */
class MerkleTreeTest {

    // python: leaf = sha256(b"\x00" + v), node = sha256(b"\x01" + l + r), odd node PROMOTED
    private static final String ROOT_1 =
            "022a6979e6dab7aa5ae4c3e5e45f7e977112a7e63593820dbec1ec738a24f93c";
    private static final String ROOT_2 =
            "4c64254e6636add7f281ff49278beceb26378bd0021d1809974994e6e233ec35";
    private static final String ROOT_3 =
            "506ca1fda9c643406e0ab9eb83a0c9db7dff8727f286af947eefc74aa9eb1df9";
    private static final String ROOT_4 =
            "9dc1674ae1ee61c90ba50b6261e8f9a47f7ea07d92612158edfe3c2a37c6d74c";
    private static final String ROOT_5 =
            "c9481669d120766d583b0e0980a42a8b75f9535b3630d95626550bdc70c9d19d";

    /** What duplicating the odd node would produce for three leaves — the shape we refuse. */
    private static final String ROOT_3_IF_DUPLICATED =
            "d7c20f0abcde6851382762e1059ec0a93f002a23d4a524e6696c82b7e0b15b3d";

    @Test
    @DisplayName("golden roots for 1..5 leaves match the external reference")
    void goldenRoots() {
        assertEquals(ROOT_1, MerkleTree.root(List.of("a")));
        assertEquals(ROOT_2, MerkleTree.root(List.of("a", "b")));
        assertEquals(ROOT_3, MerkleTree.root(List.of("a", "b", "c")));
        assertEquals(ROOT_4, MerkleTree.root(List.of("a", "b", "c", "d")));
        assertEquals(ROOT_5, MerkleTree.root(List.of("a", "b", "c", "d", "e")));
    }

    @Test
    @DisplayName("an odd node is PROMOTED, not duplicated — the malleability control")
    void oddNodeIsPromotedNotDuplicated() {
        // Duplicating the last node to pad a level lets two DIFFERENT leaf lists produce the
        // same root (CVE-2012-2459's shape), so an attacker can construct a second list a
        // checkpoint appears to commit to. This pins that we do not do that — the assertion is
        // against the value duplication WOULD give, so switching the implementation fails here
        // rather than silently changing every root.
        assertNotEquals(ROOT_3_IF_DUPLICATED, MerkleTree.root(List.of("a", "b", "c")),
                "the tree pads odd levels by duplicating the last node");
        assertEquals(ROOT_3, MerkleTree.root(List.of("a", "b", "c")));
    }

    @Test
    @DisplayName("leaves and interior nodes are domain-separated")
    void leavesAndNodesAreDomainSeparated() {
        // Without the prefixes, a LEAF whose value is the concatenation of two hashes can be
        // presented as an interior node — a second-preimage attack on naive Merkle trees
        // (RFC 6962 §2.1 states the same rule).
        String leftLeaf = MerkleTree.hashLeaf("a");
        String rightLeaf = MerkleTree.hashLeaf("b");
        assertNotEquals(MerkleTree.hashNode(leftLeaf, rightLeaf),
                MerkleTree.hashLeaf(leftLeaf + rightLeaf),
                "a leaf and an interior node over the same bytes hash the same, so a leaf can "
                        + "impersonate a node");
    }

    @Test
    @DisplayName("an empty list has NO root — absence is not a value")
    void emptyHasNoRoot() {
        // Returning a constant would let "nothing was recorded" and "these things were
        // recorded" be committed to by the same checkpoint value.
        assertNull(MerkleTree.root(List.of()));
        assertNull(MerkleTree.root(null));
    }

    @Test
    @DisplayName("every leaf's proof verifies against the root")
    void everyProofVerifies() {
        List<String> leaves = List.of("a", "b", "c", "d", "e");
        String root = MerkleTree.root(leaves);

        for (int i = 0; i < leaves.size(); i++) {
            List<MerkleTree.ProofStep> path = MerkleTree.proof(leaves, i);
            assertTrue(MerkleTree.verify(leaves.get(i), path, root),
                    "leaf " + i + " did not verify — an inclusion proof nobody can check is "
                            + "not a proof");
        }
    }

    @Test
    @DisplayName("a proof does NOT verify a leaf that is not there — the control")
    void aWrongLeafDoesNotVerify() {
        // Without this, a verify() that returned true unconditionally would pass the test
        // above.
        List<String> leaves = List.of("a", "b", "c", "d", "e");
        String root = MerkleTree.root(leaves);
        List<MerkleTree.ProofStep> pathForA = MerkleTree.proof(leaves, 0);

        assertFalse(MerkleTree.verify("z", pathForA, root),
                "a leaf that was never in the tree verified against its root");
        assertFalse(MerkleTree.verify(leaves.get(1), pathForA, root),
                "another leaf's audit path verified — the position is not being checked");
    }

    @Test
    @DisplayName("proof of the ODD leaf verifies too — promotion must not break the path")
    void theOddLeafProofVerifies() {
        // The promoted node contributes no sibling step. An implementation that recorded a
        // self-duplicate step here would produce a path that does not reproduce the root.
        List<String> leaves = List.of("a", "b", "c");
        assertTrue(MerkleTree.verify("c", MerkleTree.proof(leaves, 2),
                MerkleTree.root(leaves)));
    }

    @Test
    @DisplayName("reordering the leaves changes the root")
    void orderIsCommitted() {
        assertNotEquals(MerkleTree.root(List.of("a", "b")), MerkleTree.root(List.of("b", "a")),
                "the tree does not commit to the ORDER, so entries could be rearranged");
    }

    @Test
    @DisplayName("an out-of-range index has no proof")
    void outOfRangeHasNoProof() {
        assertNull(MerkleTree.proof(List.of("a", "b"), 2));
        assertNull(MerkleTree.proof(List.of("a", "b"), -1));
    }
}
