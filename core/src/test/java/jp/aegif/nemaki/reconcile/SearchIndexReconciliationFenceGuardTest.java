package jp.aegif.nemaki.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the cooperative-fencing guard
 * ({@link SearchIndexReconciliationService#fenceGuard}), which must FAIL-CLOSED: once the
 * reconciliation lease can no longer be proven held — whether {@code renewLeaseIfNeeded}
 * returns {@code false} (a clean reclaim) OR THROWS (a CouchDB timeout that could hide a
 * reclaim) — the guard must latch {@code false} permanently so a worker never resumes
 * writing on an unprovable lease. No CouchDB needed: {@code renewLeaseIfNeeded} is
 * overridden to drive the guard deterministically (it is the ONLY thing the guard calls).
 */
public class SearchIndexReconciliationFenceGuardTest {

    private static SearchIndexAclReindexTask task() {
        SearchIndexAclReindexTask t = new SearchIndexAclReindexTask();
        t.setTaskId("t-1");
        return t;
    }

    @Test
    public void guardLatchesFalseAndStopsCallingRenewWhenRenewThrows() {
        final AtomicInteger renewCalls = new AtomicInteger(0);
        SearchIndexReconciliationService svc = new SearchIndexReconciliationService() {
            @Override
            public boolean renewLeaseIfNeeded(SearchIndexAclReindexTask t, long leaseMillis) {
                renewCalls.incrementAndGet();
                throw new RuntimeException("simulated CouchDB timeout during lease renewal");
            }
        };
        BooleanSupplier guard = svc.fenceGuard(task(), 1000L);

        assertFalse(guard.getAsBoolean(), "a renewal that THROWS must be treated as lease-lost (false)");
        assertFalse(guard.getAsBoolean(), "the guard must stay false (latched) after a throw");
        assertEquals(1, renewCalls.get(),
                "once latched false, the guard must NOT call renew again (a later transient success "
                + "must not resurrect a lost lease)");
    }

    @Test
    public void guardLatchesFalseWhenRenewReturnsFalse() {
        final AtomicInteger renewCalls = new AtomicInteger(0);
        SearchIndexReconciliationService svc = new SearchIndexReconciliationService() {
            @Override
            public boolean renewLeaseIfNeeded(SearchIndexAclReindexTask t, long leaseMillis) {
                renewCalls.incrementAndGet();
                return false;
            }
        };
        BooleanSupplier guard = svc.fenceGuard(task(), 1000L);

        assertFalse(guard.getAsBoolean());
        assertFalse(guard.getAsBoolean());
        assertEquals(1, renewCalls.get(), "guard must latch and not re-call renew after a false");
    }

    @Test
    public void guardReportsHeldWhileRenewSucceeds() {
        SearchIndexReconciliationService svc = new SearchIndexReconciliationService() {
            @Override
            public boolean renewLeaseIfNeeded(SearchIndexAclReindexTask t, long leaseMillis) {
                return true;
            }
        };
        BooleanSupplier guard = svc.fenceGuard(task(), 1000L);

        assertTrue(guard.getAsBoolean());
        assertTrue(guard.getAsBoolean(), "while the lease is held every checkpoint returns true");
    }

    @Test
    public void guardNeverRecoversAfterASingleLoss() {
        // Renew is lost once, then would "succeed" again — the guard must stay closed.
        final AtomicInteger n = new AtomicInteger(0);
        SearchIndexReconciliationService svc = new SearchIndexReconciliationService() {
            @Override
            public boolean renewLeaseIfNeeded(SearchIndexAclReindexTask t, long leaseMillis) {
                return n.incrementAndGet() != 1; // first call false, subsequent would be true
            }
        };
        BooleanSupplier guard = svc.fenceGuard(task(), 1000L);

        assertFalse(guard.getAsBoolean(), "first checkpoint loses the lease");
        assertFalse(guard.getAsBoolean(), "must NOT recover even though renew would now return true");
        assertEquals(1, n.get(), "latched: renew is not consulted again");
    }
}
