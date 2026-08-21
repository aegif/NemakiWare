package jp.aegif.nemaki.util;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.util.spring.SpringPropertiesUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class PropertyManager{
	private static final Log log = LogFactory
			.getLog(PropertyManager.class);

	private SpringPropertiesUtil propertyConfigurer;
	private ContentDaoService contentDaoService;

	public PropertyManager(){

	}
	public Set<String> getKeys(){
		return propertyConfigurer.getKeys();
	}

	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key){
		// Admin-managed integration keys (set via the admin UI and persisted to
		// nemaki_conf) take precedence over deploy-time -D/env so an operator can
		// configure them at runtime without editing config files. A blank stored
		// value is treated as "not set" and falls through to the bootstrap
		// sources below (deploy default), so clearing reverts to the default.
		if (isAdminManagedDynamicKey(key)) {
			Object dyn = getDynamicValue(key);
			if (dyn != null && !dyn.toString().isBlank()) {
				return dyn.toString();
			}
		}

		// CRITICAL FIX: Check system properties first for Jetty environment override support
		String systemPropertyValue = System.getProperty(key);
		if(systemPropertyValue != null){
			return systemPropertyValue;
		}

		// Check environment variables (Docker support)
		// Convert property key format (lowercase.with.dots) to env var format
		// (UPPERCASE_WITH_UNDERSCORES).
		//
		// Hyphens fold too. Only dots were folded, so a key such as
		// lineage.capture-boundary.stale.minutes derived LINEAGE_CAPTURE-BOUNDARY_STALE_MINUTES —
		// a name no POSIX shell can export and no Compose env_file can carry, which silently
		// removed one of the two per-replica override mechanisms MULTI-REPLICA-DEPLOYMENT.md
		// names (external review). Spring's own relaxed binding folds hyphens the same way, so
		// this makes PropertyManager agree with the @Value path rather than inventing a rule.
		//
		// No existing key is affected: before this change a hyphenated name could never match an
		// exportable variable, so nothing can have been relying on the old derivation.
		String envKey = environmentVariableNameFor(key);
		String envValue = System.getenv(envKey);
		if(envValue != null){
			return envValue;
		}

		Object configVal = getDynamicValue(key);
		if(configVal == null){
			return propertyConfigurer.getValue(key);
		}else{
			return configVal.toString();
		}
	}

	/**
	 * Keys whose runtime value comes primarily from the admin UI (persisted to
	 * nemaki_conf), overriding any deploy-time {@code -D}/env bootstrap default.
	 * Scoped to the authentication / SSO integration settings so an
	 * administrator can configure Google / Microsoft / OIDC (Keycloak) / SAML
	 * from the admin menu and have it take effect (and persist) without a
	 * config-file/redeploy round trip. Other keys (DB credentials, etc.) keep
	 * the historical "system property first" precedence.
	 *
	 * <p>Prefixes:
	 * <ul>
	 *   <li>{@code cloud.auth.} / {@code cloud.drive.} — Google/Microsoft sign-in + Drive</li>
	 *   <li>{@code sso.} — {@code sso.oidc.enabled} / {@code sso.saml.enabled}</li>
	 *   <li>{@code oidc.} — OIDC issuer / clientId (Keycloak etc.)</li>
	 *   <li>{@code saml.} — SAML IdP SSO URL / SP entity id / certificate / SLO</li>
	 * </ul>
	 */
	public static boolean isAdminManagedDynamicKey(String key){
		if (key == null) return false;
		return key.startsWith("cloud.auth.")
				|| key.startsWith("cloud.drive.")
				|| key.startsWith("sso.")
				|| key.startsWith("oidc.")
				|| key.startsWith("saml.");
	}

	public String readHeadValue(String key) throws Exception{
		Object configVal = getDynamicValue(key);
		if(configVal == null){
			return propertyConfigurer.getHeadValue(key);
		}else{
			return configVal.toString();
		}
	}

	public List<String> readValues(String key) {
		Object configVal = getDynamicValue(key);
		if(configVal == null){
			return propertyConfigurer.getValues(key);
		}else{
			return (List<String>)configVal;
		}
	}

	public boolean readBoolean(String key){
		String val = readValue(key);
		return Boolean.valueOf(val);
	}

	public String readValue(String repositoryId, String key){
		if (log.isDebugEnabled()) {
			log.debug("readValue called with repositoryId='" + repositoryId + "', key='" + key + "'");
		}

		// Admin-managed integration keys: nemaki_conf (admin UI) first, so a
		// runtime-configured value overrides any deploy-time -D/env bootstrap.
		// Repo-specific value wins over global; a blank stored value falls
		// through to the bootstrap sources below. See isAdminManagedDynamicKey.
		if (isAdminManagedDynamicKey(key)) {
			try {
				Configuration repoConf = getConfiguration(repositoryId);
				if (repoConf != null && repoConf.getConfiguration().containsKey(key)) {
					Object repoVal = repoConf.getConfiguration().get(key);
					if (repoVal != null && !repoVal.toString().isBlank()) {
						return repoVal.toString();
					}
				}
			} catch (Exception e) {
				log.warn("Repo-specific admin-managed lookup failed for repositoryId='"
						+ repositoryId + "', key='" + key + "': " + e.getMessage());
			}
			Object dyn = getDynamicValue(key);
			if (dyn != null && !dyn.toString().isBlank()) {
				return dyn.toString();
			}
		}

		// Priority 1: System properties (JVM -D flags, Jetty environment overrides)
		String systemPropertyValue = System.getProperty(key);
		if (systemPropertyValue != null) {
			return systemPropertyValue;
		}

		// Priority 2: Environment variables (Docker/container support)
		String envKey = key.toUpperCase().replace('.', '_');
		String envValue = System.getenv(envKey);
		if (envValue != null) {
			return envValue;
		}

		// Priority 3: Repo-specific dynamic value (CouchDB repo Configuration document)
		try {
			Configuration repoConf = getConfiguration(repositoryId);
			if (repoConf != null && repoConf.getConfiguration().containsKey(key)) {
				Object repoVal = repoConf.getConfiguration().get(key);
				if (repoVal != null) {
					String result = repoVal.toString();
					if (log.isDebugEnabled()) {
						log.debug("Using repo-specific dynamic value for repositoryId='" + repositoryId + "', key='" + key + "': '" + result + "'");
					}
					return result;
				}
			}
		} catch (Exception e) {
			log.warn("Repo-specific getDynamicValue failed for repositoryId='" + repositoryId + "', key='" + key + "': " + e.getMessage());
		}

		// Priority 4: Global dynamic value (CouchDB global Configuration document)
		Object configVal = getDynamicValue(key);
		if (configVal != null) {
			return configVal.toString();
		}

		// Priority 5: Properties file (nemakiware.properties)
		return propertyConfigurer.getValue(key);
	}

	public String readHeadValue(String repositoryId, String key) throws Exception{
		Object configVal = getDynamicValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getHeadValue(key);
		}else{
			return configVal.toString();
		}
	}

	public List<String> readValues(String repositoryId, String key) {
		Object configVal = getDynamicValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getValues(key);
		}else{
			return (List<String>)configVal;
		}
	}

	public boolean readBoolean(String repositoryId, String key){
		String val = readValue(repositoryId, key);
		return Boolean.valueOf(val);
	}

	/**
	 * The environment-variable name a configuration key is read from.
	 *
	 * <p>Package-private and separate so a test can drive THIS rather than a copy of it. The
	 * derivation was previously inline, and the test written for it restated the rule instead of
	 * calling it — so reverting the fix left the test green (external review).
	 */
	static String environmentVariableNameFor(String key) {
		return key.toUpperCase().replace('.', '_').replace('-', '_');
	}

	public Configuration getConfiguration(String repositoryId) {
		return contentDaoService.getConfiguration(repositoryId);
	}

	private Object getDynamicValue(String key){
		Object result = null;

		Configuration sysConf = getConfiguration(SystemConst.NEMAKI_CONF_DB);
		if(sysConf != null && sysConf.getConfiguration().containsKey(key)){
			Object sysVal = sysConf.getConfiguration().get(key);
			if(sysVal != null){
				result = sysVal;
			}
		}
		return result;
	}

	private Object getDynamicValue(String repositoryId, String key){
		Object result = null;

		if (log.isDebugEnabled()) {
			log.debug("getDynamicValue called with repositoryId='" + repositoryId + "', key='" + key + "'");
		}

		Configuration repoConf = getConfiguration(repositoryId);
		
		if (log.isDebugEnabled()) {
			log.debug("getConfiguration('" + repositoryId + "') returned: " + (repoConf != null ? "NOT NULL" : "NULL"));
		}
		
		if(repoConf != null){
			if (log.isDebugEnabled()) {
				log.debug("Configuration map: " + (repoConf.getConfiguration() != null ? "NOT NULL, size=" + repoConf.getConfiguration().size() : "NULL"));
				if(repoConf.getConfiguration() != null) {
					log.debug("Configuration keys: " + repoConf.getConfiguration().keySet());
				}
			}
			
			Object repoVal = repoConf.getConfiguration().get(key);
			
			if (log.isDebugEnabled()) {
				log.debug("repoConf.getConfiguration().get('" + key + "') returned: " + (repoVal != null ? "'" + repoVal.toString() + "'" : "NULL"));
			}
			
			if(repoVal != null){
				result = repoVal;
			}
		}

		if(result == null){
			if (log.isDebugEnabled()) {
				log.debug("Repository-specific value is null, trying system-wide configuration");
			}
			result = getDynamicValue(key);
		}

		if (log.isDebugEnabled()) {
			log.debug("getDynamicValue final result: " + (result != null ? "'" + result.toString() + "'" : "NULL"));
		}

		return result;
	}

	/**
	 * Returns the source file name for the given property key.
	 * @param key the property key
	 * @return the file name where the property was last defined, or null if unknown
	 */
	public String getPropertySource(String key) {
		return propertyConfigurer.getPropertySource(key);
	}

	public void setPropertyConfigurer(SpringPropertiesUtil propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}
}
