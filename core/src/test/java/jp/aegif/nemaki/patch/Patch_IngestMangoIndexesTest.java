package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins {@link Patch_IngestMangoIndexes} behaviour without needing a live
 * Cloudant. Full index-creation round-trips are exercised by the API E2E
 * suite running against a real deployment; this test just verifies the
 * graceful-degradation paths and surface invariants:
 *
 * <ul>
 *   <li>Stable patch name (PatchHistory key)</li>
 *   <li>Skips silently when connector pool / client are unavailable
 *       (e.g. during Setup Mode where nemaki_conf may not be wired yet)</li>
 *   <li>applyPerRepositoryPatch is a no-op (system-wide patch)</li>
 * </ul>
 *
 * <p>Live index creation is verified at deployment time — Cloudant
 * returns {@code result="exists"} on idempotent re-application; the
 * patch is therefore safe to run on every startup.
 */
class Patch_IngestMangoIndexesTest {

    private Patch_IngestMangoIndexes patch;
    private PatchUtil patchUtil;
    private CloudantClientPool pool;

    @BeforeEach
    void setUp() {
        patch = new Patch_IngestMangoIndexes();
        patchUtil = mock(PatchUtil.class);
        pool = mock(CloudantClientPool.class);
        patch.setPatchUtil(patchUtil);
    }

    @Test
    void patchName_isStable() {
        assertEquals("IngestMangoIndexes-20260518", patch.getName(),
                "PatchHistory key — DO NOT rename without a migration plan");
    }

    @Test
    void applyPerRepositoryPatch_isNoOp() {
        // System-wide patch — must not touch per-repo state. We just want
        // to be sure it doesn't blow up and doesn't reach for resources
        // it doesn't need.
        when(patchUtil.getConnectorPool()).thenReturn(pool);
        patch.applyPerRepositoryPatch("bedroom");
        verifyNoInteractions(pool);
    }

    @Test
    void applySystemPatch_skipsWhenConnectorPoolMissing() {
        when(patchUtil.getConnectorPool()).thenReturn(null);
        // Must NOT throw — Setup Mode can hit this before the pool is
        // wired. PatchHistory won't be marked applied (the per-repo
        // wrapper marks history only on success); the next startup
        // will retry.
        assertDoesNotThrow(() -> patch.applySystemPatch());
    }

    @Test
    void applySystemPatch_skipsWhenClientNull() {
        when(patchUtil.getConnectorPool()).thenReturn(pool);
        when(pool.getClient(any())).thenReturn(null);
        assertDoesNotThrow(() -> patch.applySystemPatch());
    }

    @Test
    void applySystemPatch_skipsWhenPoolThrows() {
        when(patchUtil.getConnectorPool()).thenReturn(pool);
        when(pool.getClient(any())).thenThrow(new RuntimeException("client init failure"));
        assertDoesNotThrow(() -> patch.applySystemPatch(),
                "pool-init exceptions must be logged not propagated");
    }

    @Test
    void applySystemPatch_clientWrapperWithoutCloudant_doesNotCrash() {
        // CloudantClientWrapper.getClient() returns null in some unit-test
        // builds. The patch should fail gracefully rather than NPE.
        when(patchUtil.getConnectorPool()).thenReturn(pool);
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(null);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        when(pool.getClient(any())).thenReturn(wrapper);
        // With a null Cloudant client the postIndex loop will throw an NPE
        // on the first iteration. The patch should catch that, log warn,
        // count it as a failure, and rethrow at the end with a descriptive
        // RuntimeException so PatchHistory does NOT mark the patch
        // applied (next startup retries).
        RuntimeException ex = assertThrows(RuntimeException.class, () -> patch.applySystemPatch());
        assertTrue(ex.getMessage().contains("index(es) failed to register"),
                "must surface failure summary, got: " + ex.getMessage());
    }
}
