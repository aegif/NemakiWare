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
package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The idempotency guard on system-property initialization.
 *
 * <p>The initialization runs for every system property, in every repository, on every boot,
 * and the underlying create reuses the core but always writes a new detail document. Without
 * the guard that is a leak of one detail per property per repository per boot — measured at
 * ~10,000 orphaned documents after weeks of restarts, which degraded the type list endpoint
 * (it loads every detail) to multi-second responses and intermittently broke the UI's E2E
 * networkidle waits. The guard is the difference between "ensure present" and "append forever".
 *
 * <p>The decision reads structures the caller prefetched with one view read each, so a boot
 * costs two reads per repository rather than a full-collection scan per property.
 */
class PatchServiceSystemPropertyGuardTest {

    private static final String PROP = "cmis:name";

    @Test
    @DisplayName("core と detail が揃っていれば作らない — これが毎起動の定常状態")
    void initializedStateNeedsNothing() {
        Map<String, String> cores = new HashMap<>();
        cores.put(PROP, "core-1");
        Set<String> withDetail = new HashSet<>();
        withDetail.add("core-1");

        assertFalse(PatchService.needsSystemPropertyDetail(cores, withDetail, PROP),
                "a second detail for an initialized property is the per-boot leak itself");
    }

    @Test
    @DisplayName("core はあるが detail が無ければ作る (core は再利用される)")
    void coreWithoutDetailNeedsOne() {
        Map<String, String> cores = new HashMap<>();
        cores.put(PROP, "core-1");

        assertTrue(PatchService.needsSystemPropertyDetail(cores, new HashSet<>(), PROP));
    }

    @Test
    @DisplayName("core が無ければ作る — 初回起動")
    void freshRepositoryNeedsOne() {
        assertTrue(PatchService.needsSystemPropertyDetail(new HashMap<>(), new HashSet<>(), PROP));
    }

    /**
     * The ensure-once semantics, end to end over the shared state.
     *
     * <p>This is what the leak violated: asking again after a create must say "nothing to do".
     * The caller records each created detail's core into the same set it decides from, so the
     * second pass — the next boot, or a duplicate id later in the same list — creates nothing.
     */
    @Test
    @DisplayName("作成を記録した後の再判定は「作らない」— ensure であって append ではない")
    void secondPassCreatesNothing() {
        Map<String, String> cores = new HashMap<>();
        cores.put(PROP, "core-1");
        Set<String> withDetail = new HashSet<>();

        assertTrue(PatchService.needsSystemPropertyDetail(cores, withDetail, PROP),
                "first pass over an empty repository creates");
        withDetail.add("core-1"); // what the caller records after the create
        assertFalse(PatchService.needsSystemPropertyDetail(cores, withDetail, PROP),
                "the very next ask must be a no-op, or every boot appends for ever");
    }

    @Test
    @DisplayName("入力不在は作らない (呼び出し元がエラー処理する)")
    void missingStructuresCreateNothing() {
        assertFalse(PatchService.needsSystemPropertyDetail(null, new HashSet<>(), PROP));
        assertFalse(PatchService.needsSystemPropertyDetail(new HashMap<>(), null, PROP));
    }
}
