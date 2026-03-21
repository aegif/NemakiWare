package jp.aegif.nemaki.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.FindResult;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Reads and writes {@code password.policy.minLength} from/to CouchDB {@code nemaki_conf}
 * (via {@link PropertyManager} for reads, via Cloudant SDK for writes).
 *
 * <p>Default value is {@code 0} (no constraint), so Setup Wizard can set short
 * passwords like {@code admin} without being blocked.</p>
 */
public class PasswordPolicyService {

	private static final Log log = LogFactory.getLog(PasswordPolicyService.class);

	private static final String KEY_MIN_LENGTH = "password.policy.minLength";
	private static final int DEFAULT_MIN_LENGTH = 0;

	private PropertyManager propertyManager;
	private CloudantClientPool connectorPool;
	private NemakiCachePool nemakiCachePool;

	/**
	 * Returns the currently configured minimum password length.
	 * Checks the repository-specific value first, then falls back to global.
	 */
	public int getMinLength(String repositoryId) {
		try {
			String val = propertyManager.readValue(repositoryId, KEY_MIN_LENGTH);
			if (val != null && !val.isEmpty()) {
				return Integer.parseInt(val.trim());
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid password.policy.minLength value, using default " + DEFAULT_MIN_LENGTH);
		}
		return DEFAULT_MIN_LENGTH;
	}

	/**
	 * Returns the global minimum password length (no repository context).
	 */
	public int getMinLength() {
		try {
			String val = propertyManager.readValue(KEY_MIN_LENGTH);
			if (val != null && !val.isEmpty()) {
				return Integer.parseInt(val.trim());
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid password.policy.minLength value, using default " + DEFAULT_MIN_LENGTH);
		}
		return DEFAULT_MIN_LENGTH;
	}

	/**
	 * Updates the minimum password length setting in CouchDB nemaki_conf.
	 * Creates a new configuration document if one doesn't exist, or updates the existing one.
	 *
	 * @param repositoryId the repository context
	 * @param minLength    the new minimum length (0 = no constraint)
	 */
	public void setMinLength(String repositoryId, int minLength) {
		CloudantClientWrapper confClient = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
		if (confClient == null) {
			throw new RuntimeException("nemaki_conf database client not available");
		}

		String dbName = confClient.getDatabaseName();
		com.ibm.cloud.cloudant.v1.Cloudant cloudant = confClient.getClient();

		// Find existing document with this key and repositoryId
		Map<String, Object> selector = new HashMap<>();
		selector.put("type", "configuration");
		selector.put("key", KEY_MIN_LENGTH);
		selector.put("repositoryId", repositoryId);

		PostFindOptions findOptions = new PostFindOptions.Builder()
			.db(dbName)
			.selector(selector)
			.limit(1)
			.build();

		FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
		List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();

		Document doc = new Document();
		doc.put("type", "configuration");
		doc.put("key", KEY_MIN_LENGTH);
		doc.put("value", String.valueOf(minLength));
		doc.put("repositoryId", repositoryId);

		if (docs != null && !docs.isEmpty()) {
			// Update existing document
			com.ibm.cloud.cloudant.v1.model.Document existingDoc = docs.get(0);
			String docId = existingDoc.getId();
			String docRev = existingDoc.getRev();

			doc.setId(docId);
			doc.setRev(docRev);
		}

		PostDocumentOptions postOptions = new PostDocumentOptions.Builder()
			.db(dbName)
			.document(doc)
			.build();

		DocumentResult result = cloudant.postDocument(postOptions).execute().getResult();
		if (!result.isOk()) {
			throw new RuntimeException("Failed to save password policy: " + result.getError());
		}

		log.info("Password policy updated: minLength=" + minLength + " for repository " + repositoryId);

		// Invalidate the configuration cache so the new value is picked up immediately
		invalidateConfigCache(repositoryId);
	}

	private void invalidateConfigCache(String repositoryId) {
		if (nemakiCachePool != null) {
			try {
				nemakiCachePool.get(repositoryId).getConfigCache().remove("configuration");
			} catch (Exception e) {
				log.warn("Failed to invalidate config cache for " + repositoryId + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Validates a password against the policy.
	 *
	 * @param password     the password to validate
	 * @param repositoryId the repository context
	 * @return a {@link PasswordPolicyResult} indicating success or failure
	 */
	public PasswordPolicyResult validate(String password, String repositoryId) {
		// Empty password is always rejected (safety net, independent of policy)
		if (password == null || password.isEmpty()) {
			return PasswordPolicyResult.error("Password must not be empty");
		}

		int minLength = (repositoryId != null) ? getMinLength(repositoryId) : getMinLength();
		if (minLength > 0 && password.length() < minLength) {
			return PasswordPolicyResult.error(
					"Password must be at least " + minLength + " characters");
		}

		return PasswordPolicyResult.ok();
	}

	// --- Result value object ---

	public static class PasswordPolicyResult {
		private final boolean ok;
		private final String errorMessage;

		private PasswordPolicyResult(boolean ok, String errorMessage) {
			this.ok = ok;
			this.errorMessage = errorMessage;
		}

		public static PasswordPolicyResult ok() {
			return new PasswordPolicyResult(true, null);
		}

		public static PasswordPolicyResult error(String message) {
			return new PasswordPolicyResult(false, message);
		}

		public boolean isOk() {
			return ok;
		}

		public String getErrorMessage() {
			return errorMessage;
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
}
