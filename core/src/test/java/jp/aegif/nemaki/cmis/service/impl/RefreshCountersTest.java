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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The attempt budget must be spent on nodes that are broken, not on nodes that are waiting.
 *
 * @see RefreshCounters
 */
class RefreshCountersTest {

    @Test
    @DisplayName("pending だけの失敗は「attempt を使わない」と判定される")
    void allPendingMeansRetainWithoutAnAttempt() {
        RefreshCounters c = new RefreshCounters();
        c.recordPendingBlock();
        c.recordPendingBlock();
        assertEquals(2, c.failures(), "a pending block still failed this traversal");
        assertEquals(2, c.pendingBlocks());
        assertTrue(c.blockedOnlyByPendingGates());
    }

    @Test
    @DisplayName("本物の失敗が 1 件でも混ざれば attempt を消費する")
    void oneGenuineFailureStillSpendsAnAttempt() {
        RefreshCounters c = new RefreshCounters();
        c.recordPendingBlock();
        c.recordPendingBlock();
        c.recordFailure();
        assertFalse(c.blockedOnlyByPendingGates(),
                "a genuine failure needs the attempt budget so it can eventually surface as"
                        + " terminal FAILED instead of retrying forever behind pending blocks");
    }

    @Test
    @DisplayName("失敗ゼロは pending 扱いにしない (成功を保持扱いにしない)")
    void aCleanTraversalIsNotPending() {
        assertFalse(new RefreshCounters().blockedOnlyByPendingGates());
    }

    @Test
    @DisplayName("pending の総数が JVM 単位で数えられる (管理メトリクス用)")
    void pendingBlocksAreCountedGlobally() {
        long before = RefreshCounters.pendingBlocksTotal();
        RefreshCounters c = new RefreshCounters();
        c.recordPendingBlock();
        c.recordFailure();
        assertEquals(before + 1, RefreshCounters.pendingBlocksTotal(),
                "only pending blocks advance the global counter");
    }

    /**
     * The counter is useless unless the scheduler acts on it. This pins the wiring, because the
     * bug being fixed was precisely that the scheduler could not tell the two cases apart.
     */
    @Test
    @DisplayName("scheduler が pending を専用 catch で保持し attempt を消費しない")
    void theSchedulerRetainsPendingWithoutConsumingAnAttempt() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/reconcile/SearchIndexReconciliationScheduler.java"),
                StandardCharsets.UTF_8);
        int catchAt = src.indexOf("catch (jp.aegif.nemaki.cmis.service.impl.SearchIndexRefreshPendingException");
        assertTrue(catchAt > 0, "the scheduler must catch the pending signal by type");

        String branch = src.substring(catchAt, src.indexOf("continue;", catchAt));
        assertTrue(branch.contains("retryLaterWithoutCountingAnAttempt"),
                "a pending block must not advance `attempts` toward maxAttempts");
        assertFalse(branch.contains("markFailed"),
                "a pending block must never become terminal FAILED");
    }

    @Test
    @DisplayName("pending 例外が re-drive から型のまま外に出る (generic catch に潰されない)")
    void thePendingSignalEscapesTheReDrive() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("throw new SearchIndexRefreshPendingException"),
                "reindexSearchIndexAclForObject must raise the typed signal");
        int pendingCatch = src.indexOf("AclEpochPendingException pe");
        int genericCatch = src.indexOf("catch (Exception e) {", pendingCatch);
        assertTrue(pendingCatch > 0 && pendingCatch < genericCatch,
                "the typed pending catch must precede the generic one, or it never runs");
    }
}
