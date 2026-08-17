package jp.aegif.nemaki.util.cache.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

public class NemakiCachePoolImpl implements NemakiCachePool{

	// Concurrent, not HashMap: the pool is read on every request and is now also written by the
	// admin clear endpoint. An unsynchronised HashMap mutated while others read it can corrupt its
	// table, which is a far worse failure than the stale cache the clear is meant to fix.
	private Map<String, CacheService> pool = new ConcurrentHashMap<String, CacheService>();
	private CacheService nullCache;
	
	private RepositoryInfoMap repositoryInfoMap;
	private SpringPropertyManager propertyManager;
	
	public NemakiCachePoolImpl() {
		
	}
	
	public void init(){
		for(String key : repositoryInfoMap.keys()){
			add(key);
		}
		
		nullCache = new CacheService(null, propertyManager);
	}
	
	@Override
	public CacheService get(String repositoryId) {
		CacheService cache = pool.get(repositoryId);
		
		if (cache == null){
			return nullCache;
		}else{
			return cache;
		}
	}

	@Override
	public void add(String repositoryId) {
		pool.put(repositoryId, new CacheService(repositoryId, propertyManager));
	}

	@Override
	public void remove(String repositoryId) {
		pool.remove(repositoryId);
	}

	@Override
	public void removeAll() {
		pool.clear();
	}

	/**
	 * Replace this repository's caches with fresh, empty ones.
	 *
	 * <p>BUILD the replacement first, PUBLISH it, and only then close the old one — in that order.
	 * Closing first leaves every request that already holds the old {@code CacheService} (or one of
	 * its {@code NemakiCache} wrappers, which are handed out by value) operating on a closed
	 * manager, and if the constructor then throws, the pool is left holding a closed service with
	 * nothing to replace it. Publishing first means concurrent readers either see the old caches or
	 * the new ones; both are usable, and the old one is discarded once its last user is done with
	 * it.
	 *
	 * <p>The close is best-effort for the same reason: an in-flight reader may still be inside it,
	 * and failing the clear because the discarded instance objected would achieve nothing.
	 */
	@Override
	public void clear(String repositoryId) {
		CacheService replacement = new CacheService(repositoryId, propertyManager);
		CacheService old = pool.put(repositoryId, replacement);
		closeQuietly(old);
	}

	private static void closeQuietly(CacheService service) {
		if (service == null) {
			return;
		}
		try {
			service.close();
		} catch (RuntimeException e) {
			// Nothing to recover: the instance is already unreachable from the pool.
			org.slf4j.LoggerFactory.getLogger(NemakiCachePoolImpl.class)
					.debug("Closing a replaced cache service failed: {}", e.getMessage());
		}
	}

	@Override
	public void clearAll() {
		List<String> keys = new ArrayList<>(pool.keySet());
		for(String key : keys){
			clear(key);
		}
	}

	@Override
	public void closeAll() {
		for (CacheService cacheService : pool.values()) {
			cacheService.close();
		}
		if (nullCache != null) {
			nullCache.close();
		}
	}

	public void setPropertyManager(SpringPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
