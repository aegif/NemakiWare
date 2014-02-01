package jp.aegif.nemaki.util;

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
		return nemakiProperties.getProperty(key);
	}

	public String readHeadValue(String key) throws Exception{
		return nemakiProperties.getProperty(key);
	}

	public void setNemakiProperties(SpringPropertiesUtil nemakiProperties) {
		this.nemakiProperties = nemakiProperties;
	}
}
