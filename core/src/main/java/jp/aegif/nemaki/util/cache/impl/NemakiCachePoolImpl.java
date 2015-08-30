package jp.aegif.nemaki.util.cache.impl;

import java.util.HashMap;
import java.util.Map;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCache;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

public class NemakiCachePoolImpl implements NemakiCachePool{

	private Map<String, NemakiCache> pool = new HashMap<String, NemakiCache>();
	private NemakiCache nullCache;
	
	private RepositoryInfoMap repositoryInfoMap;
	private PropertyManager propertyManager;
	
	public NemakiCachePoolImpl() {
		
	}
	
	public void init(){
		for(String key : repositoryInfoMap.keys()){
			add(key);
		}
		
		nullCache = new NemakiCacheImpl(null, propertyManager);
	}
	
	@Override
	public NemakiCache get(String repositoryId) {
		NemakiCache cache = pool.get(repositoryId);
		
		if (cache == null){
			return nullCache;
		}else{
			return cache;
		}
	}

	@Override
	public void add(String repositoryId) {
		pool.put(repositoryId, new NemakiCacheImpl(repositoryId, propertyManager));
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
		pool.put(repositoryId, new NemakiCacheImpl(repositoryId, propertyManager));
	}

	@Override
	public void clearAll() {
		for(String key : pool.keySet()){
			pool.put(key, new NemakiCacheImpl(key, propertyManager));
		}
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
