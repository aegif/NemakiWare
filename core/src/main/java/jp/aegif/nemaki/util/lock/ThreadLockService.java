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
	public void bulkLock(List<Lock> locks);
	public void bulkUnlock(List<Lock> locks);
}
