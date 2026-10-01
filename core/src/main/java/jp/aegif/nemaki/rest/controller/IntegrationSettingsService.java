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
	 * A stored setting this node could not read is never the same answer as "no such setting".
	 *
	 * <p>{@code readSetting} returns {@code null} for both, because {@code
	 * ContentDaoServiceImpl} answers a failed {@code nemaki_conf} read with an EMPTY
	 * {@code Configuration} carrying {@code loadFailed=true}, and {@code PropertyManager}
	 * ignores that flag. Two callers then state a fact the read never established: the ingest
	 * idempotency record ("this request has not been completed before" — after which a
	 * {@code dedupePolicy=replace} request DELETES the document a previous run committed) and
	 * the connector checkpoints ("this profile has never polled" — after which the poll takes
	 * only the first page, treats every item as new, and advances the checkpoint past the
	 * older items it never listed, filtering them out for good). Three reviews reported the
	 * pair; the first two rounds recorded it as a residual for a later batch.
	 *
	 * <p>The flag is consulted ONLY when the value could not be resolved at all. A key
	 * satisfied by a system property, an environment variable or the properties file is
	 * answered from there and never reaches the store, so an outage must not refuse it.
	 *
	 * <p>SCOPE. A previous version of this note said {@code readValue} consults
	 * {@code nemaki_conf} only for the five admin-managed prefixes. THAT IS FALSE, and a review
	 * caught it: the admin-managed check is only the FIRST of two dynamic reads, and the second
	 * runs for every key that no system property or environment variable answered. So
	 * {@code loadFailed} is about this key's own read after all — which is what the second arm
	 * below assumes. The note was written from reading the top of the method and not the rest.
	 *
	 * @throws SettingUnreadableException when the key resolved nowhere AND the configuration
	 *         database did not answer. Callers that would otherwise assert an absence must let
	 *         it out.
	 */
	public String readSettingOrRefuse(String key) {
		// Through readSetting, not straight to the PropertyManager: test doubles and any
		// future subclass override the one read path, and going around it made seven test
		// classes NPE on a manager they never needed.
		String value;
		try {
			value = readSetting(key);
		} catch (RuntimeException couldNotRead) {
			// A read that THREW is a failed read. Only the loadFailed sentinel was converted,
			// so an exception fell through to the caller's generic catch — and the idempotency
			// caller's catch leaves idempSkip=false, after which a dedupePolicy=replace request
			// DELETES the document a previous run committed. That is the batch's own headline
			// defect, reached through the arm added to close it. Codex found it.
			throw new SettingUnreadableException("the stored value of '" + key
					+ "' could not be read: " + couldNotRead.getMessage() + "; retry shortly");
		}
		if (value != null) return value;
		if (propertyManager == null) return null;
		jp.aegif.nemaki.model.Configuration conf;
		try {
			conf = propertyManager.getConfiguration(SystemConst.NEMAKI_CONF_DB);
		} catch (RuntimeException couldNotAsk) {
			throw new SettingUnreadableException("whether '" + key + "' is stored could not be"
					+ " established: " + couldNotAsk.getMessage() + "; retry shortly");
		}
		if (conf != null && conf.isLoadFailed()) {
			throw new SettingUnreadableException("the stored value of '" + key
					+ "' could not be read: the configuration database did not answer");
		}
		return value;
	}

	/** A setting this node could not read — never the same answer as "there is none". */
	public static class SettingUnreadableException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public SettingUnreadableException(String message) {
			super(message);
		}
	}

	/**
	 * Determines the source of the current effective value for a given key.
	 *
	 * @return one of "system_property", "environment", "couchdb", "properties_file", or "none"
	 */
	public String readSettingSource(String key) {
		// Admin-managed integration keys resolve their effective value from
		// nemaki_conf first (see PropertyManager.isAdminManagedDynamicKey), so
		// report "couchdb" when a non-blank value is stored there — otherwise a
		// deploy-time -D/env default would be mis-reported as the source and the
		// admin UI would look like it had no effect.
		if (jp.aegif.nemaki.util.PropertyManager.isAdminManagedDynamicKey(key)) {
			try {
				jp.aegif.nemaki.model.Configuration conf =
						propertyManager.getConfiguration(SystemConst.NEMAKI_CONF_DB);
				if (conf != null && conf.getConfiguration().containsKey(key)) {
					Object val = conf.getConfiguration().get(key);
					if (val != null && !val.toString().isBlank()) {
						return "couchdb";
					}
				}
			} catch (Exception e) {
				log.debug("Failed to check CouchDB source for admin-managed key=" + key + ": " + e.getMessage());
			}
		}

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
	 * Reads settings that belong to ONE repository (or one ledger domain) straight from
	 * nemaki_conf — not through {@code PropertyManager}'s configuration cache.
	 *
	 * <p>Two reasons, both about reading the right value. The configuration cache never expires,
	 * and a value saved on another replica invalidates only that replica's copy; a reader that
	 * must act on a save wherever it landed cannot use it. And the cache is keyed by repository:
	 * an id the cache pool does not know (a ledger domain such as {@code record-content}) shares
	 * ONE entry with every other unknown id — nemaki_conf's global configuration among them — so
	 * reading such an id through it can return, or overwrite, the global settings.
	 *
	 * @return key → value for the keys that have a stored document for this id; a key with none
	 *         is absent
	 * @throws RuntimeException when nemaki_conf could not be asked — never an empty map for that
	 */
	public Map<String, String> readRepositorySettings(String repositoryId, java.util.Collection<String> keys) {
		if (repositoryId == null || repositoryId.isBlank()) {
			throw new IllegalArgumentException("repositoryId is required for repository settings");
		}
		CloudantClientWrapper confClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
		if (confClient == null) {
			throw new IllegalStateException("nemaki_conf database client not available");
		}
		Map<String, Object> selector = new HashMap<>();
		selector.put("type", "configuration");
		selector.put("repositoryId", repositoryId);
		selector.put("key", Map.of("$in", new java.util.ArrayList<>(keys)));
		// At most one document per key; the limit only has to exceed that, with room for a
		// duplicate a concurrent first save could leave behind.
		int limit = Math.max(10, keys.size() * 4);
		FindResult findResult = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB).getClient()
				.postFind(new PostFindOptions.Builder().db(confClient.getDatabaseName())
						.selector(selector).limit(limit).build()).execute().getResult();
		List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();
		if (docs == null) {
			throw new IllegalStateException("nemaki_conf answered without documents for " + repositoryId);
		}
		if (docs.size() >= limit) {
			throw new IllegalStateException("nemaki_conf returned a full page of settings for "
					+ repositoryId + "; the result may be partial");
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
			Map<String, Object> props = doc.getProperties();
			Object key = props == null ? null : props.get("key");
			Object value = props == null ? null : props.get("value");
			if (key != null && value != null) {
				values.put(key.toString(), value.toString());
			}
		}
		return values;
	}

	/**
	 * Writes settings that belong to ONE repository: configuration documents in nemaki_conf that
	 * carry {@code repositoryId}, which {@code PropertyManager.readValue(repositoryId, key)} reads
	 * before the global value. A blank value deletes the repository's document, so the key falls
	 * back to the next source instead of holding an empty string (the global writer's rule for
	 * {@link #deleteSettings}).
	 *
	 * <p>Every configuration cache on THIS node is invalidated afterwards. Other replicas keep
	 * theirs until they re-read — a caller whose readers run elsewhere must refresh on its own
	 * (the anchor scheduler evicts before every tick).
	 */
	public void writeRepositorySettings(String repositoryId, Map<String, String> settings) {
		if (repositoryId == null || repositoryId.isBlank()) {
			throw new IllegalArgumentException("repositoryId is required for repository settings");
		}
		CloudantClientWrapper confClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
		if (confClient == null) {
			throw new RuntimeException("nemaki_conf database client not available");
		}
		String dbName = confClient.getDatabaseName();
		com.ibm.cloud.cloudant.v1.Cloudant cloudant = confClient.getClient();

		for (Map.Entry<String, String> entry : settings.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();

			Map<String, Object> selector = new HashMap<>();
			selector.put("type", "configuration");
			selector.put("key", key);
			selector.put("repositoryId", repositoryId);
			FindResult findResult = cloudant.postFind(new PostFindOptions.Builder()
					.db(dbName).selector(selector).limit(1).build()).execute().getResult();
			List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();
			com.ibm.cloud.cloudant.v1.model.Document existing =
					docs == null || docs.isEmpty() ? null : docs.get(0);

			if (value == null || value.isBlank()) {
				if (existing != null) {
					cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
							.db(dbName).docId(existing.getId()).rev(existing.getRev()).build()).execute();
					log.info("Repository setting cleared (reverted to the next source): " + key
							+ " for " + repositoryId);
				}
				continue;
			}
			Document doc = new Document();
			doc.put("type", "configuration");
			doc.put("key", key);
			doc.put("value", value);
			doc.put("repositoryId", repositoryId);
			if (existing != null) {
				doc.setId(existing.getId());
				doc.setRev(existing.getRev());
			}
			DocumentResult result = cloudant.postDocument(new PostDocumentOptions.Builder()
					.db(dbName).document(doc).build()).execute().getResult();
			if (!result.isOk()) {
				throw new RuntimeException("Failed to save setting " + key + " for " + repositoryId
						+ ": " + result.getError());
			}
			log.info("Repository setting updated: " + key + " for " + repositoryId);
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
