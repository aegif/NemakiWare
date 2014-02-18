package jp.aegif.nemaki.spring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;

public class SpringPropertiesUtil extends PropertyPlaceholderConfigurer {

    private Map<String, String> propertiesMap;
    // Default as in PropertyPlaceholderConfigurer
    private int springSystemPropertiesMode = SYSTEM_PROPERTIES_MODE_FALLBACK;

    @Override
    public void setSystemPropertiesMode(int systemPropertiesMode) {
        super.setSystemPropertiesMode(systemPropertiesMode);
        springSystemPropertiesMode = systemPropertiesMode;
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

    public String getValue(String key) {
        return propertiesMap.get(key).toString();
    }

    public String getHeadValue(String key){
    	String val = propertiesMap.get(key).toString();
    	String[] _val = val.split(","); 
    	if(_val.length == 0) return null;
    	
    	return _val[0].trim();
    }
   
    public List<String> getValues(String key){
    	String val = propertiesMap.get(key).toString();
    	String[] _val = val.split(","); 
    	if(_val.length == 0) return null;
    	
    	List<String> result = new ArrayList<String>();
    	for(String _v : _val){
    		result.add(_v.trim());
    	}
    	
    	return result;
    }

}
