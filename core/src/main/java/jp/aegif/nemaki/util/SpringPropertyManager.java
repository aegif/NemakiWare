package jp.aegif.nemaki.util;

import java.util.List;

import jp.aegif.nemaki.util.spring.SpringPropertiesUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SpringPropertyManager{
	private static final Log log = LogFactory
			.getLog(PropertyManager.class);

	private SpringPropertiesUtil propertyConfigurer;

	public SpringPropertyManager(){

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

	public void setPropertyConfigurer(SpringPropertiesUtil propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}
}
