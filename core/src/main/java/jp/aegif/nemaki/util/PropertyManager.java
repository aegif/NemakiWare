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
		// CRITICAL FIX: Check system properties first for Jetty environment override support
		String systemPropertyValue = System.getProperty(key);
		if(systemPropertyValue != null){
			return systemPropertyValue;
		}
		
		// Check environment variables (Docker support)
		// Convert property key format (lowercase.with.dots) to env var format (UPPERCASE_WITH_UNDERSCORES)
		String envKey = key.toUpperCase().replace('.', '_');
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
