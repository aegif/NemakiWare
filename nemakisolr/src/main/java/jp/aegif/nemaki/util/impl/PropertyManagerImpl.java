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
package jp.aegif.nemaki.util.impl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.PropertyManager;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.core.SolrResourceLoader;

public class PropertyManagerImpl implements PropertyManager{

	private String propertiesFile;
	private Properties config;
	private List<String> overrideFiles = new ArrayList<String>();

	public PropertyManagerImpl(){

	}

	/**
	 * Constructor setting specified properties file and config object
	 * @param propertiesFile
	 */
	public PropertyManagerImpl(String propertiesFile){
		this.setPropertiesFile(propertiesFile);

		Properties config = new Properties();
		SolrResourceLoader loader = new SolrResourceLoader(null);
		try {
			//Set key values
			InputStream inputStream = loader.openResource(propertiesFile);
			if(inputStream != null){
				config.load(inputStream);
				this.setConfig(config);
			}

			//Set override files
			String _overrideFiles = config.getProperty(PropertyKey.OVERRIDE_FILES);
			if(StringUtils.isNotBlank(_overrideFiles)){
				overrideFiles = split(_overrideFiles);
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public String readValue(String key){
		String val = config.getProperty(key);
		return override(key, val);
	}

	@Override
	public List<String> readValues(String key){
		String val = readValue(key);
		String _val = override(key, val);
		return split(_val);
	}

	@Override
	public String readHeadValue(String key){
		String val = config.getProperty(key);
		String _val = override(key, val);
		String[] vals = _val.split(",");
		return vals[0];
	}

	private List<String> split(String str){
		if(StringUtils.isBlank(str)){
			return new ArrayList<String>();
		}
		final String delimiter = ",";
		String[] splitted = StringUtils.split(str, delimiter);

		List<String> result = new ArrayList<String>();
		for(int i=0; i < splitted.length; i++){
			result.add(splitted[i]);
		}
		return result;
	}

	private String override(String key, String value){
		String result = value;
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		for(String file : overrideFiles){
			//Load file
			InputStream is = cl.getResourceAsStream(file);
			if (is == null){
				continue;
			}
			Properties properties = new Properties();
			try {
				properties.load(is);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			String _value = properties.getProperty(key);
			if(_value != null){
				result = _value;
			}
		}
		return result;
	}

	/**
	 * Override is not supported for update
	 */
	@Override
	public void modifyValue(String key, String value) {
		config.setProperty(key, value);

		SolrResourceLoader loader = new SolrResourceLoader(null);
		ClassLoader classLoader = loader.getClassLoader();
		URL url = classLoader.getResource(propertiesFile);

		try {
		    if ( url == null ) {
			config.store(new FileOutputStream(new File(loader.locateSolrHome() + "/conf/" + propertiesFile)), null);
		    }
		    else {
			config.store(new FileOutputStream(new File(url.toURI())), null);
		    }

		} catch (FileNotFoundException e) {
			// TODO 自動生成された catch ブロック
			e.printStackTrace();
		} catch (IOException e) {
			// TODO 自動生成された catch ブロック
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO 自動生成された catch ブロック
			e.printStackTrace();
		}

	}

	/**
	 * Override is not supported for update
	 */
	@Override
	public void addValue(String key, String value){
		String currentVal = config.getProperty(key);
		String[] currentVals = currentVal.split(",");
		List<String>valList = new ArrayList<String>();
		Collections.addAll(valList, currentVals);

		valList.add(0,value);
		String newVal = StringUtils.join(valList.toArray(), ",");
		config.setProperty(key, newVal);

		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		URL url = classLoader.getResource(propertiesFile);
		try {
			config.store(new FileOutputStream(new File(url.toURI())), null);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Override is not supported for update
	 */
	@Override
	public void removeValue(String key, String value){
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
			try {
				config.store(new FileOutputStream(new File(url.toURI())), null);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
		}
	}

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
