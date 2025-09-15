package jp.aegif.nemaki.util.spring;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class SpringPropertiesUtil extends PropertyPlaceholderConfigurer {

	private static final Log log = LogFactory
			.getLog(SpringPropertiesUtil.class);

    private Map<String, String> propertiesMap;
    
    public SpringPropertiesUtil() {
        System.out.println("=== SPRING PROPERTIES DEBUG: SpringPropertiesUtil constructor called ===");
        log.info("=== SPRING PROPERTIES DEBUG: SpringPropertiesUtil constructor called ===");
    }
    // Default as in PropertyPlaceholderConfigurer
    private int springSystemPropertiesMode = SYSTEM_PROPERTIES_MODE_FALLBACK;

    @Override
    public void setSystemPropertiesMode(int systemPropertiesMode) {
        super.setSystemPropertiesMode(systemPropertiesMode);
        springSystemPropertiesMode = systemPropertiesMode;
    }

    @Override
    protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) throws BeansException {
        System.out.println("=== SPRING PROPERTIES DEBUG: processProperties called with " + props.size() + " properties ===");
        log.info("=== SPRING PROPERTIES DEBUG: processProperties called with " + props.size() + " properties ===");
        
        super.processProperties(beanFactory, props);

        propertiesMap = new HashMap<String, String>();
        for (Object key : props.keySet()) {
            String keyStr = key.toString();
            String valueStr = resolvePlaceholder(keyStr, props, springSystemPropertiesMode);
            propertiesMap.put(keyStr, valueStr);
            
            if (keyStr.contains("couchdb.url")) {
                System.out.println("=== SPRING PROPERTIES DEBUG: " + keyStr + " = " + valueStr);
                System.out.println("=== SPRING PROPERTIES DEBUG: System property db.couchdb.url = " + System.getProperty("db.couchdb.url"));
            }
        }
        
        System.out.println("=== SPRING PROPERTIES DEBUG: processProperties completed ===");
        log.info("=== SPRING PROPERTIES DEBUG: processProperties completed ===");
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
