package jp.aegif.nemaki.util.lock.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
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
 * Per-object read/write locks, striped, with every acquisition bounded.
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
 * <h2>Named locks</h2>
 *
 * <p>A key of the form {@code __name__} is a NAMED lock: it gets a dedicated lock outside the
 * stripe array instead of hashing into it. The repository-wide folder-hierarchy lock is one.
 * Hashing it into the stripes would silently make it the same lock as ~1/4096 of all real
 * objects, and a lock that is deliberately taken FIRST and held across an ordered set must not be
 * a lock that also appears INSIDE other requests' ordered sets — that is the outer-hold shape
 * that took the repository down. A dedicated instance removes the collision by construction.
 *
 * <h2>Bounds</h2>
 *
 * <p>Every acquisition path is time-bounded, so a cycle — through any pair of sites, converted or
 * not — ends as one failed, retryable request instead of a stopped repository. The bounds
 * distinguish retrying (which breaks cycles, because everything held is released between
 * attempts) from total patience (how long ordinary contention is waited out); conflating the two
 * made contention look like deadlock twice while this was being built. A read-to-write upgrade on
 * a stripe the thread does not also hold for writing can never succeed, so it is refused
 * immediately rather than after the bound.
 */
public class ThreadLockServiceImpl implements ThreadLockService {

	/**
	 * Must stay a power of two: the index is a mask, not a modulo.
	 *
	 * <p>Kept at the previous value so the memory and contention profile does not change with this
	 * work. The locks are allocated eagerly; 4096 {@code ReentrantReadWriteLock}s is a few hundred
	 * kilobytes, and a stripe that cannot be collected is a stripe whose identity is stable —
	 * which an ordering built on the stripe index needs.
	 */
	private static final int STRIPES = 4096;
	private static final int MASK = STRIPES - 1;

	private static final org.slf4j.Logger LOG =
			org.slf4j.LoggerFactory.getLogger(ThreadLockServiceImpl.class);

	/** At most one contention WARN per stripe per this interval — hot stripes must not flood. */
	private static final long WARN_INTERVAL_MS = 60_000L;
	private static final AtomicLongArray WARN_STAMPS = new AtomicLongArray(STRIPES);

	private final ReadWriteLock[] locks = new ReadWriteLock[STRIPES];

	/** Dedicated locks for {@code __name__} keys, with monitor identities above the stripe range. */
	private final ConcurrentHashMap<String, NamedLock> namedLocks = new ConcurrentHashMap<>();
	private final AtomicInteger nextNamedStripe = new AtomicInteger(STRIPES);

	private record NamedLock(ReadWriteLock lock, int stripeId) {
	}

	/** One attempt at a whole set; everything held is released when it expires. */
	private final long acquireAttemptMs;
	/** Total patience for a set across attempts. */
	private final long acquireTotalMs;
	/** The bound on a single lock — a deadlock breaker, far above any realistic wait. */
	private final long singleLockDeadlineMs;
	/** When a single acquisition is worth telling an operator about (not a failure). */
	private final long singleLockWarnMs;

	public ThreadLockServiceImpl() {
		// 30s per set attempt / 300s total / 300s single / 15s warn. The single-lock bound is
		// deliberately the same as the set total: a single lock has no alternative to waiting,
		// and cutting it short converts ordinary contention into failed requests — at 30s the
		// concurrency suite failed document creates, because a hundred threads writing into one
		// folder legitimately queue on that folder for longer than that. A deadlock, by contrast,
		// is permanent, so any finite bound breaks it.
		this(30_000L, 300_000L, 300_000L, 15_000L);
	}

	/**
	 * Bound-tuning constructor.
	 *
	 * <p>Public because the timeout behaviour is untestable at production values — a test cannot
	 * wait five minutes to see a throw — and a bound that has never been seen to fire is exactly
	 * the kind of net that turns out to be missing when it is finally needed. Production wiring
	 * uses the default constructor.
	 */
	public ThreadLockServiceImpl(long acquireAttemptMs, long acquireTotalMs,
			long singleLockDeadlineMs, long singleLockWarnMs) {
		this.acquireAttemptMs = acquireAttemptMs;
		this.acquireTotalMs = acquireTotalMs;
		this.singleLockDeadlineMs = singleLockDeadlineMs;
		this.singleLockWarnMs = Math.min(singleLockWarnMs, singleLockDeadlineMs);
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

	private static boolean isNamedKey(String objectId) {
		return objectId != null && objectId.length() > 4
				&& objectId.startsWith("__") && objectId.endsWith("__");
	}

	@Override
	public ReadWriteLock get(String repositoryId, String objectId) {
		if (isNamedKey(objectId)) {
			NamedLock named = namedLocks.computeIfAbsent(key(repositoryId, objectId),
					k -> new NamedLock(new ReentrantReadWriteLock(),
							nextNamedStripe.getAndIncrement()));
			return new MonitoredReadWriteLock(named.lock(), named.stripeId(),
					key(repositoryId, objectId));
		}
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
		// leaking a hold for the life of the thread. Named keys sort after all object stripes.
		List<int[]> ordered = new ArrayList<>(objectIds.size());
		List<ReadWriteLock> raw = new ArrayList<>(objectIds.size());
		List<String> keys = new ArrayList<>(objectIds.size());
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (String id : objectIds) {
			if (id == null) {
				continue;
			}
			int stripe;
			ReadWriteLock lock;
			if (isNamedKey(id)) {
				NamedLock named = namedLocks.computeIfAbsent(key(repositoryId, id),
						k -> new NamedLock(new ReentrantReadWriteLock(),
								nextNamedStripe.getAndIncrement()));
				stripe = named.stripeId();
				lock = named.lock();
			} else {
				stripe = stripeOf(repositoryId, id);
				lock = locks[stripe];
			}
			if (!seen.add(stripe)) {
				continue;
			}
			ordered.add(new int[] { stripe, keys.size() });
			raw.add(lock);
			keys.add(key(repositoryId, id));
		}
		ordered.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] entry : ordered) {
			ReadWriteLock rw = new MonitoredReadWriteLock(raw.get(entry[1]), entry[0],
					keys.get(entry[1]));
			result.add(write ? rw.writeLock() : rw.readLock());
		}
		return result;
	}

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
		long giveUpAt = System.nanoTime() + acquireTotalMs * 1_000_000L;
		boolean warned = false;
		for (int attempt = 1;; attempt++) {
			// Each attempt is capped by the TOTAL deadline too, so the last attempt cannot run
			// past it — without the cap an attempt started just before the total expired would
			// overshoot the documented bound by a whole attempt.
			long attemptDeadline = Math.min(
					System.nanoTime() + acquireAttemptMs * 1_000_000L, giveUpAt);
			List<Lock> taken = new ArrayList<>(locks.size());
			boolean complete = true;
			try {
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
			} catch (LockAcquisitionTimeoutException e) {
				// tryLock refuses a certain self-deadlock (this thread already reads one of the
				// stripes and the set wants to write it) by throwing. Waiting out the total budget
				// on an acquisition that can never succeed would be worse than failing now — but
				// the partial holds must not leak on the way out.
				releaseQuietly(taken);
				throw e;
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
						+ " {}s in total. Objects: {}", locks.size(), acquireAttemptMs / 1000,
						acquireTotalMs / 1000, describe(locks));
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
				+ " object lock(s) within " + (acquireTotalMs / 1000)
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

	/** Test seam: the key a monitored lock was built for, or null for foreign locks. */
	static String keyOf(Lock lock) {
		return lock instanceof MonitoredLock ? ((MonitoredLock) lock).key : null;
	}

	/** Test seam: the stripe identity a monitored lock reports to the monitor, or -1. */
	static int stripeIdOf(Lock lock) {
		return lock instanceof MonitoredLock ? ((MonitoredLock) lock).stripe : -1;
	}

	/** Pairs a stripe's real lock with the identity needed to report an ordering problem. */
	private final class MonitoredReadWriteLock implements ReadWriteLock {
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
	 * A lock that tells {@link LockOrderMonitor} what it is and when it is taken, and never waits
	 * beyond its bound.
	 *
	 * <p>Wrapping here rather than instrumenting each call site is the point: there are more than
	 * thirty acquisition sites across the services, and a rule enforced at thirty places is a rule
	 * that will be missed at the thirty-first.
	 */
	private final class MonitoredLock implements Lock {
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
		 * without making every caller aware of it; the monitor counts the no-ops so a genuine
		 * wrong-wrapper unlock (which now LEAKS the hold instead of releasing it, unlike the
		 * pre-wrapper implementation) still has a visible symptom.
		 */
		private int holds;

		MonitoredLock(Lock delegate, int stripe, String key, String mode) {
			this.delegate = delegate;
			this.stripe = stripe;
			this.key = key;
			this.mode = mode;
		}

		private LockAcquisitionTimeoutException refuseUpgrade() {
			return new LockAcquisitionTimeoutException("Refusing the " + mode + " lock on " + key
					+ ": this thread already holds that stripe for READING and"
					+ " ReentrantReadWriteLock cannot upgrade, so the acquisition could only end"
					+ " at its timeout — while stalling every later reader of the stripe behind"
					+ " the queued writer. This is a lock-usage bug at the call site (see the"
					+ " lock-order finding just logged), not contention; retrying will fail the"
					+ " same way until the caller stops requesting a write under its own read.",
					List.of(key));
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
			if (LockOrderMonitor.beforeAcquire(stripe, key, mode)) {
				throw refuseUpgrade();
			}
			boolean got;
			long startedAt = System.nanoTime();
			try {
				got = delegate.tryLock(singleLockWarnMs, TimeUnit.MILLISECONDS);
				if (!got) {
					// Not a failure yet — say so, and keep waiting up to the deadlock bound. The
					// distinction matters: this line means "busy", and only the throw below means
					// "this was never going to finish".
					warnRateLimited("Waiting over " + (singleLockWarnMs / 1000) + "s for the "
							+ mode + " lock on " + key + " — heavy contention. Will give up at "
							+ (singleLockDeadlineMs / 1000)
							+ "s so a deadlock cannot hold this request forever.");
					got = delegate.tryLock(singleLockDeadlineMs - singleLockWarnMs,
							TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LockAcquisitionTimeoutException(
						"Interrupted while acquiring " + key, List.of(key));
			}
			if (!got) {
				throw new LockAcquisitionTimeoutException("Could not take the " + mode
						+ " lock on " + key + " within " + (singleLockDeadlineMs / 1000)
						+ "s — another request holds it and is very likely waiting on something"
						+ " this request holds. Failing so both sides can make progress; this"
						+ " request is safe to retry.", List.of(key));
			}
			if (System.nanoTime() - startedAt > singleLockWarnMs * 1_000_000L) {
				warnRateLimited("Took the " + mode + " lock on " + key + " after "
						+ ((System.nanoTime() - startedAt) / 1_000_000L) + "ms of waiting");
			}
			holds++;
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		/**
		 * At most one contention WARN per stripe per {@link #WARN_INTERVAL_MS}: hundreds of
		 * requests queueing behind one long writer all cross the threshold together, and a
		 * hundred identical lines say less than one.
		 */
		private void warnRateLimited(String message) {
			if (stripe >= STRIPES) {
				LOG.warn(message); // named locks are few; no need to rate-limit
				return;
			}
			long now = System.currentTimeMillis();
			long prev = WARN_STAMPS.get(stripe);
			if (now - prev >= WARN_INTERVAL_MS && WARN_STAMPS.compareAndSet(stripe, prev, now)) {
				LOG.warn(message);
			}
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
			// Same bound as lock(): an unbounded acquisition path on the shared lock type would
			// quietly re-admit the permanent-deadlock failure mode for whoever calls it next,
			// which is exactly how the last unbounded path was found — by a reviewer, not a test.
			if (LockOrderMonitor.beforeAcquire(stripe, key, mode)) {
				throw refuseUpgrade();
			}
			if (!delegate.tryLock(singleLockDeadlineMs, TimeUnit.MILLISECONDS)) {
				throw new LockAcquisitionTimeoutException("Could not take the " + mode
						+ " lock on " + key + " within " + (singleLockDeadlineMs / 1000)
						+ "s. Failing so both sides can make progress; safe to retry.",
						List.of(key));
			}
			holds++;
			LockOrderMonitor.acquired(stripe, key, mode);
		}

		@Override
		public boolean tryLock() {
			// No waiting, so no upgrade refusal needed: a certain upgrade simply fails the try,
			// which is already the contract. Still worth reporting, hence beforeAcquire.
			LockOrderMonitor.beforeAcquire(stripe, key, mode);
			if (delegate.tryLock()) {
				holds++;
				LockOrderMonitor.acquired(stripe, key, mode);
				return true;
			}
			return false;
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			if (LockOrderMonitor.beforeAcquire(stripe, key, mode)) {
				// A certain self-deadlock: waiting out `time` cannot change the outcome, and
				// inside bulkLock it would burn the whole retry budget on an acquisition that can
				// never succeed. Thrown rather than returned as false so the caller's failure
				// carries the reason; bulkLock releases its partial holds and rethrows.
				throw refuseUpgrade();
			}
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
				// Never taken (or already released) THROUGH THIS WRAPPER. Normal after a failed
				// acquisition (the caller's finally-block still runs); counted because the other
				// cause — unlocking through a different wrapper than the one that locked — now
				// leaks the hold instead of releasing it, and the counter is that bug's only
				// visible symptom.
				LockOrderMonitor.unlockWithoutHold();
				return;
			}
			holds--;
			delegate.unlock();
			LockOrderMonitor.released(stripe, mode);
		}

		@Override
		public Condition newCondition() {
			return delegate.newCondition();
		}
	}
}
