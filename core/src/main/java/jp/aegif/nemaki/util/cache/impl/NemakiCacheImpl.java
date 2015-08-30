package jp.aegif.nemaki.util.cache.impl;


import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CustomCache;
import jp.aegif.nemaki.util.cache.NemakiCache;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class NemakiCacheImpl implements NemakiCache{
	private boolean cacheEnabled;
	
	private final CacheManager cacheManager;
	private final String OBJECT_DATA_CACHE = "objectDataCache";
	private final String PROPERTIES_CACHE = "propertisCache";
	private final String TYPE_CACHE = "typeCache";
	private final String CONTENT_CACHE = "contentCache";
	private final String VERSION_SERIES_CACHE = "versionSeriesCache";
	private final String ATTACHMENTS_CACHE = "attachmentCache";
	private final String CHANGE_EVENT_CACHE = "changeEventCache";
	private final String LATEST_CHANGE_TOKEN_CACHE = "latestChangeTokenCache";
	private final String USER_CACHE = "userCache";
	private final String USERS_CACHE = "usersCache";
	private final String GROUP_CACHE = "groupCache";
	private final String GROUPS_CACHE = "groupsCache";
	
	private final String repositoryId;
	
	public NemakiCacheImpl(String repositoryId, PropertyManager propertyManager) {
		this.repositoryId = repositoryId;
		
		cacheEnabled = propertyManager.readBoolean(PropertyKey.CACHE_CMIS_ENABLED);
		
		cacheManager = CacheManager.newInstance();
		
		cacheManager.addCache(new Cache(repositoryId + "_" + OBJECT_DATA_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + PROPERTIES_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + TYPE_CACHE, 1, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + CONTENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + VERSION_SERIES_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + ATTACHMENTS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + CHANGE_EVENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + LATEST_CHANGE_TOKEN_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + USER_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + USERS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + GROUP_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(repositoryId + "_" + GROUPS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
	}
	
	@Override
	public CustomCache getObjectDataCache() {
		CustomCache cc = new CustomCache(cacheEnabled);
		cc.setCache(cacheManager.getCache(repositoryId + "_" + OBJECT_DATA_CACHE));
		return cc; 
	}

	@Override
	public Cache getPropertiesCache() {
		return cacheManager.getCache(repositoryId + "_" + PROPERTIES_CACHE);
	}

	@Override
	public Cache getTypeCache() {
		return cacheManager.getCache(repositoryId + "_" + TYPE_CACHE);
	}

	@Override
	public Cache getContentCache() {
		return cacheManager.getCache(repositoryId + "_" + CONTENT_CACHE);
	}

	@Override
	public Cache getVersionSeriesCache() {
		return cacheManager.getCache(repositoryId + "_" + VERSION_SERIES_CACHE);
	}

	@Override
	public Cache getAttachmentCache() {
		return cacheManager.getCache(repositoryId + "_" + ATTACHMENTS_CACHE);
	}

	@Override
	public Cache getChangeEventCache() {
		return cacheManager.getCache(repositoryId + "_" + CHANGE_EVENT_CACHE);
	}

	@Override
	public Cache getLatestChangeTokenCache() {
		return cacheManager.getCache(repositoryId + "_" + LATEST_CHANGE_TOKEN_CACHE);
	}

	@Override
	public Cache getUserCache() {
		return cacheManager.getCache(repositoryId + "_" + USER_CACHE);
	}

	@Override
	public Cache getUsersCache() {
		return cacheManager.getCache(repositoryId + "_" + USERS_CACHE);
	}

	@Override
	public Cache getGroupCache() {
		return cacheManager.getCache(repositoryId + "_" + GROUP_CACHE);
	}

	@Override
	public Cache getGroupsCache() {
		return cacheManager.getCache(repositoryId + "_" + GROUPS_CACHE);
	}

	@Override
	public void removeCmisCache(String objectId) {
		getPropertiesCache().remove(objectId);
		getObjectDataCache().remove(objectId);
	}
}