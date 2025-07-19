package jp.aegif.nemaki.test;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.assertEquals;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
    "classpath:applicationContext.xml",
    "classpath:serviceContext.xml",
    "classpath:businesslogicContext.xml",
    "classpath:daoContext.xml"
})
public class ContentStreamTest {

    @Autowired
    private PropertyManager propertyManager;

    @Test
    public void testContentStreamAllowedConfiguration() {
        String contentStreamAllowed = propertyManager.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED);
        System.out.println("Content Stream Allowed: " + contentStreamAllowed);
        
        // Verify that the configuration override is working
        assertEquals("Configuration should be 'allowed' for CMIS compliance", "allowed", contentStreamAllowed);
    }
}