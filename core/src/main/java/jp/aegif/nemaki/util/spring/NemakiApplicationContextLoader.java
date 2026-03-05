package jp.aegif.nemaki.util.spring;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.context.support.XmlWebApplicationContext;

public class NemakiApplicationContextLoader implements ApplicationContextAware,
		InitializingBean {
	private XmlWebApplicationContext applicationContext;
	private PropertySourcesPlaceholderConfigurer propertyConfigurer;
	private String[] configLocations;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.applicationContext = (XmlWebApplicationContext) applicationContext;

	}

	@Override
	public void afterPropertiesSet() throws Exception {
		applicationContext.setConfigLocations(configLocations);
		applicationContext.addBeanFactoryPostProcessor(propertyConfigurer);
		applicationContext.refresh();
	}

	public void setPropertyConfigurer(
			PropertySourcesPlaceholderConfigurer propertyConfigurer) {
		this.propertyConfigurer = propertyConfigurer;
	}

	public void setConfigLocations(String[] configLocations) {
		this.configLocations = configLocations;
	}
}
