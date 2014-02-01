/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;

public class PropertyManager {

	private String propertiesFile;
	private Properties config;
	
	
	public PropertyManager(){
		
	}
	
	/**
	 *Constructor setting propertiesFile and config
	 * @param propertiesFile
	 * @throws Exception
	 */
	public PropertyManager(String propertiesFile) throws Exception{
		this.setPropertiesFile(propertiesFile);

		Properties config = new Properties();
		InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(propertiesFile);
		if(inputStream == null){
			throw new Exception();
		}else{
			config.load(inputStream);
			inputStream.close();
			this.setConfig(config);
		}
	}

	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key) throws Exception{
		String val = config.getProperty(key);
		return val;
	}

	/**
	 * Modify a value of the property 
	 * @param key
	 * @param value: new value
	 * @throws Exception
	 */
	public void modifyValue(String key, String value) throws Exception{
		config.setProperty(key, value);
		
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		URL url = classLoader.getResource(propertiesFile);
		config.store(new FileOutputStream(new File(url.toURI())), null);
	}
	
	/**
	 * Add a value to the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void addValue(String key, String value) throws Exception{
		String currentVal = config.getProperty(key);
		String[] currentVals = currentVal.split(","); 
		List<String>valList = new ArrayList<String>();
		Collections.addAll(valList, currentVals);
		
		valList.add(0,value);		
		String newVal = StringUtils.join(valList.toArray(), ",");
		config.setProperty(key, newVal);
		
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		URL url = classLoader.getResource(propertiesFile);
		config.store(new FileOutputStream(new File(url.toURI())), null);
	}
	
	/**
	 * Remove a value from the property which might have multiple values, separated with comma
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public void removeValue(String key, String value) throws Exception{
		String currentVal = config.getProperty(key);
		String[] currentVals = currentVal.split(","); 
		List<String>valList = new ArrayList<String>();
		Collections.addAll(valList, currentVals);
		
		boolean success = valList.remove(value);
		if(success){
			String newVal = StringUtils.join(valList.toArray(), ",");
			config.setProperty(key, newVal);
			
			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			URL url = classLoader.getResource(propertiesFile);
			config.store(new FileOutputStream(new File(url.toURI())), null);
		}else{
			throw new Exception();
		}
	}
	
	public String readHeadValue(String key) throws Exception{
		String currentVal = config.getProperty(key);
		String[] currentVals = currentVal.split(","); 
		return currentVals[0];
	}
	
	
	public boolean containsKey(String key){
		return config.containsKey(key);
	}
	/*
	 * Getter & Setter
	 */
	public String getPropertiesFile() {
		return propertiesFile;
	}

	public void setPropertiesFile(String propertiesFile) {
		this.propertiesFile = propertiesFile;
	}

	public Properties getConfig() {
		return config;
	}

	public void setConfig(Properties config) {
		this.config = config;
	}
}
