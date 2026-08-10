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
package jp.aegif.nemaki.util.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A computation that began before an ACL change must not be able to publish itself after it.
 *
 * <p>The interleaving reproduced here is the one striped locks cannot exclude: a reader resolving
 * a DESCENDANT's effective ACL holds that descendant's lock, while the writer holds the changed
 * ancestor's. The eviction walk takes no locks at all. So the reader can start before the write,
 * finish after the sweep, and store an answer that nothing will remove again.
 */
class AclCacheGenerationTest {

    private static final String REPO = "repo";

    @BeforeEach
    void reset() {
        AclCacheGeneration.resetForTests();
    }

    @Test
    @DisplayName("世代が進んでいなければ公開できる")
    void anUncontendedComputationPublishes() {
        long g = AclCacheGeneration.current(REPO);
        assertTrue(AclCacheGeneration.isStillCurrent(REPO, g));
    }

    @Test
    @DisplayName("計算中に ACL 変更が入ったら公開を拒否する")
    void aComputationOvertakenByAWriteIsDeclined() {
        long g = AclCacheGeneration.current(REPO);
        AclCacheGeneration.advance(REPO); // an applyAcl landed mid-computation
        assertFalse(AclCacheGeneration.isStillCurrent(REPO, g));
    }

    @Test
    @DisplayName("世代を記録し忘れた呼び出しは fail-closed で拒否する")
    void anUndatedComputationIsRefused() {
        assertFalse(AclCacheGeneration.isStillCurrent(REPO, -1),
                "a caller that did not date its computation is exactly the one whose answer"
                        + " cannot be trusted");
    }

    @Test
    @DisplayName("リポジトリごとに独立している (片方の変更が他方を無効化しない)")
    void generationsAreScopedPerRepository() {
        long g = AclCacheGeneration.current("a");
        AclCacheGeneration.advance("b");
        assertTrue(AclCacheGeneration.isStillCurrent("a", g));
    }

    /**
     * The real interleaving, with threads: reader starts, writer advances and sweeps, reader
     * finishes and tries to publish.
     */
    @Test
    @DisplayName("退避の掃過を跨いだ読み手は、古い実効 ACL を再投入できない")
    void aReaderStraddlingTheEvictionCannotRepublish() throws Exception {
        Map<String, String> cache = new HashMap<>();
        cache.put("descendant", "OLD-EFFECTIVE-ACL");

        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            long generation = AclCacheGeneration.current(REPO);
            readerStarted.countDown();
            try {
                writeDone.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String computed = "OLD-EFFECTIVE-ACL"; // resolved from pre-change state
            if (AclCacheGeneration.isStillCurrent(REPO, generation)) {
                cache.put("descendant", computed);
            }
        }, "reader");

        Thread writer = new Thread(() -> {
            try {
                readerStarted.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            AclCacheGeneration.advance(REPO); // applyAcl commits
            cache.remove("descendant");       // the eviction walk sweeps past
            writeDone.countDown();
        }, "writer");

        reader.start();
        writer.start();
        reader.join(5000);
        writer.join(5000);

        assertNull(cache.get("descendant"),
                "the reader must NOT have put its pre-change answer back after the sweep");
    }

    @Test
    @DisplayName("読み取り経路が実際に compare-and-put を通っている")
    void theReadPathUsesTheCompareAndPut() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/AclServiceDelegate.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("putIfStillCurrent"),
                "both read-through sites must publish through the generation check");
        assertEquals(1, src.split("aclCache\\.put\\(", -1).length - 1,
                "there must be exactly ONE aclCache.put in this file — the guarded one."
                        + " A second, unguarded put would silently reopen the race");
    }

    @Test
    @DisplayName("世代を上げるのは退避の直前 (掃過を跨いだ読み手を確実に捕まえる)")
    void theGenerationAdvancesBeforeTheEvictionSweep() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java"),
                StandardCharsets.UTF_8);
        int method = src.indexOf("private int clearCachesRecursively");
        assertTrue(method > 0, "clearCachesRecursively not found");
        String body = src.substring(method, src.indexOf("\n\t}", method));
        int advance = body.indexOf("AclCacheGeneration.advance");
        int firstEvict = body.indexOf("removeCmisAndContentCache");
        assertTrue(advance > 0, "the eviction path must advance the generation");
        assertTrue(advance < firstEvict,
                "advancing AFTER the sweep would leave a window in which a straddling reader is"
                        + " still considered current");
    }
}
