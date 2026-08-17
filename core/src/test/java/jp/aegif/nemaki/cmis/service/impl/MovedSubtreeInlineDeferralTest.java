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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.epoch.AclEpochIndexWriter;
import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A move refresh forced onto a request thread must be deferred, exactly like applyACL.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Both submit sites share one executor (core=0, max=1, queue=256, CallerRunsPolicy). When the
 * queue is full, {@code CallerRunsPolicy} runs the task on the thread that submitted it — a
 * request thread. The applyACL site handles that by enqueueing the subtree root to the durable
 * reconciliation queue and returning without walking anything.
 *
 * <p>The move site computed the same condition and reported it through {@code PropagationProgress}
 * — and then walked the subtree anyway. Flagging a violation is not avoiding it: under a saturated
 * queue a move request rewrote every descendant on its own request thread, which is the precise
 * behaviour acceptance criterion 2 forbids, reached through a second entry point.
 *
 * <p>{@code InlineRefreshDeferralTest} could not catch this. It tests
 * {@code deferInlineRefreshToReconciliation} in isolation, and the bug was that one caller never
 * invoked it — a helper test passes no matter how many call sites forget to call the helper.
 *
 * <h2>Why the executor here is real</h2>
 *
 * <p>The condition is {@code Thread.currentThread() == submittingThread}, which only a genuine
 * rejection produces. Rather than simulate it with a same-thread executor, this test builds a
 * {@link ThreadPoolExecutor} shaped like production with a one-slot queue, occupies the single
 * worker and that slot, and lets the real {@code CallerRunsPolicy} hand the task back.
 */
class MovedSubtreeInlineDeferralTest {

    private static final String REPO = "bedroom";
    private static final String MOVED = "moved-folder-id";

    private AclServiceImpl svc;
    private ContentService contentService;
    private SearchIndexReconciliationService reconcile;
    private SolrUtil solrUtil;
    private ExecutorService installed;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        reconcile = mock(SearchIndexReconciliationService.class);
        solrUtil = mock(SolrUtil.class);
        ACLExpander expander = mock(ACLExpander.class);
        AclEpochIndexWriter writer = mock(AclEpochIndexWriter.class);
        try {
            lenient().when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(updated());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        NemakiCachePool cachePool = mock(NemakiCachePool.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

        ThreadLockService locks = mock(ThreadLockService.class);
        lenient().when(locks.getReadLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().readLock());
        lenient().when(locks.getWriteLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().writeLock());

        svc = spy(new AclServiceImpl());
        svc.setContentService(contentService);
        svc.setExceptionService(mock(ExceptionService.class));
        svc.setTypeManager(mock(TypeManager.class));
        svc.setThreadLockService(locks);
        svc.setNemakiCachePool(cachePool);
        svc.setCompileService(mock(CompileService.class));
        svc.setPropertyManager(mock(PropertyManager.class));
        svc.setReconciliationService(reconcile);
        // Without a writer the traversal throws before it reaches Solr, and then BOTH cases look
        // like "nothing touched the search index" — the discriminator would stop discriminating.
        svc.setAclEpochIndexWriter(writer);

        doReturn(solrUtil).when(svc).getSolrUtil();
        doReturn(expander).when(svc).getAclExpander();
        doReturn(null).when(svc).getRagIndexingService();
        lenient().when(expander.principalResolver())
                .thenReturn(mock(AclSemantics.PrincipalResolver.class));

        // The moved folder must have a child, or a traversal would have nothing to do and would
        // be indistinguishable from a deferral.
        Document child = new Document();
        child.setId("moved-child-id");
        child.setType("cmis:document");
        child.setObjectType("cmis:document");
        child.setAclInherited(true);
        child.setAcl(new jp.aegif.nemaki.model.Acl());
        lenient().when(contentService.getChildren(REPO, MOVED)).thenReturn(List.of(child));
        lenient().when(contentService.getChildren(REPO, "moved-child-id")).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        if (release != null) {
            release.countDown();
        }
        if (installed != null) {
            installed.shutdownNow();
        }
    }

    /** A successful fenced write, built the way AclServiceImplEpochWiringTest builds one. */
    private static AclEpochIndexWriter.WriteOutcome updated() throws Exception {
        var c = AclEpochIndexWriter.WriteOutcome.class.getDeclaredConstructor(
                AclEpochIndexWriter.WriteResult.class, long.class, List.class, int.class);
        c.setAccessible(true);
        return c.newInstance(AclEpochIndexWriter.WriteResult.UPDATED, 1L, List.of("user:x"), 1);
    }

    /** Production's shape, with a one-slot queue so it can be saturated deterministically. */
    private ThreadPoolExecutor saturatable() {
        return new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void install(ExecutorService ex) throws Exception {
        Field f = AclServiceImpl.class.getDeclaredField("ragAclExecutor");
        f.setAccessible(true);
        f.set(svc, ex);
        installed = ex;
    }

    private void saturate(ThreadPoolExecutor ex) throws Exception {
        release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        ex.submit(() -> {          // occupies the single worker
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        running.await(5, TimeUnit.SECONDS);
        ex.submit(() -> { });      // occupies the one queue slot
    }

    private Folder movedFolder() {
        Folder f = new Folder();
        f.setId(MOVED);
        f.setType("cmis:folder");
        f.setObjectType("cmis:folder");
        f.setAclInherited(true);
        f.setAcl(new jp.aegif.nemaki.model.Acl());
        return f;
    }

    /**
     * The fix. With the queue full, the submitted task runs on this very thread; it must hand the
     * subtree to the durable queue and walk nothing.
     */
    @Test
    void aSaturatedQueueDefersTheMovedSubtreeInsteadOfWalkingItOnTheRequestThread() throws Exception {
        ThreadPoolExecutor ex = saturatable();
        install(ex);
        saturate(ex);

        svc.refreshMovedSubtreeSearchIndexAcl(REPO, movedFolder());

        verify(reconcile).enqueueOrThrow(eq(REPO), eq(MOVED),
                eq(SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE),
                eq(SearchIndexAclReindexTask.Operation.ACL_REINDEX), anyLong());
        // Enqueueing alone would not prove the walk was skipped — code can do both. The search
        // index is the witness: every node the traversal touches goes through SolrUtil, and
        // nothing else on this path does. (getChildren cannot be the witness: the synchronous
        // cache-eviction walk that runs before the submit uses it too.)
        assertTrue(mockingDetails(solrUtil).getInvocations().isEmpty(),
                "the request thread wrote to the search index, so it walked the subtree");
    }

    /**
     * The other half: when the queue has room the task runs on a pool thread, and deferring there
     * would be wrong — it would push every ordinary move onto the reconciliation queue. Without
     * this, "always defer" would pass the test above.
     */
    @Test
    void anUnsaturatedQueueStillWalksTheSubtreeOnThePoolThread() throws Exception {
        ThreadPoolExecutor ex = saturatable();
        install(ex);

        svc.refreshMovedSubtreeSearchIndexAcl(REPO, movedFolder());
        ex.shutdown();
        ex.awaitTermination(20, TimeUnit.SECONDS);

        verify(reconcile, never()).enqueueOrThrow(anyString(), anyString(), any(), any(), anyLong());
        assertFalse(mockingDetails(solrUtil).getInvocations().isEmpty(),
                "nothing reached the search index, so this test would pass even if the pool "
                        + "thread had deferred too — the discriminator must actually discriminate");
    }
}
