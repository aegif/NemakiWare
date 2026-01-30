package jp.aegif.nemaki.util.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.YamlManager;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.model.Tree;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * CacheService using Hazelcast for distributed caching.
 * Migrated from ehcache 2.x to Hazelcast 5.x for clustering support.
 */
public class CacheService {
	private static final Logger log = LoggerFactory.getLogger(CacheService.class);
	
	private final HazelcastInstance hazelcastInstance;
	private final Map<String, Boolean> enabled = new HashMap<>();
	private final Map<String, NemakiCacheConfig> cacheConfigs = new HashMap<>();
	
	private final String CONFIG_CACHE = "configCache";
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
	
	private final String ACL_CACHE = "aclCache";
	private final String JOINED_GROUP_CACHE = "joinedGroupCache";
	private final String PROPERTY_DEFINITION_CACHE = "propertyDefinitionCache";

	private final String repositoryId;

	public CacheService(String repositoryId, SpringPropertyManager propertyManager) {
		this.repositoryId = repositoryId;
		
		Config config = loadConfig(propertyManager);
		this.hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance(config);
		
		log.info("CacheService initialized with Hazelcast for repository: {}", repositoryId);
	}

	private Config loadConfig(SpringPropertyManager propertyManager) {
		String configFile = propertyManager.readValue(PropertyKey.CACHE_CONFIG);
		YamlManager manager = new YamlManager(configFile);
		Map<String, Map<String, Object>> yml = (Map<String, Map<String, Object>>) manager.loadYml();

		Map<String, Object> defaultConfigMap = yml.get("default");
		yml.remove("default");

		Config hazelcastConfig = new Config();
		hazelcastConfig.setInstanceName("nemakiware-cache");
		
		hazelcastConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
		hazelcastConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);

		for (Entry<String, Map<String, Object>> configMap : yml.entrySet()) {
			NemakiCacheConfig cacheConfig = new NemakiCacheConfig();
			cacheConfig.override(defaultConfigMap);
			if (configMap.getValue() != null) {
				cacheConfig.override(configMap.getValue());
			}

			String mapName = repositoryId + "_" + configMap.getKey();
			
			MapConfig mapConfig = new MapConfig(mapName);
			mapConfig.getEvictionConfig()
				.setMaxSizePolicy(MaxSizePolicy.PER_NODE)
				.setSize(cacheConfig.maxElementsInMemory.intValue());
			
			if (!cacheConfig.eternal) {
				mapConfig.setTimeToLiveSeconds(cacheConfig.timeToLiveSeconds.intValue());
				mapConfig.setMaxIdleSeconds(cacheConfig.timeToIdleSeconds.intValue());
			}
			
			hazelcastConfig.addMapConfig(mapConfig);
			enabled.put(mapName, cacheConfig.cacheEnabled);
			cacheConfigs.put(mapName, cacheConfig);
		}
		
		return hazelcastConfig;
	}

	private class NemakiCacheConfig {
		private Boolean cacheEnabled = true;
		private Long maxElementsInMemory = 10000L;
		private Boolean overflowToDisc = false;
		private Boolean eternal = false;
		private Long timeToLiveSeconds = 3600L;
		private Long timeToIdleSeconds = 3600L;

		private void override(Map<String, Object> map) {
			if (map == null) return;
			if (map.get("cacheEnabled") != null)
				cacheEnabled = (Boolean) map.get("cacheEnabled");
			if (map.get("maxElementsInMemory") != null)
				maxElementsInMemory = ((Number) map.get("maxElementsInMemory")).longValue();
			if (map.get("overflowToDisc") != null)
				overflowToDisc = (Boolean) map.get("overflowToDisc");
			if (map.get("eternal") != null)
				eternal = (Boolean) map.get("eternal");
			if (map.get("timeToLiveSeconds") != null)
				timeToLiveSeconds = ((Number) map.get("timeToLiveSeconds")).longValue();
			if (map.get("timeToIdleSeconds") != null)
				timeToIdleSeconds = ((Number) map.get("timeToIdleSeconds")).longValue();
		}
	}
	
	private <T> IMap<String, T> getMap(String cacheName) {
		String mapName = repositoryId + "_" + cacheName;
		return hazelcastInstance.getMap(mapName);
	}
	
	private boolean isEnabled(String cacheName) {
		String mapName = repositoryId + "_" + cacheName;
		Boolean isEnabled = enabled.get(mapName);
		return isEnabled != null && isEnabled;
	}

	public NemakiCache<Configuration> getConfigCache() {
		return new NemakiCache<>(isEnabled(CONFIG_CACHE), getMap(CONFIG_CACHE));
	}

	public NemakiCache<ObjectData> getObjectDataCache() {
		return new NemakiCache<>(isEnabled(OBJECT_DATA_CACHE), getMap(OBJECT_DATA_CACHE));
	}

	public NemakiCache<?> getPropertiesCache() {
		return new NemakiCache<>(isEnabled(PROPERTIES_CACHE), getMap(PROPERTIES_CACHE));
	}

	public NemakiCache<List<NemakiTypeDefinition>> getTypeCache() {
		return new NemakiCache<>(isEnabled(TYPE_CACHE), getMap(TYPE_CACHE));
	}

	public NemakiCache<Content> getContentCache() {
		return new NemakiCache<>(isEnabled(CONTENT_CACHE), getMap(CONTENT_CACHE));
	}

	public NemakiCache<Tree> getTreeCache() {
		return new NemakiCache<>(isEnabled(TREE_CACHE), getMap(TREE_CACHE));
	}

	public NemakiCache<VersionSeries> getVersionSeriesCache() {
		return new NemakiCache<>(isEnabled(VERSION_SERIES_CACHE), getMap(VERSION_SERIES_CACHE));
	}

	public NemakiCache<AttachmentNode> getAttachmentCache() {
		return new NemakiCache<>(isEnabled(ATTACHMENTS_CACHE), getMap(ATTACHMENTS_CACHE));
	}

	public NemakiCache<ObjectData> getChangeEventCache() {
		return new NemakiCache<>(isEnabled(CHANGE_EVENT_CACHE), getMap(CHANGE_EVENT_CACHE));
	}

	public NemakiCache<Change> getLatestChangeTokenCache() {
		return new NemakiCache<>(isEnabled(LATEST_CHANGE_TOKEN_CACHE), getMap(LATEST_CHANGE_TOKEN_CACHE));
	}

	public NemakiCache<UserItem> getUserItemCache() {
		return new NemakiCache<>(isEnabled(USER_CACHE), getMap(USER_CACHE));
	}

	public NemakiCache<List<UserItem>> getUserItemsCache() {
		return new NemakiCache<>(isEnabled(USERS_CACHE), getMap(USERS_CACHE));
	}

	public NemakiCache<GroupItem> getGroupItemCache() {
		return new NemakiCache<>(isEnabled(GROUP_CACHE), getMap(GROUP_CACHE));
	}

	public NemakiCache<List<GroupItem>> getGroupsCache() {
		return new NemakiCache<>(isEnabled(GROUPS_CACHE), getMap(GROUPS_CACHE));
	}

	/**
	 * Acl cache related tree cache.
	 * @see jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl.calculateAcl(String, Content)
	 * @see jp.aegif.nemaki.cmis.service.impl.AclServiceImpl.applyAcl(CallContext, String, String, Acl, AclPropagation)
	 */
	public NemakiCache<Acl> getAclCache() {
		return new NemakiCache<>(isEnabled(ACL_CACHE), getMap(ACL_CACHE));
	}
	
	public NemakiCache<List<String>> getJoinedGroupCache() {
		return new NemakiCache<>(isEnabled(JOINED_GROUP_CACHE), getMap(JOINED_GROUP_CACHE));
	}
	
	/**
	 * Property Definition Cache
	 */
	@SuppressWarnings("rawtypes")
	public NemakiCache getPropertyDefinitionCache() {
		return new NemakiCache<>(isEnabled(PROPERTY_DEFINITION_CACHE), getMap(PROPERTY_DEFINITION_CACHE));
	}

	public void removeCmisCache(String objectId) {
		getObjectDataCache().remove(objectId);
	}

	public void removeCmisAndContentCache(String objectId) {
		getContentCache().remove(objectId);
		getAclCache().remove(objectId);
		removeCmisCache(objectId);
	}
	
	public void removeCmisAndTreeCache(String objectId) {
		getTreeCache().remove(objectId);
		getAclCache().remove(objectId);
		removeCmisCache(objectId);
	}
	
	/**
	 * Get the Hazelcast instance for advanced operations or shutdown.
	 */
	public HazelcastInstance getHazelcastInstance() {
		return hazelcastInstance;
	}
	
	/**
	 * Shutdown the Hazelcast instance.
	 */
	public void shutdown() {
		if (hazelcastInstance != null && hazelcastInstance.getLifecycleService().isRunning()) {
			log.info("Shutting down Hazelcast instance for repository: {}", repositoryId);
			hazelcastInstance.shutdown();
		}
	}
}
