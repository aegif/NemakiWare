package jp.aegif.nemaki.util.spring;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class SpringPropertiesUtil extends PropertySourcesPlaceholderConfigurer {

	private static final Log log = LogFactory
			.getLog(SpringPropertiesUtil.class);

    private Map<String, String> propertiesMap;
    
    public SpringPropertiesUtil() {
        System.out.println("=== SPRING PROPERTIES DEBUG: SpringPropertiesUtil constructor called ===");
        log.info("=== SPRING PROPERTIES DEBUG: SpringPropertiesUtil constructor called ===");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        System.out.println("=== SPRING PROPERTIES DEBUG: postProcessBeanFactory called (Spring 6.x version) ===");
        log.info("=== SPRING PROPERTIES DEBUG: postProcessBeanFactory called (Spring 6.x version) ===");
        
        super.postProcessBeanFactory(beanFactory);

        propertiesMap = new HashMap<String, String>();
        
        try {
            MutablePropertySources appliedPropertySources = (MutablePropertySources) getAppliedPropertySources();
            if (appliedPropertySources != null) {
                System.out.println("=== SPRING PROPERTIES DEBUG: Found " + appliedPropertySources.size() + " property sources ===");
                log.info("=== SPRING PROPERTIES DEBUG: Found " + appliedPropertySources.size() + " property sources ===");
                
                for (PropertySource<?> propertySource : appliedPropertySources) {
                    if (propertySource.getSource() instanceof Properties) {
                        Properties props = (Properties) propertySource.getSource();
                        for (Object key : props.keySet()) {
                            String keyStr = key.toString();
                            String valueStr = props.getProperty(keyStr);
                            propertiesMap.put(keyStr, valueStr);
                            
                            if (keyStr.contains("couchdb.url")) {
                                System.out.println("=== SPRING PROPERTIES DEBUG: " + keyStr + " = " + valueStr);
                                log.info("=== SPRING PROPERTIES DEBUG: " + keyStr + " = " + valueStr);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("=== SPRING PROPERTIES DEBUG: Error extracting properties: " + e.getMessage());
            log.warn("=== SPRING PROPERTIES DEBUG: Error extracting properties: " + e.getMessage(), e);
        }
        
        System.out.println("=== SPRING PROPERTIES DEBUG: postProcessBeanFactory completed, propertiesMap size: " + propertiesMap.size() + " ===");
        log.info("=== SPRING PROPERTIES DEBUG: postProcessBeanFactory completed, propertiesMap size: " + propertiesMap.size() + " ===");
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
