package jp.aegif.nemaki.util;

import java.util.List;
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
		Object configVal = getDynamicValue(repositoryId, key);
		if(configVal == null){
			return propertyConfigurer.getValue(key);
		}else{
			return configVal.toString();
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
		}else{
			log.warn("Key=" + key + " is not found in configuration.");
		}
		return result;
	}

	private Object getDynamicValue(String repositoryId, String key){
		Object result = null;

		Configuration repoConf = getConfiguration(repositoryId);
		if(repoConf != null){
			Object repoVal = repoConf.getConfiguration().get(key);
			if(repoVal != null){
				result = repoVal;
			}
		}

		if(result == null){
			result = getDynamicValue(key);
		}

		return result;
	}

	public void setPropertyConfigurer(SpringPropertiesUtil propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}
}
