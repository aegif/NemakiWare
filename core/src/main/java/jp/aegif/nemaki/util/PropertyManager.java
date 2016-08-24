package jp.aegif.nemaki.util;

import java.util.List;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.ConnectorPool;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.couch.CouchConfiguration;
import jp.aegif.nemaki.util.spring.SpringPropertiesUtil;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.ViewQuery;

public class PropertyManager{
	private static final Log log = LogFactory
			.getLog(PropertyManager.class);

	private SpringPropertiesUtil propertyConfigurer;
	private ContentDaoService contentDaoService;

	public PropertyManager(){

	}

	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key){
		return propertyConfigurer.getValue(key);
	}

	public String readHeadValue(String key) throws Exception{
		return propertyConfigurer.getHeadValue(key);
	}

	public List<String> readValues(String key) {
		return propertyConfigurer.getValues(key);
	}
	
	public boolean readBoolean(String key){
		String val = readValue(key);
		return Boolean.valueOf(val);
	}
	
	public String readValue(String repositoryId, String key){
		Object configVal = getConfigurationValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getValue(key);
		}else{
			return configVal.toString();
		}
	}

	public String readHeadValue(String repositoryId, String key) throws Exception{
		Object configVal = getConfigurationValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getHeadValue(key);
		}else{
			return configVal.toString();
		}
	}

	public List<String> readValues(String repositoryId, String key) {
		Object configVal = getConfigurationValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getValues(key);
		}else{
			return (List<String>)configVal;
		}
	}
	
	public boolean readBoolean(String repositoryId, String key){
		Object configVal = getConfigurationValue(repositoryId, key);
		if(configVal == null){
			String val = readValue(key);
			return Boolean.valueOf(val);
		}else{
			String val = readValue(repositoryId, key);
			return Boolean.valueOf(val);
		}
	}

	private Configuration getConfiguration(String repositoryId) {
		return contentDaoService.getConfiguration(repositoryId);
	}
	
	private Object getConfigurationValue(String repositoryId, String key){
		Configuration configuration = getConfiguration(repositoryId);
		if(configuration != null){
			Object value = configuration.getConfiguration().get(key);
			if(value != null){
				return value;
			}
		}
		
		return null;
	}
	
	public void setPropertyConfigurer(SpringPropertiesUtil propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}
}
