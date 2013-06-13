package jp.aegif.nemaki.api.resources;

import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;

public class AbstractResourceTest extends JerseyTest {
	
	public AbstractResourceTest() {
		super(new WebAppDescriptor.Builder("jp.aegif.nemaki.api.resources").contextPath("Nemaki")
		        .contextParam("contextConfigLocation", "applicationContext.xml")
		        .servletClass(jp.aegif.nemaki.api.resources.TestServletContainer.class)
		        .build());
	}
    
}
