package jp.aegif.nemaki.service.cache.impl;


import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import jp.aegif.nemaki.service.cache.NemakiCache;

public class NemakiCacheImpl implements NemakiCache{
	private final CacheManager cacheManager;
	private final String TYPE_CACHE = "typeCache";
	private final String CONTENT_CACHE = "contentCache";
	private final String DOCUMENT_CACHE = "documentCache";
	private final String FOLDER_CACHE = "folderCache";
	private final String VERSION_SERIES_CACHE = "versionSeriesCache";
	private final String ATTACHMENTS_CACHE = "attachmentCache";
	private final String CHANGE_EVENT_CACHE = "changeEventCache";
	private final String LATEST_CHANGE_TOKEN_CACHE = "latestChangeTokenCache";
	private final String USER_CACHE = "userCache";
	private final String USERS_CACHE = "usersCache";
	private final String GROUP_CACHE = "groupCache";
	private final String GROUPS_CACHE = "groupsCache";
	
	public NemakiCacheImpl() {
		cacheManager = CacheManager.newInstance();
		
		cacheManager.addCache(new Cache(TYPE_CACHE, 1, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(CONTENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(DOCUMENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(FOLDER_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(VERSION_SERIES_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(ATTACHMENTS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(CHANGE_EVENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(LATEST_CHANGE_TOKEN_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(USER_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(USERS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(GROUP_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(GROUPS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
	}
	
	@Override
	public Cache getTypeCache() {
		return cacheManager.getCache(TYPE_CACHE);
	}

	@Override
	public Cache getContentCache() {
		return cacheManager.getCache(CONTENT_CACHE);
	}

	@Override
	public Cache getDocumentCache() {
		return cacheManager.getCache(DOCUMENT_CACHE);
	}

	@Override
	public Cache getFolderCache() {
		return cacheManager.getCache(FOLDER_CACHE);
	}

	@Override
	public Cache getVersionSeriesCache() {
		return cacheManager.getCache(VERSION_SERIES_CACHE);
	}

	@Override
	public Cache getAttachmentCache() {
		return cacheManager.getCache(ATTACHMENTS_CACHE);
	}

	@Override
	public Cache getChangeEventCache() {
		return cacheManager.getCache(CHANGE_EVENT_CACHE);
	}

	@Override
	public Cache getLatestChangeTokenCache() {
		return cacheManager.getCache(LATEST_CHANGE_TOKEN_CACHE);
	}

	@Override
	public Cache getUserCache() {
		return cacheManager.getCache(USER_CACHE);
	}

	@Override
	public Cache getUsersCache() {
		return cacheManager.getCache(USERS_CACHE);
	}

	@Override
	public Cache getGroupCache() {
		return cacheManager.getCache(GROUP_CACHE);
	}

	@Override
	public Cache getGroupsCache() {
		return cacheManager.getCache(GROUPS_CACHE);
	}

}
