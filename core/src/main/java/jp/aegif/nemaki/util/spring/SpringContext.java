package jp.aegif.nemaki.util.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class SpringContext implements ApplicationContextAware{
	private static ApplicationContext applicationContext;

	   public SpringContext() {

	   }

	   public static Object getBean(final String beanId) {
	       return applicationContext.getBean(beanId);
	   }

	   public static final ApplicationContext  getApplicationContext () {
	       return applicationContext;
	   }

	   public void setApplicationContext(final ApplicationContext applicationContext) {

	       SpringContext.applicationContext = applicationContext;

	   }

	   public void setNemakiApplicationContextLoader(NemakiApplicationContextLoader loader) {
	   }
}
