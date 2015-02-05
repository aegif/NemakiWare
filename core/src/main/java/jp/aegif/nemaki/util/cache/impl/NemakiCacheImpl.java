package jp.aegif.nemaki.util.cache.impl;

import javax.annotation.PostConstruct;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CustomCache;
import jp.aegif.nemaki.util.cache.NemakiCache;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class NemakiCacheImpl implements NemakiCache{
	private PropertyManager propertyManager;
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
	
	public NemakiCacheImpl() {
		cacheManager = CacheManager.newInstance();
		
		cacheManager.addCache(new Cache(OBJECT_DATA_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(PROPERTIES_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(TYPE_CACHE, 1, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(CONTENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(VERSION_SERIES_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(ATTACHMENTS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(CHANGE_EVENT_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(LATEST_CHANGE_TOKEN_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(USER_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(USERS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(GROUP_CACHE, 10000, false, false, 60 * 60, 60 * 60));
		cacheManager.addCache(new Cache(GROUPS_CACHE, 10000, false, false, 60 * 60, 60 * 60));
	}
	
	@PostConstruct
	public void init(){
		cacheEnabled = propertyManager.readBoolean(PropertyKey.CACHE_CMIS_ENABLED);
	}
	
	@Override
	public CustomCache getObjectDataCache() {
		CustomCache cc = new CustomCache(cacheEnabled);
		cc.setCache(cacheManager.getCache(OBJECT_DATA_CACHE));
		return cc; 
	}

	@Override
	public Cache getPropertiesCache() {
		return cacheManager.getCache(PROPERTIES_CACHE);
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

	@Override
	public void removeCmisCache(String objectId) {
		getPropertiesCache().remove(objectId);
		getObjectDataCache().remove(objectId);
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}	
	
}