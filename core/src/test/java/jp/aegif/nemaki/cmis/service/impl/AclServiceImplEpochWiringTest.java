package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService;
import jp.aegif.nemaki.epoch.AclEpochIndexWriter;
import jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException;
import jp.aegif.nemaki.epoch.AclEpochState;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * Increment 12 wiring contracts on {@link AclServiceImpl} (design §11.2–§11.4), pinned with mocks:
 * WHAT goes into the CouchDB PUT, WHICH Solr writer runs under the flag, in WHAT order the
 * terminus executes, and what must propagate versus what must never fail the request.
 */
public class AclServiceImplEpochWiringTest {

    private AclServiceImpl svc;
    private ContentService contentService;
    private ExceptionService exceptionService;
    private TypeManager typeManager;
    private PropertyManager propertyManager;
    private AclEpochFinalizationService finalization;
    private AclEpochIndexWriter writer;
    private SearchIndexReconciliationService reconcile;
    private SolrUtil solrUtil;
    private ACLExpander expander;
    private NemakiCachePool cachePool;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        exceptionService = mock(ExceptionService.class);
        typeManager = mock(TypeManager.class);
        propertyManager = mock(PropertyManager.class);
        finalization = mock(AclEpochFinalizationService.class);
        writer = mock(AclEpochIndexWriter.class);
        reconcile = mock(SearchIndexReconciliationService.class);
        solrUtil = mock(SolrUtil.class);
        expander = mock(ACLExpander.class);
        cachePool = mock(NemakiCachePool.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

        ThreadLockService locks = mock(ThreadLockService.class);
        lenient().when(locks.getReadLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().readLock());
        // applyAcl takes the WRITE lock: it is a read-modify-write on the object's ACL, and a
        // shared lock let two concurrent calls compute from the same pre-state and lose one of
        // the two grants. Stubbed here so this wiring test exercises the real path.
        lenient().when(locks.getWriteLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().writeLock());

        CompileService compile = mock(CompileService.class);

        svc = spy(new AclServiceImpl());
        svc.setContentService(contentService);
        svc.setExceptionService(exceptionService);
        svc.setTypeManager(typeManager);
        svc.setThreadLockService(locks);
        svc.setNemakiCachePool(cachePool);
        svc.setCompileService(compile);
        svc.setPropertyManager(propertyManager);
        svc.setAclEpochFinalizationService(finalization);
        svc.setAclEpochIndexWriter(writer);
        svc.setReconciliationService(reconcile);

        // Deterministic seams: no Spring context in a unit test.
        doReturn(solrUtil).when(svc).getSolrUtil();
        doReturn(expander).when(svc).getAclExpander();
        doReturn(null).when(svc).getRagIndexingService();
        lenient().when(expander.principalResolver()).thenReturn(mock(
                jp.aegif.nemaki.acl.AclSemantics.PrincipalResolver.class));
    }

    private Document doc(String id) {
        Document d = new Document();
        d.setId(id);
        d.setType("cmis:document");
        d.setObjectType("cmis:document");
        d.setAclInherited(true);
        d.setAcl(new jp.aegif.nemaki.model.Acl());
        return d;
    }

    private static AclEpochIndexWriter.WriteOutcome outcome(AclEpochIndexWriter.WriteResult r)
            throws Exception {
        var c = AclEpochIndexWriter.WriteOutcome.class.getDeclaredConstructor(
                AclEpochIndexWriter.WriteResult.class, long.class, List.class, int.class);
        c.setAccessible(true);
        return c.newInstance(r, 1L, List.of("user:x"), 1);
    }

    private static AclEpochFinalizationService.FinalizeOutcome finalized(long epoch) throws Exception {
        var c = AclEpochFinalizationService.FinalizeOutcome.class.getDeclaredConstructor(
                AclEpochFinalizationService.FinalizeResult.class, Long.class);
        c.setAccessible(true);
        return c.newInstance(AclEpochFinalizationService.FinalizeResult.FINALIZED, epoch);
    }

    private org.apache.chemistry.opencmis.commons.data.Acl applyAclOn(Document content) {
        when(contentService.getContent(eq("bedroom"), eq(content.getId()))).thenReturn(content);
        var td = mock(org.apache.chemistry.opencmis.commons.definitions.TypeDefinition.class);
        when(td.isControllableAcl()).thenReturn(true);
        when(typeManager.getTypeDefinition(eq("bedroom"), any(jp.aegif.nemaki.model.Content.class)))
                .thenReturn(td);
        var acl = mock(org.apache.chemistry.opencmis.commons.data.Acl.class);
        when(acl.getAces()).thenReturn(List.of());
        lenient().when(acl.getExtensions()).thenReturn(null);
        return svc.applyAcl(mock(CallContext.class), "bedroom", content.getId(), acl,
                org.apache.chemistry.opencmis.commons.enums.AclPropagation.PROPAGATE);
    }

    // ── §11.2: Phase-1 atomicity — the marker rides the SAME PUT ───

    @Test
    public void theSAMEPutCarriesPENDINGAndAFreshMutationId() throws Exception {
        when(finalization.finalizePending(anyString(), anyString())).thenReturn(finalized(7L));
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));
        when(finalization.clearMarkerAfterReconcile(anyString(), anyString(), anyString()))
                .thenReturn(AclEpochFinalizationService.ClearResult.CLEARED);
        Document content = doc("obj-2");
        // Snapshot the carrier AT PUT TIME, not after applyAcl returns: a captor holds the live
        // object reference, so a mutation that marks AFTER the PUT would still look marked by
        // assert time. This is exactly the atomicity being tested — the marker must already be on
        // the object THE MOMENT the DAO write happens.
        final java.util.Map<String, Object>[] atPutTime = new java.util.Map[1];
        when(contentService.updateInternal(eq("bedroom"), any(), eq(true))).thenAnswer(inv -> {
            jp.aegif.nemaki.model.Content c = inv.getArgument(1);
            atPutTime[0] = c.getAclEpochFields() == null
                    ? null : new java.util.LinkedHashMap<>(c.getAclEpochFields());
            return c;
        });
        applyAclOn(content);

        var fields = atPutTime[0];
        assertNotNull(fields, "Phase 1 must ride the ACL PUT itself — a separate write is not atomic");
        assertEquals(AclEpochState.PENDING_EPOCH, fields.get(AclEpochState.FIELD_STATE));
        assertNotNull(fields.get(AclEpochState.FIELD_MUTATION_ID));

        // As-built order (two live findings): PUT → finalize → ACK → own-node write → CLEAR.
        // ACK BEFORE the write, because the §4.2 pending gate refuses FINALIZED_NEEDS_RECONCILE —
        // a write attempted between finalize and ack can never pass the walk (found on the dev
        // stack: every own-node write failed with the pending-gate message). The clear is
        // SYNCHRONOUS (flag-ON TCK finding): a deferred async clear raced the next mutation's
        // Phase-1 PUT as "Document update conflict".
        InOrder order = inOrder(contentService, finalization, writer);
        order.verify(contentService).updateInternal(eq("bedroom"), any(), eq(true));
        order.verify(finalization).finalizePending("bedroom", "obj-2");
        order.verify(finalization).ackFinalized("bedroom", "obj-2");
        order.verify(writer).writeAllowingBootstrap(eq("bedroom"), eq("obj-2"), any(), any());
        order.verify(finalization).clearMarkerAfterReconcile(eq("bedroom"), eq("obj-2"), anyString());

        // TERMINUS half 2 (async): the task is consumed AFTER the clear — D5's order preserved
        // across the two halves. Timeout because the settle runs on the refresh executor.
        verify(reconcile, org.mockito.Mockito.timeout(5000)).settleIfCovered("bedroom", "obj-2", 7L);
    }

    /**
     * When the marker was NOT cleared (here: the own-node write failed, so the clear is skipped),
     * the async half must NOT consume the task — doing so strands a RECONCILE_ENQUEUED document
     * that nothing can ever clear. Observed LIVE when the gate bug made every own-node write fail:
     * the settle still ran and the marker was orphaned.
     */
    @Test
    public void anUnclearedMarkerKeepsTheTask() throws Exception {
        when(finalization.finalizePending(anyString(), anyString())).thenReturn(finalized(7L));
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("pending gate"));
        Document content = doc("obj-9");
        applyAclOn(content);

        verify(finalization, never()).clearMarkerAfterReconcile(anyString(), anyString(), anyString());
        verify(reconcile, org.mockito.Mockito.after(1500).never())
                .settleIfCovered(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** An epoch-cycle failure must NEVER fail the mutation request (§11.4: the ACL is committed). */
    @Test
    public void aFinalizeFailureDoesNotFailApplyAcl() {
        when(finalization.finalizePending(anyString(), anyString()))
                .thenThrow(new RuntimeException("couch down"));
        Document content = doc("obj-3");
        applyAclOn(content); // must not throw
        verify(contentService).updateInternal(eq("bedroom"), any(), eq(true));
    }

    // ── §11.3: the Solr cutover dispatch ───────────────────────────

    @Test
    public void everyAclGroupWriteGoesThroughTheEpochWriter() throws Exception {
        Document content = doc("obj-4");
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        svc.writeContentReaders(solrUtil, "bedroom", content, null);

        verify(writer).writeAllowingBootstrap(eq("bedroom"), eq("obj-4"), any(), any());
        // Increment 14: the pre-epoch generation fence is GONE — there is no second ACL writer to
        // fall back to, so this is the only path an ACL group can be written through.
    }

    @Test
    public void dispatch_NOT_INDEXEDFallsBackToTheStrictFullIndex() throws Exception {
        Document content = doc("obj-5");
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.NOT_INDEXED));
        svc.writeContentReaders(solrUtil, "bedroom", content, null);
        verify(solrUtil).indexDocument(eq("bedroom"), eq(content), eq(true), eq(true), any(), eq(true));
    }

    // ── §11.4: terminus order (approved D5, split across the two halves) ──

    /**
     * The async tail consumes the TASK ONLY — it must never touch the content document. Clearing
     * the marker here is what raced the next mutation's Phase-1 PUT (the flag-ON TCK caught it as
     * "Document update conflict"), so this binding is the fix's regression guard.
     */
    @Test
    public void theAsyncSettleConsumesTheTaskAndNeverTouchesTheMarker() {
        svc.settleEpochObligationAfterRefresh("bedroom", "obj-6", 7L, "mid-1");

        verify(reconcile).settleIfCovered("bedroom", "obj-6", 7L);
        verify(finalization, never()).clearMarkerAfterReconcile(anyString(), anyString(), anyString());
    }

    // ── §11.4: the re-drive terminus ───────────────────────────────

    private Document reDriveContent(String state) {
        Document content = doc("obj-7");
        content.putAclEpochField(AclEpochState.FIELD_STATE, state);
        content.putAclEpochField(AclEpochState.FIELD_MUTATION_ID, "mid-7");
        when(contentService.getContent("bedroom", "obj-7")).thenReturn(content);
        lenient().when(contentService.getRelationsipsOfObject(anyString(), anyString(), any()))
                .thenReturn(List.of());
        return content;
    }

    @Test
    public void aCleanReDriveClearsTheMarkerScopedToThePreReadMutationId() throws Exception {
        reDriveContent(AclEpochState.RECONCILE_ENQUEUED);
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));

        assertTrue(svc.reindexSearchIndexAclForObject("bedroom", "obj-7"));
        verify(finalization).clearMarkerAfterReconcile("bedroom", "obj-7", "mid-7");
    }

    /**
     * A clear FAILURE must make the re-drive NOT clean: completing the task while the marker
     * survives strands a RECONCILE_ENQUEUED document with no task — §11.6 row 5's inverse, the
     * exact shape D5 exists to prevent.
     */
    @Test
    public void aFailedClearKeepsTheTask() throws Exception {
        reDriveContent(AclEpochState.RECONCILE_ENQUEUED);
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenReturn(outcome(AclEpochIndexWriter.WriteResult.UPDATED));
        when(finalization.clearMarkerAfterReconcile(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("contention"));

        assertFalse(svc.reindexSearchIndexAclForObject("bedroom", "obj-7"),
                "completing the task while the marker survives strands the outbox");
    }

    // ── §5.1 via §11.3: quarantine blocks PROPAGATE out of the re-drive ──

    @Test
    public void aQuarantineBlockPropagatesToTheSchedulerInsteadOfBurningAttempts() throws Exception {
        reDriveContent(AclEpochState.RECONCILE_ENQUEUED);
        when(writer.writeAllowingBootstrap(anyString(), anyString(), any(), any()))
                .thenThrow(new AclEpochQuarantineBlockedException("blocked", "anc-1"));

        assertThrows(AclEpochQuarantineBlockedException.class,
                () -> svc.reindexSearchIndexAclForObject("bedroom", "obj-7"),
                "flattened into a counted failure it would burn attempts toward terminal-FAILED");
    }
}
