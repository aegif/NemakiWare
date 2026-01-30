package jp.aegif.nemaki.util.cache.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.map.IMap;
import com.hazelcast.map.LocalMapStats;

/**
 * NemakiCache wrapper for Hazelcast IMap.
 * Migrated from ehcache 2.x to Hazelcast 5.x for clustering support.
 */
public class NemakiCache<T> {
	private IMap<String, T> cache;
	private final boolean cacheEnabled;
	private static final Logger log = LoggerFactory.getLogger(NemakiCache.class);

	public NemakiCache(boolean cacheEnabled, IMap<String, T> cache) {
		this.cacheEnabled = cacheEnabled;
		this.cache = cache;
	}

	public String getStatisticString() {
		if (cache == null) {
			return "CacheInfo name:null items: 0, size: 0 byte";
		}
		LocalMapStats stats = cache.getLocalMapStats();
		String name = cache.getName();
		long size = stats.getOwnedEntryCount();
		long bytes = stats.getOwnedEntryMemoryCost();
		return String.format("CacheInfo name:%s items: %d, size: %d byte", name, size, bytes);
	}

	@SuppressWarnings("unchecked")
	public T get(String key) {
		if (cacheEnabled && cache != null) {
			return cache.get(key);
		} else {
			return null;
		}
	}

	public void put(String key, T data) {
		if (cacheEnabled && cache != null) {
			cache.put(key, data);
		}
	}

	public void remove(String key) {
		if (cacheEnabled && cache != null) {
			cache.remove(key);
		}
	}

	public void removeAll() {
		if (cacheEnabled && cache != null) {
			cache.clear();
		}
	}

	public IMap<String, T> getCache() {
		return this.cache;
	}

	public void setCache(IMap<String, T> cache) {
		this.cache = cache;
	}

	public boolean isCacheEnabled() {
		return cacheEnabled;
	}
}
