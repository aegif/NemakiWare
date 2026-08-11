package jp.aegif.nemaki.util.lock;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import jp.aegif.nemaki.model.Content;

public interface ThreadLockService {
	public ReadWriteLock get(String repositoryId, String objectId);
	public Lock getWriteLock(String repositoryId, String objectId);
	public Lock getReadLock(String repositoryId, String objectId);
	public <T extends Content> List<Lock> readLocks(String repositoryId, List<T> contents);

	/**
	 * Locks for several objects, ordered so that concurrent callers cannot deadlock on them.
	 *
	 * <p>The order is by STRIPE, not by object id or by position in the hierarchy. Locks here are
	 * striped, so unrelated objects share one — an order defined over the objects would leave two
	 * threads free to take the same two stripes in opposite orders, which is the shape that stops
	 * a repository. Duplicates (ids that land on one stripe) are collapsed, so the returned list
	 * has one entry per lock and {@link #bulkUnlock} releases each hold exactly once.
	 *
	 * @param write {@code true} for write locks, {@code false} for read locks
	 */
	public List<Lock> orderedLocks(String repositoryId, List<String> objectIds, boolean write);

	/**
	 * A repository-scoped lock that is NOT an object lock.
	 *
	 * <p>Taken as an enum rather than a string id on purpose. The only such lock, the folder
	 * hierarchy lock, is designed to be acquired FIRST and released LAST — a rule that holds only
	 * while nobody else can acquire it in the middle of their own ordering. When it was reachable
	 * through the ordinary object-id path, a client could name it as an object id and get exactly
	 * that: the lock taken in the middle of an ordered set (named locks sort last), inverting the
	 * rule against every legitimate folder move. An enum cannot arrive over the wire.
	 */
	public Lock getNamedWriteLock(String repositoryId, NamedLock name);

	/** The repository-scoped locks that exist. */
	public enum NamedLock {
		/** Serialises folder moves repository-wide so the move cycle guard is sound. */
		FOLDER_HIERARCHY("__folder-hierarchy__");

		private final String key;

		NamedLock(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}
	public void bulkLock(List<Lock> locks);
	public void bulkUnlock(List<Lock> locks);
}
