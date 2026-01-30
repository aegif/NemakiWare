package jp.aegif.nemaki.util.cache.impl;

import java.util.HashMap;
import java.util.Map;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

public class NemakiCachePoolImpl implements NemakiCachePool{

	private Map<String, CacheService> pool = new HashMap<String, CacheService>();
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

	@Override
	public void clear(String repositoryId) {
		CacheService old = pool.get(repositoryId);
		if (old != null) {
			old.close();
		}
		pool.put(repositoryId, new CacheService(repositoryId, propertyManager));
	}

	@Override
	public void clearAll() {
		for(String key : pool.keySet()){
			CacheService old = pool.get(key);
			if (old != null) {
				old.close();
			}
			pool.put(key, new CacheService(key, propertyManager));
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
