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

import jp.aegif.nemaki.util.PropertyManager;

import org.apache.commons.lang3.StringUtils;

import ucar.unidata.util.StringUtil;



public class PropertyManagerImpl implements PropertyManager{

	private final String PATH = "tracking.properties"; 
	private String propertiesFile;
	private Properties config;
	
	/**
	 *Constructor setting default properties file and config object
	 * @param propertiesFile
	 * @throws Exception
	 */
	public PropertyManagerImpl(){
		this.propertiesFile = PATH;
		
		Properties config = new Properties();
		InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(propertiesFile);
		if(inputStream != null){
			try {
				config.load(inputStream);
			} catch (IOException e) {
				e.printStackTrace();
			}finally{
				try {
					inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			this.setConfig(config);
		}
	}
	
	/**
	 * Constructor setting specified properties file and config object
	 * @param propertiesFile
	 */
	public PropertyManagerImpl(String propertiesFile){
		this.setPropertiesFile(propertiesFile);

		Properties config = new Properties();
		InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(propertiesFile);
		if(inputStream != null){
			try {
				config.load(inputStream);
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.setConfig(config);
		}
	}
	
	@Override
	public String readValue(String key){
		String val = config.getProperty(key);
		return val;
	}
	
	@Override
	public List<String> readValues(String key){
		final String delimiter = ",";
		String val = readValue(key);
		return StringUtil.split(val, delimiter);
	}

	@Override
	public void modifyValue(String key, String value) {
		config.setProperty(key, value);
		
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		URL url = classLoader.getResource(propertiesFile);
		try {
			config.store(new FileOutputStream(new File(url.toURI())), null);
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
	}

	@Override
	public String readHeadValue(String key){
		String currentVal = config.getProperty(key);
		String[] currentVals = currentVal.split(","); 
		return currentVals[0];
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
