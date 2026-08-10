package jp.aegif.nemaki.util.lock.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.collections4.CollectionUtils;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.lock.LockAcquisitionTimeoutException;
import jp.aegif.nemaki.util.lock.LockOrderMonitor;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * Per-object read/write locks, striped.
 *
 * <h2>Striping, and what it means for lock order</h2>
 *
 * <p>There is one lock per stripe, not per object: a repository with tens of thousands of objects
 * shares {@value #STRIPES} locks between them. That is a deliberate memory trade, but it has a
 * consequence that is easy to miss when reasoning about deadlock — <b>two objects with no
 * relationship at all can be the same lock</b>. An ordering rule expressed over the folder
 * hierarchy ("always take the ancestor first") therefore cannot make acquisition safe, because the
 * pair that deadlocks need not be an ancestor and a descendant.
 *
 * <p>The order that IS safe is one defined on the stripe. {@link #orderedLocks} sorts by stripe
 * index, so any two threads asking for an overlapping set take the shared stripes in the same
 * order and cannot wait on each other. Sites that take several locks should use it.
 *
 * <p>The stripe index is computed here rather than taken from Guava's {@code Striped}: Guava
 * exposes {@code bulkGet} (which sorts) but not the index itself, and {@code bulkGet} returns
 * stripes without saying which key each came from — unusable when some of the set is wanted for
 * reading and some for writing. Owning the mapping also lets a lock report its own stripe, which
 * is what makes {@link LockOrderMonitor} able to name the pair in a finding.
 */
public class ThreadLockServiceImpl implements ThreadLockService {

	/**
	 * Must stay a power of two: the index is a mask, not a modulo.
	 *
	 * <p>Kept at the previous value so the memory and contention profile does not change with this
	 * work. The locks are now allocated eagerly rather than through weak references; 4096
	 * {@code ReentrantReadWriteLock}s is a few hundred kilobytes, and a stripe that cannot be
	 * collected is a stripe whose identity is stable — which an ordering built on the stripe index
	 * needs.
	 */
	private static final int STRIPES = 4096;
	private static final int MASK = STRIPES - 1;

	private final ReadWriteLock[] locks = new ReadWriteLock[STRIPES];

	public ThreadLockServiceImpl() {
		for (int i = 0; i < STRIPES; i++) {
			locks[i] = new ReentrantReadWriteLock();
		}
	}

	/**
	 * Which stripe a (repository, object) pair maps to.
	 *
	 * <p>The hash is spread before masking. A plain {@code hashCode() & MASK} on a string key
	 * throws away the high bits, and object ids in this system share long prefixes, so whole
	 * families of ids would land on one stripe and serialize against each other for no reason.
	 */
	public int stripeOf(String repositoryId, String objectId) {
		int h = (repositoryId == null ? 0 : repositoryId.hashCode()) * 31
				+ (objectId == null ? 0 : objectId.hashCode());
		h ^= (h >>> 20) ^ (h >>> 12);
		h ^= (h >>> 7) ^ (h >>> 4);
		return h & MASK;
	}

	private static String key(String repositoryId, String objectId) {
		return repositoryId + "/" + objectId;
	}

	@Override
	public ReadWriteLock get(String repositoryId, String objectId) {
		int stripe = stripeOf(repositoryId, objectId);
		return new MonitoredReadWriteLock(locks[stripe], stripe, key(repositoryId, objectId));
	}

	@Override
	public Lock getWriteLock(String repositoryId, String objectId) {
		return get(repositoryId, objectId).writeLock();
	}

	@Override
	public Lock getReadLock(String repositoryId, String objectId) {
		return get(repositoryId, objectId).readLock();
	}

	@Override
	public <T extends Content> List<Lock> readLocks(String repositoryId, List<T> contents) {
		if (CollectionUtils.isEmpty(contents)) {
			return new ArrayList<>();
		}
		List<String> ids = new ArrayList<>(contents.size());
		for (T content : contents) {
			ids.add(content.getId());
		}
		return orderedLocks(repositoryId, ids, false);
	}

	@Override
	public List<Lock> orderedLocks(String repositoryId, List<String> objectIds, boolean write) {
		List<Lock> result = new ArrayList<>();
		if (CollectionUtils.isEmpty(objectIds)) {
			return result;
		}
		// Sort by stripe, and drop duplicates: two ids on one stripe are ONE lock, and taking it
		// twice would be a reentrant acquisition that the matching bulkUnlock releases only once,
		// leaking a hold for the life of the thread.
		List<int[]> ordered = new ArrayList<>(objectIds.size());
		List<String> keys = new ArrayList<>(objectIds.size());
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (String id : objectIds) {
			if (id == null) {
				continue;
			}
			int stripe = stripeOf(repositoryId, id);
			if (!seen.add(stripe)) {
				continue;
			}
			ordered.add(new int[] { stripe, keys.size() });
			keys.add(key(repositoryId, id));
		}
		ordered.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] entry : ordered) {
			ReadWriteLock rw = new MonitoredReadWriteLock(locks[entry[0]], entry[0],
					keys.get(entry[1]));
			result.add(write ? rw.writeLock() : rw.readLock());
		}
		return result;
	}

	/**
	 * How long a whole set is allowed to take before the attempt is abandoned.
	 *
	 * <p>Long enough that ordinary contention — a writer finishing a document update — is simply
	 * waited out, short enough that a cycle is broken while the client is still there. The point
	 * is not to be quick; it is to be finite.
	 */
	private static final long ACQUIRE_TIMEOUT_MS = 10_000L;

	/** Attempts before the request is failed. Each releases everything it holds first. */
	private static final int ACQUIRE_ATTEMPTS = 3;

	@Override
	public void bulkLock(List<Lock> locks) {
		if (CollectionUtils.isEmpty(locks)) {
			return;
		}
		// Bounded waiting, and RELEASE EVERYTHING on the way out.
		//
		// Sorting by stripe (see orderedLocks) removes cycles among the locks in one set, but not
		// cycles that close through a lock the caller already holds from an outer scope — a
		// listing holds its folder's lock while taking its children's, and two listings whose
		// stripes overlap can then wait on each other. Nothing inside this method can see that
		// outer hold, so this method cannot order its way out of it. What it can do is refuse to
		// wait forever: whoever times out drops the set, which lets the other side finish.
		for (int attempt = 1; attempt <= ACQUIRE_ATTEMPTS; attempt++) {
			List<Lock> taken = new ArrayList<>(locks.size());
			boolean complete = true;
			for (Lock lock : locks) {
				boolean got;
				try {
					got = lock.tryLock(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					releaseQuietly(taken);
					throw new LockAcquisitionTimeoutException(
							"Interrupted while acquiring object locks");
				}
				if (!got) {
					complete = false;
					break;
				}
				taken.add(lock);
			}
			if (complete) {
				return;
			}
			// Partial holds are what make a cycle persist, so drop them before trying again.
			releaseQuietly(taken);
			if (attempt < ACQUIRE_ATTEMPTS) {
				try {
					// Both sides backing off in lockstep would re-collide forever. The jitter is
					// derived from the thread id rather than a random source so a given thread
					// backs off consistently and the behaviour stays reproducible in tests.
					Thread.sleep(20L * attempt + (Thread.currentThread().threadId() % 25));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new LockAcquisitionTimeoutException(
							"Interrupted while backing off from object lock contention");
				}
			}
		}
		throw new LockAcquisitionTimeoutException("Could not take " + locks.size()
				+ " object lock(s) within " + (ACQUIRE_TIMEOUT_MS * ACQUIRE_ATTEMPTS / 1000)
				+ "s — another request is holding one of them and may be waiting on this one."
				+ " Failing this request so both sides can make progress; retry it."
				+ " See the search-index lock-order endpoint for the pairs involved.");
	}

	private static void releaseQuietly(List<Lock> taken) {
		for (int i = taken.size() - 1; i >= 0; i--) {
			try {
				taken.get(i).unlock();
			} catch (RuntimeException ignore) {
				// Releasing what we know we took; an exception here must not mask the real one.
			}
		}
	}

	@Override
	public void bulkUnlock(List<Lock> locks) {
		// Reverse order, so a nested hold unwinds the way it was taken. With distinct stripes the
		// order does not matter; it does the moment anything nests.
		for (int i = locks.size() - 1; i >= 0; i--) {
			locks.get(i).unlock();
		}
	}

	/** Pairs a stripe's real lock with the identity needed to report an ordering problem. */
	private static final class MonitoredReadWriteLock implements ReadWriteLock {
		private final ReadWriteLock delegate;
		private final int stripe;
		private final String key;

		MonitoredReadWriteLock(ReadWriteLock delegate, int stripe, String key) {
			this.delegate = delegate;
			this.stripe = stripe;
			this.key = key;
		}

		@Override
		public Lock readLock() {
			return new MonitoredLock(delegate.readLock(), stripe, key, LockOrderMonitor.MODE_READ);
		}

		@Override
		public Lock writeLock() {
			return new MonitoredLock(delegate.writeLock(), stripe, key, LockOrderMonitor.MODE_WRITE);
		}
	}

	/**
	 * A lock that tells {@link LockOrderMonitor} what it is and when it is taken.
	 *
	 * <p>Wrapping here rather than instrumenting each call site is the point: there are more than
	 * thirty acquisition sites across the services, and a rule enforced at thirty places is a rule
	 * that will be missed at the thirty-first.
	 */
	private static final class MonitoredLock implements Lock {
		private final Lock delegate;
		private final int stripe;
		private final String key;
		private final String mode;

		MonitoredLock(Lock delegate, int stripe, String key, String mode) {
			this.delegate = delegate;
			this.stripe = stripe;
			this.key = key;
			this.mode = mode;
		}

		@Override
		public void lock() {
			// Reported BEFORE blocking: an upgrade never returns, so a report written afterwards
			// would never be written at all.
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			delegate.lock();
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			delegate.lockInterruptibly();
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		@Override
		public boolean tryLock() {
			if (delegate.tryLock()) {
				LockOrderMonitor.acquired(stripe, key, mode);
				return true;
			}
			return false;
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			if (delegate.tryLock(time, unit)) {
				LockOrderMonitor.acquired(stripe, key, mode);
				return true;
			}
			return false;
		}

		@Override
		public void unlock() {
			delegate.unlock();
			LockOrderMonitor.released(stripe);
		}

		@Override
		public Condition newCondition() {
			return delegate.newCondition();
		}
	}
}
