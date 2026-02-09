package jp.aegif.nemaki.util.spring;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
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

public class SpringPropertiesUtil extends PropertyPlaceholderConfigurer {

	private static final Log log = LogFactory
			.getLog(SpringPropertiesUtil.class);

    private Map<String, String> propertiesMap;
    private Map<String, String> propertySourceMap = new HashMap<>();
    private Resource[] locationResources;
    // Default as in PropertyPlaceholderConfigurer
    private int springSystemPropertiesMode = SYSTEM_PROPERTIES_MODE_FALLBACK;

    @Override
    public void setSystemPropertiesMode(int systemPropertiesMode) {
        super.setSystemPropertiesMode(systemPropertiesMode);
        springSystemPropertiesMode = systemPropertiesMode;
    }

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
    protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) throws BeansException {
        super.processProperties(beanFactory, props);

        propertiesMap = new HashMap<String, String>();
        for (Object key : props.keySet()) {
            String keyStr = key.toString();
            String valueStr = resolvePlaceholder(keyStr, props, springSystemPropertiesMode);
            propertiesMap.put(keyStr, valueStr);
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
