package jp.aegif.nemaki.util;

import java.util.List;

public interface PropertyManager {
	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key);

	/**
	 * 
	 * @param key
	 * @return
	 */
	public List<String>readValues(String key);
	
	/**
	 * Modify a value of the property 
	 * @param key
	 * @param value: new value
	 * @throws Exception
	 */
	public void modifyValue(String key, String value);
	
	/**
	 * Add a value to the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void addValue(String key, String value);
	
	/**
	 * Remove a value from the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void removeValue(String key, String value);
	
	
	/**
	 * 
	 * @param key
	 * @return
	 */
	public String readHeadValue(String key);
}
