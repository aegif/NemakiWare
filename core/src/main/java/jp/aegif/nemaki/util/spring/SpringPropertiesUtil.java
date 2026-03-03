package jp.aegif.nemaki.util.spring;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Extended PropertySourcesPlaceholderConfigurer that captures all resolved
 * properties into a map for programmatic access throughout NemakiWare.
 *
 * <p>Migrated from PropertyPlaceholderConfigurer (deprecated for removal in Spring 7)
 * to PropertySourcesPlaceholderConfigurer which leverages the Spring Environment
 * and PropertySource abstractions.</p>
 */
public class SpringPropertiesUtil extends PropertySourcesPlaceholderConfigurer {

	private static final Log log = LogFactory
			.getLog(SpringPropertiesUtil.class);

    private Map<String, String> propertiesMap;
    private Map<String, String> propertySourceMap = new HashMap<>();
    private Resource[] locationResources;

    @Override
    public void setLocations(Resource... locations) {
        super.setLocations(locations);
        this.locationResources = locations;
    }

    @Override
    protected Properties mergeProperties() throws IOException {
        Properties mergedProps = super.mergeProperties();

        // Track which file each property came from
        // Later files override earlier ones, so the last file defining a key is the "source"
        if (locationResources != null) {
            for (Resource resource : locationResources) {
                if (!resource.exists()) {
                    continue;
                }
                try (InputStream is = resource.getInputStream()) {
                    Properties fileProps = new Properties();
                    fileProps.load(is);
                    String fileName = resource.getFilename();
                    if (fileName == null) {
                        fileName = resource.getDescription();
                    }
                    for (Object key : fileProps.keySet()) {
                        propertySourceMap.put(key.toString(), fileName);
                    }
                } catch (IOException e) {
                    log.debug("Could not load resource for source tracking: " + resource, e);
                }
            }
        }

        return mergedProps;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        super.postProcessBeanFactory(beanFactory);

        // Capture all resolved properties from the applied property sources
        propertiesMap = new HashMap<>();
        PropertySources appliedSources = getAppliedPropertySources();
        if (appliedSources != null) {
            PropertySource<?> localSource = appliedSources.get(LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME);
            if (localSource != null && localSource.getSource() instanceof Properties props) {
                for (Object key : props.keySet()) {
                    String keyStr = key.toString();
                    // Resolve property value through the full property source chain
                    // (environment properties override local when systemPropertiesMode=OVERRIDE)
                    Object resolved = null;
                    for (PropertySource<?> ps : appliedSources) {
                        resolved = ps.getProperty(keyStr);
                        if (resolved != null) {
                            break;
                        }
                    }
                    if (resolved != null) {
                        propertiesMap.put(keyStr, resolved.toString());
                    } else {
                        // Fall back to the raw local value
                        Object rawValue = props.get(key);
                        if (rawValue != null) {
                            propertiesMap.put(keyStr, rawValue.toString());
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the source file name for the given property key.
     * @param key the property key
     * @return the file name where the property was last defined, or null if unknown
     */
    public String getPropertySource(String key) {
        return propertySourceMap.get(key);
    }

    /**
     * Returns an unmodifiable view of the property source map.
     * @return map of property key to source file name
     */
    public Map<String, String> getPropertySourceMap() {
        return Collections.unmodifiableMap(propertySourceMap);
    }

    public String getValue(String key) {
    	String value = propertiesMap.get(key);

    	if(log.isTraceEnabled()){
    		log.trace("key=" + key + " has no value");
    	}

        return value;
    }

    //TODO error handling
    public String getHeadValue(String key){
    	String val = propertiesMap.get(key).toString();
    	String[] _val = val.split(",");
    	if(_val.length == 0) return null;

    	return _val[0].trim();
    }

    public Set<String> getKeys(){
    	return propertiesMap.keySet();
    }

    public List<String> getValues(String key){
    	try{
    		String val = propertiesMap.get(key).toString();
        	String[] _val = val.split(",");
        	if(_val.length == 0) return null;

        	List<String> result = new ArrayList<String>();
        	for(String _v : _val){
        		result.add(_v.trim());
        	}

        	return result;
    	}catch(Exception e){
    		log.error("key=" + key, e);
    		return null;
    	}
    }
}
