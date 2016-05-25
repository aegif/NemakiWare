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
	public void bulkLock(List<Lock> locks);
	public void bulkUnlock(List<Lock> locks);
}
