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
package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Ace;

/**
 * The read-side inheritance walk must not run forever on a broken hierarchy.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code AclEffectiveEpochService} — the writer that projects the same inheritance semantics
 * into Solr — has carried a 128-hop budget and cycle detection from the start. The reader in
 * {@link AclSemantics} had neither, and nothing in {@code moveObject} stopped a folder from being
 * moved underneath itself. A single such move would make every authorization check under that
 * folder recurse until the stack overflowed: not a wrong answer, an unavailable one.
 *
 * <p>The two implementations agreeing on where they stop is the point. A cross-implementation
 * agreement IT already pins that they agree on ACL content; this pins that they agree on refusal.
 */
class AclInheritanceCycleGuardTest {

    /** A chain assembled from a parent map, so a cycle is expressible. */
    private static final class MapNode implements AclSemantics.ChainNode {
        private final String id;
        private final Map<String, String> parents;
        private final Map<String, List<Ace>> aces;

        MapNode(String id, Map<String, String> parents, Map<String, List<Ace>> aces) {
            this.id = id;
            this.parents = parents;
            this.aces = aces;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<Ace> localAces() {
            return new ArrayList<>(aces.getOrDefault(id, new ArrayList<>()));
        }

        @Override
        public jp.aegif.nemaki.model.Acl storedAcl() {
            jp.aegif.nemaki.model.Acl acl = new jp.aegif.nemaki.model.Acl();
            acl.getLocalAces().addAll(localAces());
            return acl;
        }

        @Override
        public boolean root() {
            return parents.get(id) == null;
        }

        @Override
        public boolean inherited() {
            return true;
        }

        @Override
        public String parentId() {
            return parents.get(id);
        }

        @Override
        public AclSemantics.ChainNode parent() {
            String p = parents.get(id);
            return p == null ? null : new MapNode(p, parents, aces);
        }
    }

    private static Ace ace(String principal, String permission) {
        return new Ace(principal, Arrays.asList(permission), true);
    }

    @Test
    @DisplayName("自分自身を祖先に持つフォルダは無限再帰せず fail-closed で落ちる")
    void aCycleIsRefusedRatherThanRecursedForever() {
        Map<String, String> parents = new HashMap<>();
        parents.put("a", "b");
        parents.put("b", "c");
        parents.put("c", "a"); // a → b → c → a
        Map<String, List<Ace>> aces = new HashMap<>();
        aces.put("a", Arrays.asList(ace("alice", "cmis:read")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AclSemantics.effectiveAces(new MapNode("a", parents, aces), false,
                        "GROUP_EVERYONE", "anonymous"));
        assertTrue(e.getMessage().contains("cycle"),
                "the failure must name the cycle so an operator can find the folder: " + e.getMessage());
    }

    @Test
    @DisplayName("128 hop を超える深さも fail-closed で落ちる (縮退した ACL を返さない)")
    void anOverDeepChainIsRefused() {
        Map<String, String> parents = new HashMap<>();
        int depth = AclSemantics.MAX_ANCESTOR_HOPS + 5;
        for (int i = 0; i < depth; i++) {
            parents.put("n" + i, "n" + (i + 1));
        }
        parents.put("n" + depth, null); // root
        Map<String, List<Ace>> aces = new HashMap<>();
        aces.put("n" + depth, Arrays.asList(ace("alice", "cmis:read")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AclSemantics.effectiveAces(new MapNode("n0", parents, aces), false,
                        "GROUP_EVERYONE", "anonymous"));
        assertTrue(e.getMessage().contains(String.valueOf(AclSemantics.MAX_ANCESTOR_HOPS)),
                "the failure must state the budget: " + e.getMessage());
    }

    @Test
    @DisplayName("上限ちょうどの深さは通る (境界で誤って拒否しない)")
    void aChainAtTheLimitStillResolves() {
        Map<String, String> parents = new HashMap<>();
        int depth = AclSemantics.MAX_ANCESTOR_HOPS - 1;
        for (int i = 0; i < depth; i++) {
            parents.put("n" + i, "n" + (i + 1));
        }
        parents.put("n" + depth, null);
        Map<String, List<Ace>> aces = new HashMap<>();
        aces.put("n" + depth, Arrays.asList(ace("alice", "cmis:read")));

        List<Ace> effective = AclSemantics.effectiveAces(new MapNode("n0", parents, aces), false,
                "GROUP_EVERYONE", "anonymous");
        assertEquals(1, effective.size(), "the ancestor grant must still reach the leaf");
        assertEquals("alice", effective.get(0).getPrincipalId());
    }

    @Test
    @DisplayName("正常な浅いチェーンは従来どおり合成される")
    void anOrdinaryChainIsUnaffected() {
        Map<String, String> parents = new HashMap<>();
        parents.put("leaf", "mid");
        parents.put("mid", "root");
        parents.put("root", null);
        Map<String, List<Ace>> aces = new HashMap<>();
        aces.put("root", Arrays.asList(ace("alice", "cmis:read")));
        aces.put("leaf", Arrays.asList(ace("bob", "cmis:write")));

        List<Ace> effective = AclSemantics.effectiveAces(new MapNode("leaf", parents, aces), false,
                "GROUP_EVERYONE", "anonymous");
        List<String> principals = new ArrayList<>();
        for (Ace a : effective) {
            principals.add(a.getPrincipalId());
        }
        assertTrue(principals.contains("alice") && principals.contains("bob"),
                "both the inherited and the local grant must survive: " + principals);
    }
}
