package jp.aegif.nemaki.util.spring;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.support.XmlWebApplicationContext;

public class NemakiApplicationContextLoader implements ApplicationContextAware,
		InitializingBean {
	private XmlWebApplicationContext applicationContext;
	private PropertyPlaceholderConfigurer propertyConfigurer;
	private String[] configLocations;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		System.out.println("=== CONTEXT LOADER DEBUG: setApplicationContext called ===");
		this.applicationContext = (XmlWebApplicationContext) applicationContext;

	}

	@Override
	public void afterPropertiesSet() throws Exception {
		System.out.println("=== CONTEXT LOADER DEBUG: afterPropertiesSet called ===");
		System.out.println("=== CONTEXT LOADER DEBUG: configLocations length = " + (configLocations != null ? configLocations.length : "null") + " ===");
		if (configLocations != null) {
			for (int i = 0; i < configLocations.length; i++) {
				System.out.println("=== CONTEXT LOADER DEBUG: configLocation[" + i + "] = " + configLocations[i] + " ===");
			}
		}
		applicationContext.setConfigLocations(configLocations);
		applicationContext.addBeanFactoryPostProcessor(propertyConfigurer);
		System.out.println("=== CONTEXT LOADER DEBUG: About to call applicationContext.refresh() ===");
		applicationContext.refresh();
		System.out.println("=== CONTEXT LOADER DEBUG: applicationContext.refresh() completed successfully ===");
	}

	public void setPropertyConfigurer(
			PropertyPlaceholderConfigurer propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setConfigLocations(String[] configLocations) {
		this.configLocations = configLocations;
	}
}
