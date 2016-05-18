package jp.aegif.nemaki.util.cache;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;

import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.YamlManager;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.model.Tree;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class CacheService {
	private boolean cacheEnabled;

	private final CacheManager cacheManager;
	private final String OBJECT_DATA_CACHE = "objectDataCache";
	private final String PROPERTIES_CACHE = "propertisCache";
	private final String TYPE_CACHE = "typeCache";
	private final String CONTENT_CACHE = "contentCache";
	private final String TREE_CACHE = "treeCache";
	private final String VERSION_SERIES_CACHE = "versionSeriesCache";
	private final String ATTACHMENTS_CACHE = "attachmentCache";
	private final String CHANGE_EVENT_CACHE = "changeEventCache";
	private final String LATEST_CHANGE_TOKEN_CACHE = "latestChangeTokenCache";
	private final String USER_CACHE = "userCache";
	private final String USERS_CACHE = "usersCache";
	private final String GROUP_CACHE = "groupCache";
	private final String GROUPS_CACHE = "groupsCache";

	private final String repositoryId;

	public CacheService(String repositoryId, PropertyManager propertyManager) {
		this.repositoryId = repositoryId;

		cacheEnabled = propertyManager.readBoolean(PropertyKey.CACHE_CMIS_ENABLED);

		cacheManager = CacheManager.newInstance();
		
		loadConfig(propertyManager);
	}

	private void loadConfig(PropertyManager propertyManager) {
		String configFile = propertyManager.readValue(PropertyKey.CACHE_CONFIG);
		YamlManager manager = new YamlManager(configFile);
		Map<String, Map<String, Object>> yml = (Map<String, Map<String, Object>>) manager.loadYml();

		// default
		Map<String, Object> defaultConfigMap = yml.get("default");
		yml.remove("default");

		for (Entry<String, Map<String, Object>> configMap : yml.entrySet()) {
			NemakiCacheConfig config = new NemakiCacheConfig();
			config.override(defaultConfigMap);
			if(configMap.getValue() != null){
				config.override(configMap.getValue());
			}

			Cache cache = new Cache(repositoryId + "_" + configMap.getKey(), config.maxElementsInMemory.intValue(),
					config.overflowToDisc, config.eternal, config.timeToLiveSeconds, config.timeToIdleSeconds);
			cacheManager.addCache(cache);
		}

	}

	private class NemakiCacheConfig {
		private Long maxElementsInMemory;
		private Boolean overflowToDisc;
		private Boolean eternal;
		private Long timeToLiveSeconds;
		private Long timeToIdleSeconds;

		private void override(Map<String, Object> map) {
			if (map.get("maxElementsInMemory") != null)
				maxElementsInMemory = (Long) map.get("maxElementsInMemory");
			if (map.get("overflowToDisc") != null)
				overflowToDisc = (Boolean) map.get("overflowToDisc");
			if (map.get("eternal") != null)
				eternal = (Boolean) map.get("eternal");
			if (map.get("timeToLiveSeconds") != null)
				timeToLiveSeconds = (Long) map.get("timeToLiveSeconds");
			if (map.get("timeToIdleSeconds") != null)
				timeToIdleSeconds = (Long) map.get("timeToIdleSeconds");
		}
	}

	public CustomCache getObjectDataCache() {
		CustomCache cc = new CustomCache(cacheEnabled);
		cc.setCache(cacheManager.getCache(repositoryId + "_" + OBJECT_DATA_CACHE));
		return cc;
	}

	public Cache getPropertiesCache() {
		return cacheManager.getCache(repositoryId + "_" + PROPERTIES_CACHE);
	}

	public Cache getTypeCache() {
		return cacheManager.getCache(repositoryId + "_" + TYPE_CACHE);
	}

	public Cache getContentCache() {
		return cacheManager.getCache(repositoryId + "_" + CONTENT_CACHE);
	}

	public NemakiCache<Tree> getTreeCache() {
		NemakiCache<Tree> cache = new NemakiCache<Tree>(true, cacheManager.getCache(repositoryId + "_" + TREE_CACHE));
		return cache;
	}

	public Cache getVersionSeriesCache() {
		return cacheManager.getCache(repositoryId + "_" + VERSION_SERIES_CACHE);
	}

	public Cache getAttachmentCache() {
		return cacheManager.getCache(repositoryId + "_" + ATTACHMENTS_CACHE);
	}

	public Cache getChangeEventCache() {
		return cacheManager.getCache(repositoryId + "_" + CHANGE_EVENT_CACHE);
	}

	public Cache getLatestChangeTokenCache() {
		return cacheManager.getCache(repositoryId + "_" + LATEST_CHANGE_TOKEN_CACHE);
	}

	public Cache getUserCache() {
		return cacheManager.getCache(repositoryId + "_" + USER_CACHE);
	}

	public Cache getUsersCache() {
		return cacheManager.getCache(repositoryId + "_" + USERS_CACHE);
	}

	public Cache getGroupCache() {
		return cacheManager.getCache(repositoryId + "_" + GROUP_CACHE);
	}

	public Cache getGroupsCache() {
		return cacheManager.getCache(repositoryId + "_" + GROUPS_CACHE);
	}

	public void removeCmisCache(String objectId) {
		getPropertiesCache().remove(objectId);
		getObjectDataCache().remove(objectId);
	}
}