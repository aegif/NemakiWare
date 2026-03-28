package jp.aegif.nemaki.rest.controller;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for reading/writing integration settings (OIDC, Google, Microsoft, SAML, Purview)
 * to CouchDB nemaki_conf via PropertyManager for reads and Cloudant SDK for writes.
 *
 * <p>On write, invalidates the configuration cache across all repositories so that
 * PropertyManager picks up the new values immediately without container restart.</p>
 */
public class IntegrationSettingsService {

	private static final Log log = LogFactory.getLog(IntegrationSettingsService.class);

	private PropertyManager propertyManager;
	private CloudantClientPool connectorPool;
	private NemakiCachePool nemakiCachePool;
	private RepositoryInfoMap repositoryInfoMap;

	/**
	 * Reads the current value of a setting key via PropertyManager.
	 */
	public String readSetting(String key) {
		return propertyManager.readValue(key);
	}

	/**
	 * Determines the source of the current effective value for a given key.
	 *
	 * @return one of "system_property", "environment", "couchdb", "properties_file", or "none"
	 */
	public String readSettingSource(String key) {
		// Priority 1: System properties
		if (System.getProperty(key) != null) {
			return "system_property";
		}

		// Priority 2: Environment variables
		String envKey = key.toUpperCase().replace('.', '_');
		if (System.getenv(envKey) != null) {
			return "environment";
		}

		// Priority 3: CouchDB nemaki_conf
		try {
			jp.aegif.nemaki.model.Configuration conf =
					propertyManager.getConfiguration(SystemConst.NEMAKI_CONF_DB);
			if (conf != null && conf.getConfiguration().containsKey(key)) {
				Object val = conf.getConfiguration().get(key);
				if (val != null) {
					return "couchdb";
				}
			}
		} catch (Exception e) {
			log.debug("Failed to check CouchDB source for key=" + key + ": " + e.getMessage());
		}

		// Priority 4: Properties file
		String fileVal = propertyManager.readValue(key);
		if (fileVal != null && !fileVal.isEmpty()) {
			return "properties_file";
		}

		return "none";
	}

	/**
	 * Reads multiple settings as a map.
	 */
	public Map<String, String> readSettings(Set<String> keys) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String key : keys) {
			String val = readSetting(key);
			result.put(key, val != null ? val : "");
		}
		return result;
	}

	/**
	 * Reads sources for multiple settings.
	 */
	public Map<String, String> readSettingSources(Set<String> keys) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String key : keys) {
			result.put(key, readSettingSource(key));
		}
		return result;
	}

	/**
	 * Writes a single setting to CouchDB nemaki_conf.
	 */
	public void writeSetting(String key, String value) {
		Map<String, String> single = new HashMap<>();
		single.put(key, value);
		writeSettings(single);
	}

	/**
	 * Batch-writes multiple settings to CouchDB nemaki_conf.
	 * Each key-value pair is stored as a separate configuration document
	 * (matching the PropertyManager lookup pattern).
	 */
	public void writeSettings(Map<String, String> settings) {
		CloudantClientWrapper confClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
		if (confClient == null) {
			throw new RuntimeException("nemaki_conf database client not available");
		}

		String dbName = confClient.getDatabaseName();
		com.ibm.cloud.cloudant.v1.Cloudant cloudant = confClient.getClient();

		for (Map.Entry<String, String> entry : settings.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();

			// Find existing document with this key
			Map<String, Object> selector = new HashMap<>();
			selector.put("type", "configuration");
			selector.put("key", key);

			PostFindOptions findOptions = new PostFindOptions.Builder()
					.db(dbName)
					.selector(selector)
					.limit(1)
					.build();

			FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
			List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();

			Document doc = new Document();
			doc.put("type", "configuration");
			doc.put("key", key);
			doc.put("value", value);

			if (docs != null && !docs.isEmpty()) {
				com.ibm.cloud.cloudant.v1.model.Document existingDoc = docs.get(0);
				doc.setId(existingDoc.getId());
				doc.setRev(existingDoc.getRev());
			}

			PostDocumentOptions postOptions = new PostDocumentOptions.Builder()
					.db(dbName)
					.document(doc)
					.build();

			DocumentResult result = cloudant.postDocument(postOptions).execute().getResult();
			if (!result.isOk()) {
				throw new RuntimeException("Failed to save setting " + key + ": " + result.getError());
			}

			log.info("Integration setting updated: " + key);
		}

		invalidateAllConfigCaches();
	}

	/**
	 * Deletes configuration documents from CouchDB for the given keys.
	 * After deletion the key reverts to the next source in the PropertyManager
	 * priority chain (properties file → @Value default).
	 */
	public void deleteSettings(Set<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return;
		}
		CloudantClientWrapper confClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
		if (confClient == null) {
			throw new RuntimeException("nemaki_conf database client not available");
		}

		String dbName = confClient.getDatabaseName();
		com.ibm.cloud.cloudant.v1.Cloudant cloudant = confClient.getClient();

		for (String key : keys) {
			Map<String, Object> selector = new HashMap<>();
			selector.put("type", "configuration");
			selector.put("key", key);

			PostFindOptions findOptions = new PostFindOptions.Builder()
					.db(dbName)
					.selector(selector)
					.limit(1)
					.build();

			FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
			List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();

			if (docs != null && !docs.isEmpty()) {
				com.ibm.cloud.cloudant.v1.model.Document existingDoc = docs.get(0);
				com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions deleteOptions =
						new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
								.db(dbName)
								.docId(existingDoc.getId())
								.rev(existingDoc.getRev())
								.build();
				cloudant.deleteDocument(deleteOptions).execute();
				log.info("Integration setting deleted (reverted to default): " + key);
			}
		}

		invalidateAllConfigCaches();
	}

	/**
	 * Invalidates the configuration cache for all known repositories
	 * so that PropertyManager picks up the updated CouchDB values immediately.
	 */
	private void invalidateAllConfigCaches() {
		if (nemakiCachePool == null) {
			return;
		}

		try {
			// Invalidate nemaki_conf cache (global configuration)
			try {
				nemakiCachePool.get(SystemConst.NEMAKI_CONF_DB).getConfigCache().remove("configuration");
			} catch (Exception e) {
				log.warn("Failed to invalidate nemaki_conf config cache: " + e.getMessage());
			}

			// Also invalidate per-repository caches
			if (repositoryInfoMap != null) {
				Set<String> repoIds = repositoryInfoMap.keys();
				for (String repoId : repoIds) {
					try {
						nemakiCachePool.get(repoId).getConfigCache().remove("configuration");
					} catch (Exception e) {
						log.warn("Failed to invalidate config cache for " + repoId + ": " + e.getMessage());
					}
				}
			}
			log.info("Configuration caches invalidated for nemaki_conf and all repositories");
		} catch (Exception e) {
			log.warn("Failed to invalidate config caches: " + e.getMessage());
		}
	}

	// --- DI setters ---

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
