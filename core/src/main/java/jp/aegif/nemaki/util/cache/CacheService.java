package jp.aegif.nemaki.util.cache;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.units.MemoryUnit;
import java.time.Duration;

public class CacheService {
	private static final Logger log = LoggerFactory.getLogger(CacheService.class);

	private final CacheManager cacheManager;
	private final Map<String, Boolean> enabled = new HashMap<>();
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
	private final boolean clusteringEnabled;
	private final long offheapMb;

	public CacheService(String repositoryId, SpringPropertyManager propertyManager) {
		this.repositoryId = repositoryId;

		this.clusteringEnabled = Boolean.parseBoolean(
				propertyManager.readValue(PropertyKey.CACHE_CLUSTERING_ENABLED));

		long configuredOffheap = 0;
		if (clusteringEnabled) {
			String offheapStr = propertyManager.readValue(PropertyKey.CACHE_CLUSTERING_OFFHEAP_MB);
			configuredOffheap = (offheapStr != null && !offheapStr.isEmpty())
					? Long.parseLong(offheapStr) : 100;
		}
		this.offheapMb = configuredOffheap;

		CacheManagerBuilder<?> builder = CacheManagerBuilder.newCacheManagerBuilder();

		if (clusteringEnabled) {
			try {
				String terracottaUrl = propertyManager.readValue(
						PropertyKey.CACHE_CLUSTERING_TERRACOTTA_URL);
				if (terracottaUrl == null || terracottaUrl.trim().isEmpty()) {
					throw new IllegalArgumentException(
							"cache.clustering.terracotta.url is required when cache.clustering.enabled=true");
				}
				Class<?> clusteringClass = Class.forName(
						"org.ehcache.clustered.client.config.builders.ClusteringServiceConfigurationBuilder");
				java.lang.reflect.Method clusterMethod = clusteringClass.getMethod("cluster", URI.class);
				Object clusterBuilder = clusterMethod.invoke(null, URI.create(terracottaUrl));
				java.lang.reflect.Method autoCreateMethod = clusterBuilder.getClass().getMethod(
						"autoCreate", java.util.function.Function.class);
				Object serviceConfig = autoCreateMethod.invoke(clusterBuilder,
						(java.util.function.Function<Object, Object>) s -> s);
				java.lang.reflect.Method buildMethod = serviceConfig.getClass().getMethod("build");
				Object builtConfig = buildMethod.invoke(serviceConfig);
				// Use with() via reflection to avoid compile-time dependency
				java.lang.reflect.Method withMethod = null;
				for (java.lang.reflect.Method m : builder.getClass().getMethods()) {
					if ("with".equals(m.getName()) && m.getParameterCount() == 1) {
						withMethod = m;
						break;
					}
				}
				if (withMethod != null) {
					builder = (CacheManagerBuilder<?>) withMethod.invoke(builder, builtConfig);
				}
				log.info("Ehcache clustering enabled: {}", terracottaUrl);
			} catch (ClassNotFoundException e) {
				log.error("Ehcache clustering requested but ehcache-clustered JAR not found. "
						+ "Add ehcache-clustered dependency. Falling back to standalone mode.", e);
			} catch (Exception e) {
				log.error("Failed to configure Ehcache clustering. Falling back to standalone mode.", e);
			}
		}

		cacheManager = builder.build(true);

		loadConfig(propertyManager);
	}

	private void loadConfig(SpringPropertyManager propertyManager) {
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

			String cacheName = repositoryId + "_" + configMap.getKey();
			enabled.put(cacheName, config.cacheEnabled);

			// Skip cache creation when disabled
			if (!Boolean.TRUE.equals(config.cacheEnabled)) {
				continue;
			}

			Cache<String, Object> cache = cacheManager.getCache(cacheName, String.class, Object.class);
			if (cache == null) {
				long heapSize = config.maxElementsInMemory != null && config.maxElementsInMemory > 0
						? config.maxElementsInMemory.longValue()
						: 1000L;

				ResourcePoolsBuilder pools = ResourcePoolsBuilder.heap(heapSize);
				if (clusteringEnabled && offheapMb > 0) {
					pools = pools.offheap(offheapMb, MemoryUnit.MB);
				}

				CacheConfiguration<String, Object> configBuilder = CacheConfigurationBuilder
						.newCacheConfigurationBuilder(String.class, Object.class, pools)
						.withExpiry(buildExpiry(config))
						.build();
				cache = cacheManager.createCache(cacheName, configBuilder);
			}
		}
	}

	private class NemakiCacheConfig {
		private Boolean cacheEnabled = Boolean.TRUE;
		private Long maxElementsInMemory;
		private Boolean eternal;
		private Long timeToLiveSeconds;
		private Long timeToIdleSeconds;

		private void override(Map<String, Object> map) {
			if (map.get("cacheEnabled") != null)
				cacheEnabled = (Boolean) map.get("cacheEnabled");
			if (map.get("maxElementsInMemory") != null)
				maxElementsInMemory = (Long) map.get("maxElementsInMemory");
			if (map.get("eternal") != null)
				eternal = (Boolean) map.get("eternal");
			if (map.get("timeToLiveSeconds") != null)
				timeToLiveSeconds = (Long) map.get("timeToLiveSeconds");
			if (map.get("timeToIdleSeconds") != null)
				timeToIdleSeconds = (Long) map.get("timeToIdleSeconds");
		}
	}

	private org.ehcache.expiry.ExpiryPolicy<Object, Object> buildExpiry(NemakiCacheConfig config) {
		if (config.eternal != null && config.eternal) {
			return ExpiryPolicyBuilder.noExpiration();
		}
		// When both TTL and TTI are specified, prefer TTI (idle-based eviction)
		// as it matches ehcache 2.x behavior where idle entries are evicted first.
		// Note: ehcache 3.x does not support both simultaneously on a single cache.
		if (config.timeToIdleSeconds != null) {
			return ExpiryPolicyBuilder.timeToIdleExpiration(Duration.ofSeconds(config.timeToIdleSeconds));
		}
		if (config.timeToLiveSeconds != null) {
			return ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofSeconds(config.timeToLiveSeconds));
		}
		return ExpiryPolicyBuilder.noExpiration();
	}

	public NemakiCache<Configuration> getConfigCache() {
		String name = repositoryId + "_" + CONFIG_CACHE;
		return new NemakiCache<Configuration>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<ObjectData> getObjectDataCache() {
		String name = repositoryId + "_" + OBJECT_DATA_CACHE;
		return new NemakiCache<ObjectData>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<?> getPropertiesCache() {
		String name = repositoryId + "_" + PROPERTIES_CACHE;
		return new NemakiCache<>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<List<NemakiTypeDefinition>> getTypeCache() {
		String name = repositoryId + "_" + TYPE_CACHE;
		return new NemakiCache<>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<Content> getContentCache() {
		String name = repositoryId + "_" + CONTENT_CACHE;
		return new NemakiCache<>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<Tree> getTreeCache() {
		String name = repositoryId + "_" + TREE_CACHE;
		return new NemakiCache<Tree>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<VersionSeries> getVersionSeriesCache() {
		String name = repositoryId + "_" + VERSION_SERIES_CACHE;
		return new NemakiCache<VersionSeries>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<AttachmentNode> getAttachmentCache() {
		String name = repositoryId + "_" + ATTACHMENTS_CACHE;
		return new NemakiCache<AttachmentNode>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<ObjectData> getChangeEventCache() {
		String name = repositoryId + "_" + CHANGE_EVENT_CACHE;
		return new NemakiCache<ObjectData>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<Change> getLatestChangeTokenCache() {
		String name = repositoryId + "_" + LATEST_CHANGE_TOKEN_CACHE;
		return new NemakiCache<Change>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}


	public NemakiCache<UserItem> getUserItemCache() {
		String name = repositoryId + "_" + USER_CACHE;
		return new NemakiCache<UserItem>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<List<UserItem>> getUserItemsCache() {
		String name = repositoryId + "_" + USERS_CACHE;
		return new NemakiCache<List<UserItem>>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<GroupItem> getGroupItemCache() {
		String name = repositoryId + "_" + GROUP_CACHE;
		return new NemakiCache<GroupItem>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<List<GroupItem>> getGroupsCache() {
		String name = repositoryId + "_" + GROUPS_CACHE;
		return new NemakiCache<List<GroupItem>>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}
	/***
	 * Acl cache related tree cache.
	 * @see jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl.calculateAcl(String, Content)
	 * @see jp.aegif.nemaki.cmis.service.impl.AclServiceImpl.applyAcl(CallContext, String, String, Acl, AclPropagation)
	 */
	public NemakiCache<Acl> getAclCache() {
		String name = repositoryId + "_" + ACL_CACHE;
		return new NemakiCache<Acl>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	public NemakiCache<List<String>> getJoinedGroupCache(){
		String name = repositoryId + "_" + JOINED_GROUP_CACHE;
		return new NemakiCache<List<String>>(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
	}

	/**
	 * Property Definition Cache
	 */
	public NemakiCache getPropertyDefinitionCache() {
		String name = repositoryId + "_" + PROPERTY_DEFINITION_CACHE;
		return new NemakiCache(Boolean.TRUE.equals(enabled.get(name)), cacheManager.getCache(name, String.class, Object.class));
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
	 * Close the CacheManager and release all resources.
	 * Must be called during application shutdown.
	 */
	public void close() {
		if (cacheManager != null) {
			cacheManager.close();
		}
	}
}
