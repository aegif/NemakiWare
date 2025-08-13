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
		// DEBUG: Add detailed logging for system.folder property - ENHANCED with System.out.println
		System.out.println("=== PROPERTY MANAGER DEBUG: readValue called with repositoryId='" + repositoryId + "', key='" + key + "'");
		
		Object configVal = getDynamicValue(repositoryId, key);
		
		System.out.println("=== PROPERTY MANAGER DEBUG: getDynamicValue returned: " + (configVal != null ? "'" + configVal.toString() + "'" : "NULL"));
		
		if(configVal == null){
			String fallbackValue = propertyConfigurer.getValue(key);
			
			System.out.println("=== PROPERTY MANAGER DEBUG: Using fallback propertyConfigurer.getValue('" + key + "') = " + (fallbackValue != null ? "'" + fallbackValue + "'" : "NULL"));
			
			return fallbackValue;
		}else{
			String result = configVal.toString();
			
			System.out.println("=== PROPERTY MANAGER DEBUG: Returning dynamic value: '" + result + "'");
			
			return result;
		}
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
		if(sysConf.getConfiguration().containsKey(key)){
			Object sysVal = sysConf.getConfiguration().get(key);
			if(sysVal != null){
				result = sysVal;
			}
		}
		return result;
	}

	private Object getDynamicValue(String repositoryId, String key){
		Object result = null;

		// DEBUG: Add detailed logging for system.folder property
		System.out.println("=== PROPERTY MANAGER DEBUG: getDynamicValue called with repositoryId='" + repositoryId + "', key='" + key + "'");

		Configuration repoConf = getConfiguration(repositoryId);
		
		System.out.println("=== PROPERTY MANAGER DEBUG: getConfiguration('" + repositoryId + "') returned: " + (repoConf != null ? "NOT NULL" : "NULL"));
		
		if(repoConf != null){
			System.out.println("=== PROPERTY MANAGER DEBUG: Configuration map: " + (repoConf.getConfiguration() != null ? "NOT NULL, size=" + repoConf.getConfiguration().size() : "NULL"));
			if(repoConf.getConfiguration() != null) {
				System.out.println("=== PROPERTY MANAGER DEBUG: Configuration keys: " + repoConf.getConfiguration().keySet());
			}
			
			Object repoVal = repoConf.getConfiguration().get(key);
			
			System.out.println("=== PROPERTY MANAGER DEBUG: repoConf.getConfiguration().get('" + key + "') returned: " + (repoVal != null ? "'" + repoVal.toString() + "'" : "NULL"));
			
			if(repoVal != null){
				result = repoVal;
			}
		}

		if(result == null){
			System.out.println("=== PROPERTY MANAGER DEBUG: Repository-specific value is null, trying system-wide configuration");
			result = getDynamicValue(key);
		}

		System.out.println("=== PROPERTY MANAGER DEBUG: getDynamicValue final result: " + (result != null ? "'" + result.toString() + "'" : "NULL"));

		return result;
	}

	public void setPropertyConfigurer(SpringPropertiesUtil propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}
}
