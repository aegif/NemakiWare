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

	private static final org.slf4j.Logger LOG =
			org.slf4j.LoggerFactory.getLogger(ThreadLockServiceImpl.class);

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
	 * How long ONE attempt at a set may take before everything held is dropped and it is tried
	 * again.
	 *
	 * <p>This is the cycle breaker, and it is the release rather than the delay that breaks it: a
	 * partial hold is what lets a cycle persist, so letting go periodically lets the other side
	 * finish. It is a budget for the whole set, not per lock — per lock would let a two-hundred-row
	 * page take two hundred times as long, which under shifting contention is indistinguishable
	 * from the hang this exists to prevent.
	 */
	private static final long ACQUIRE_ATTEMPT_MS = 30_000L;

	/**
	 * How long a set may keep retrying before the request is failed.
	 *
	 * <p>Separate from the attempt budget on purpose, and the reason is a mistake made here first:
	 * with a fixed three attempts the concurrency suite began failing ordinary reads, because a
	 * folder under sustained write load takes longer than three short attempts to read — nothing
	 * was deadlocked, it was busy. Retrying is how a cycle is broken; the total is how long a
	 * caller is willing to be busy. Conflating them makes contention look like deadlock.
	 */
	private static final long ACQUIRE_TOTAL_MS = 300_000L;

	/**
	 * The bound on a SINGLE lock, which is a deadlock breaker and nothing else.
	 *
	 * <p>Deliberately far above any realistic wait. A set can be abandoned and retried cheaply, so
	 * a short budget there is a reasonable back-off; a single lock has no alternative to waiting,
	 * and cutting it short converts ordinary contention into failed requests. That is not
	 * hypothetical — at 30s the concurrency suite began failing document creates, because a
	 * hundred threads writing into one folder legitimately queue on that folder for longer than
	 * that. A deadlock, by contrast, is permanent, so any finite bound breaks it; five minutes
	 * separates "this will never finish" from "this is busy".
	 *
	 * <p>Anything approaching it is logged well before it expires, so heavy contention is visible
	 * without being fatal.
	 */
	private static final long SINGLE_LOCK_DEADLINE_MS = 300_000L;

	/** How long a single acquisition may take before it is worth telling an operator about. */
	private static final long SINGLE_LOCK_WARN_MS = 15_000L;

	@Override
	public void bulkLock(List<Lock> locks) {
		if (CollectionUtils.isEmpty(locks)) {
			return;
		}
		// Bounded waiting, and RELEASE EVERYTHING between attempts.
		//
		// Sorting by stripe (see orderedLocks) removes cycles among the locks in one set, but not
		// cycles that close through a lock the caller already holds from an outer scope — a
		// descendants walk holds each level while it takes the next. Nothing inside this method
		// can see that outer hold, so this method cannot order its way out of it. What it can do
		// is let go periodically: whoever releases lets the other side proceed.
		long giveUpAt = System.nanoTime() + ACQUIRE_TOTAL_MS * 1_000_000L;
		boolean warned = false;
		for (int attempt = 1;; attempt++) {
			long attemptDeadline = System.nanoTime() + ACQUIRE_ATTEMPT_MS * 1_000_000L;
			List<Lock> taken = new ArrayList<>(locks.size());
			boolean complete = true;
			for (Lock lock : locks) {
				long remaining = attemptDeadline - System.nanoTime();
				boolean got;
				try {
					got = remaining > 0 && lock.tryLock(remaining, TimeUnit.NANOSECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					releaseQuietly(taken);
					throw new LockAcquisitionTimeoutException(
							"Interrupted while acquiring object locks", describe(locks));
				}
				if (!got) {
					complete = false;
					break;
				}
				taken.add(lock);
			}
			if (complete) {
				if (warned) {
					LOG.warn("Took {} object lock(s) after {} attempt(s)", locks.size(), attempt);
				}
				return;
			}
			// Partial holds are what make a cycle persist, so drop them before trying again.
			releaseQuietly(taken);
			if (System.nanoTime() >= giveUpAt) {
				break;
			}
			if (!warned) {
				warned = true;
				// "Busy", not "broken". Only the throw below means the latter.
				LOG.warn("Could not take {} object lock(s) in {}s; releasing and retrying up to"
						+ " {}s in total. Objects: {}", locks.size(), ACQUIRE_ATTEMPT_MS / 1000,
						ACQUIRE_TOTAL_MS / 1000, describe(locks));
			}
			try {
				// Both sides backing off in lockstep would re-collide forever. The jitter is
				// derived from the thread id rather than a random source so a given thread backs
				// off consistently and the behaviour stays reproducible in tests.
				Thread.sleep(20L * Math.min(attempt, 10)
						+ (Thread.currentThread().threadId() % 25));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LockAcquisitionTimeoutException(
						"Interrupted while backing off from object lock contention",
						describe(locks));
			}
		}
		throw new LockAcquisitionTimeoutException("Could not take " + locks.size()
				+ " object lock(s) within " + (ACQUIRE_TOTAL_MS / 1000)
				+ "s — another request holds one of them and is very likely waiting on this one."
				+ " Failing so both sides can make progress; this request is safe to retry."
				+ " Objects: " + describe(locks), describe(locks));
	}

	/** The keys behind a list of locks, so a failure names them instead of counting them. */
	private static List<String> describe(List<Lock> locks) {
		List<String> keys = new ArrayList<>(locks.size());
		for (Lock lock : locks) {
			keys.add(lock instanceof MonitoredLock ? ((MonitoredLock) lock).key : "?");
		}
		return keys;
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
		//
		// Safe to call after a FAILED bulkLock: each wrapper knows whether it was actually taken,
		// so the finally-block every caller writes cannot turn a timeout into an
		// IllegalMonitorStateException and lose the reason.
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
		final String key;
		private final String mode;
		/**
		 * How many times THIS wrapper actually took the lock.
		 *
		 * <p>Callers universally write {@code lock(); try { ... } finally { unlock(); }}. When the
		 * acquisition fails, that {@code finally} still runs — and unlocking something never taken
		 * throws {@code IllegalMonitorStateException}, which would replace the timeout with a
		 * meaningless error and lose the diagnosis. Counting here makes the release tolerant
		 * without making every caller aware of it.
		 */
		private int holds;

		MonitoredLock(Lock delegate, int stripe, String key, String mode) {
			this.delegate = delegate;
			this.stripe = stripe;
			this.key = key;
			this.mode = mode;
		}

		@Override
		public void lock() {
			// BOUNDED, even for a single lock.
			//
			// Ordering a set cannot help a cycle formed by two single acquisitions — a delete
			// holding a parent and reaching for a child, against a move holding the child and
			// reaching for the parent. Those never enter bulkLock, so a bound that lived only
			// there would leave the repository just as capable of stopping. A bound here covers
			// every acquisition in the system, including the ones nobody has converted yet.
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			boolean got;
			long startedAt = System.nanoTime();
			try {
				got = delegate.tryLock(SINGLE_LOCK_WARN_MS, TimeUnit.MILLISECONDS);
				if (!got) {
					// Not a failure yet — say so, and keep waiting up to the deadlock bound. The
					// distinction matters: this line means "busy", and only the throw below means
					// "this was never going to finish".
					LOG.warn("Waiting over {}s for the {} lock on {} — heavy contention. Will give"
							+ " up at {}s so a deadlock cannot hold this request forever.",
							SINGLE_LOCK_WARN_MS / 1000, mode, key,
							SINGLE_LOCK_DEADLINE_MS / 1000);
					got = delegate.tryLock(SINGLE_LOCK_DEADLINE_MS - SINGLE_LOCK_WARN_MS,
							TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LockAcquisitionTimeoutException(
						"Interrupted while acquiring " + key, List.of(key));
			}
			if (!got) {
				throw new LockAcquisitionTimeoutException("Could not take the " + mode
						+ " lock on " + key + " within " + (SINGLE_LOCK_DEADLINE_MS / 1000)
						+ "s — another request holds it and is very likely waiting on something"
						+ " this request holds. Failing so both sides can make progress; this"
						+ " request is safe to retry.", List.of(key));
			}
			if (System.nanoTime() - startedAt > SINGLE_LOCK_WARN_MS * 1_000_000L) {
				LOG.warn("Took the {} lock on {} after {}ms of waiting", mode, key,
						(System.nanoTime() - startedAt) / 1_000_000L);
			}
			holds++;
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			delegate.lockInterruptibly();
			holds++;
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		@Override
		public boolean tryLock() {
			if (delegate.tryLock()) {
				holds++;
				LockOrderMonitor.acquired(stripe, key, mode);
				return true;
			}
			return false;
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			if (delegate.tryLock(time, unit)) {
				holds++;
				LockOrderMonitor.acquired(stripe, key, mode);
				return true;
			}
			return false;
		}

		@Override
		public void unlock() {
			if (holds <= 0) {
				// Never taken (or already released): a caller's finally-block running after a
				// failed acquisition. Silently correct — see the `holds` field.
				return;
			}
			holds--;
			delegate.unlock();
			LockOrderMonitor.released(stripe);
		}

		@Override
		public Condition newCondition() {
			return delegate.newCondition();
		}
	}
}
