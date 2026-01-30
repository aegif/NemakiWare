package jp.aegif.nemaki.util.cache.model;

import org.ehcache.Cache;

public class NemakiCache<T> {
	private Cache cache;
	private final boolean cacheEnabled;
	public NemakiCache(boolean cacheEnabled, Cache cache){
		this.cacheEnabled = cacheEnabled;
		this.cache = cache;
	}

	public String getStatisticString(){
		String name = cache != null ? cache.toString() : "unknown";
		return String.format("CacheInfo name:%s" ,name);
	}

	public T get(String key){
		if(cacheEnabled){
			return cache != null ? (T) cache.get(key) : null;
		}else{
			return null;
		}
	}

	public void put(String key, T data){
		if(cacheEnabled){
			if (cache != null) {
				cache.put(key, data);
			}
		}
	}

	public void remove(String key){
		if(cacheEnabled){
			if (cache != null) {
				cache.remove(key);
			}
		}
	}

	public void removeAll(){
		if(cacheEnabled){
			if (cache != null) {
				cache.clear();
			}
		}
	}

	public Cache getCache(){
		return this.cache;
	}

	public void setCache(Cache cache){
		this.cache = cache;
	}

	public boolean isCacheEnabled(){
		return cacheEnabled;
	}
}
