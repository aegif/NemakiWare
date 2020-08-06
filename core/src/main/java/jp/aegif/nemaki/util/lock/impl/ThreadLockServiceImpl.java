package jp.aegif.nemaki.util.lock.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.util.concurrent.Striped;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.lock.UniqueObjectId;

public class ThreadLockServiceImpl implements ThreadLockService{
	
	private final Striped<ReadWriteLock> locks = Striped.lazyWeakReadWriteLock(4096);
	
	@Override
	public ReadWriteLock get(String repositoryId, String objectId) {
		ReadWriteLock lock = locks.get(new UniqueObjectId(repositoryId, objectId));
		return lock;
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
	public <T extends Content> List<Lock> readLocks(String repositoryId, List<T> contents){
		List<Lock> locks = new ArrayList<>();
		if(CollectionUtils.isNotEmpty(contents)){
			for(T content : contents){
				Lock lock = getReadLock(repositoryId, content.getId());
				locks.add(lock);
			}
		}
		
		return locks;
	}

	@Override
	public void bulkLock(List<Lock> locks){
		for(Lock lock : locks){
			lock.lock();
		}
	}
	
	@Override
	public void bulkUnlock(List<Lock> locks){
		for(Lock lock : locks){
			lock.unlock();
		}
	}
}
