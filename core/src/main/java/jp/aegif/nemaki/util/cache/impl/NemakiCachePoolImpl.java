package jp.aegif.nemaki.util.cache.impl;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * NemakiCachePool implementation using Hazelcast-backed CacheService.
 * Migrated from ehcache 2.x to Hazelcast 5.x for clustering support.
 */
public class NemakiCachePoolImpl implements NemakiCachePool {
	private static final Logger log = LoggerFactory.getLogger(NemakiCachePoolImpl.class);

	private Map<String, CacheService> pool = new HashMap<>();
	private CacheService nullCache;
	
	private RepositoryInfoMap repositoryInfoMap;
	private SpringPropertyManager propertyManager;
	
	public NemakiCachePoolImpl() {
	}
	
	public void init() {
		log.info("Initializing NemakiCachePool with Hazelcast");
		for (String key : repositoryInfoMap.keys()) {
			add(key);
		}
		
		nullCache = new CacheService(null, propertyManager);
		log.info("NemakiCachePool initialized with {} repositories", pool.size());
	}
	
	@Override
	public CacheService get(String repositoryId) {
		CacheService cache = pool.get(repositoryId);
		
		if (cache == null) {
			return nullCache;
		} else {
			return cache;
		}
	}

	@Override
	public void add(String repositoryId) {
		log.info("Adding cache for repository: {}", repositoryId);
		pool.put(repositoryId, new CacheService(repositoryId, propertyManager));
	}

	@Override
	public void remove(String repositoryId) {
		CacheService cache = pool.remove(repositoryId);
		if (cache != null) {
			cache.shutdown();
		}
	}

	@Override
	public void removeAll() {
		for (CacheService cache : pool.values()) {
			cache.shutdown();
		}
		pool.clear();
	}

	@Override
	public void clear(String repositoryId) {
		CacheService oldCache = pool.get(repositoryId);
		if (oldCache != null) {
			oldCache.shutdown();
		}
		pool.put(repositoryId, new CacheService(repositoryId, propertyManager));
	}

	@Override
	public void clearAll() {
		for (String key : pool.keySet()) {
			CacheService oldCache = pool.get(key);
			if (oldCache != null) {
				oldCache.shutdown();
			}
			pool.put(key, new CacheService(key, propertyManager));
		}
	}
	
	/**
	 * Shutdown all Hazelcast instances.
	 * Should be called when the application is shutting down.
	 */
	public void shutdown() {
		log.info("Shutting down NemakiCachePool");
		for (CacheService cache : pool.values()) {
			cache.shutdown();
		}
		if (nullCache != null) {
			nullCache.shutdown();
		}
		pool.clear();
		log.info("NemakiCachePool shutdown complete");
	}

	public void setPropertyManager(SpringPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
