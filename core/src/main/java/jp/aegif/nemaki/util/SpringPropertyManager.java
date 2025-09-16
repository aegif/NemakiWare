package jp.aegif.nemaki.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.ClassPathResource;

public class SpringPropertyManager{
	private static final Log log = LogFactory
			.getLog(PropertyManager.class);

	private Properties allProperties;

	public SpringPropertyManager(){
		System.out.println("=== SPRING PROPERTY MANAGER DEBUG: Constructor called ===");
		log.info("=== SPRING PROPERTY MANAGER DEBUG: Constructor called ===");
		loadProperties();
	}

	private void loadProperties() {
		allProperties = new Properties();
		
		String[] propertyFiles = {
			"nemakiware.properties",
			"nemakiware-capability.properties",
			"nemakiware-basetype.properties", 
			"nemakiware-property.properties",
			"nemakiware-dev.properties",
			"nemakiware-jetty.properties",
			"nemakiware-docker.properties",
			"custom-nemakiware.properties",
			"app-server-core.properties"
		};
		
		for (String fileName : propertyFiles) {
			try {
				ClassPathResource resource = new ClassPathResource(fileName);
				if (resource.exists()) {
					try (InputStream is = resource.getInputStream()) {
						Properties props = new Properties();
						props.load(is);
						allProperties.putAll(props);
						System.out.println("=== SPRING PROPERTY MANAGER DEBUG: Loaded " + fileName + " with " + props.size() + " properties ===");
						log.info("=== SPRING PROPERTY MANAGER DEBUG: Loaded " + fileName + " with " + props.size() + " properties ===");
					}
				} else {
					System.out.println("=== SPRING PROPERTY MANAGER DEBUG: " + fileName + " not found, skipping ===");
					log.info("=== SPRING PROPERTY MANAGER DEBUG: " + fileName + " not found, skipping ===");
				}
			} catch (IOException e) {
				System.out.println("=== SPRING PROPERTY MANAGER DEBUG: Error loading " + fileName + ": " + e.getMessage() + " ===");
				log.warn("=== SPRING PROPERTY MANAGER DEBUG: Error loading " + fileName + ": " + e.getMessage() + " ===", e);
			}
		}
		
		System.out.println("=== SPRING PROPERTY MANAGER DEBUG: Total properties loaded: " + allProperties.size() + " ===");
		log.info("=== SPRING PROPERTY MANAGER DEBUG: Total properties loaded: " + allProperties.size() + " ===");
	}

	/**
	 * Read a value of the property as a single string
	 * @param key
	 * @return
	 * @throws Exception
	 */
	public String readValue(String key){
		String value = allProperties.getProperty(key);
		if (key != null && (key.contains("couchdb.url") || key.contains("repository.definition"))) {
			System.out.println("=== SPRING PROPERTY MANAGER DEBUG: readValue(" + key + ") = " + value + " ===");
			log.info("=== SPRING PROPERTY MANAGER DEBUG: readValue(" + key + ") = " + value + " ===");
		}
		return value;
	}

	public String readHeadValue(String key) throws Exception{
		List<String> values = readValues(key);
		return values.isEmpty() ? null : values.get(0);
	}

	public List<String> readValues(String key) {
		String value = readValue(key);
		if (value == null) {
			return new ArrayList<>();
		}
		return Arrays.asList(value.split(","));
	}
	
	public boolean readBoolean(String key){
		String val = readValue(key);
		return Boolean.valueOf(val);
	}

	public void setPropertyConfigurer(Object propertyConfigurer) {
	}
}
