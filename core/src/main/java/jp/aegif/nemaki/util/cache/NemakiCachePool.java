package jp.aegif.nemaki.util.cache;

public interface NemakiCachePool {
	CacheService get(String repositoryId);
	void add(String repositoryId);
	void remove(String repositoryId);
	void removeAll();
	void clear(String repositoryId);
	void clearAll();
}
