package jp.aegif.nemaki.util;

import java.util.List;

import jp.aegif.nemaki.spring.SpringPropertiesUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class NemakiPropertyManager{
	private static final Log log = LogFactory
			.getLog(NemakiPropertyManager.class);

	private SpringPropertiesUtil nemakiProperties;

	public NemakiPropertyManager(){

	}

	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key){
		return nemakiProperties.getValue(key);
	}

	public String readHeadValue(String key) throws Exception{
		return nemakiProperties.getHeadValue(key);
	}

	public List<String> readValues(String key) {
		return nemakiProperties.getValues(key);
	}
	
	public boolean readBoolean(String key){
		String val = readValue(key);
		return Boolean.valueOf(val);
	}
	
	public void setNemakiProperties(SpringPropertiesUtil nemakiProperties) {
		this.nemakiProperties = nemakiProperties;
	}
}
