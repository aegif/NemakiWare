package jp.aegif.nemaki.webhook;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.WebhookServiceImpl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;

/**
 * Unit tests for WebhookServiceImpl.
 * 
 * Tests cover:
 * - Webhook configuration detection
 * - Event type conversion
 * - Inherited configuration retrieval
 * - Trigger webhook logic
 */
public class WebhookServiceTest {
    
    private WebhookServiceImpl webhookService;
    private ContentService mockContentService;
    private CallContext mockCallContext;
    
    @Before
    public void setUp() {
        webhookService = new WebhookServiceImpl();
        mockContentService = mock(ContentService.class);
        mockCallContext = mock(CallContext.class);
        webhookService.setContentService(mockContentService);
        
        when(mockCallContext.getUsername()).thenReturn("testuser");
    }
    
    // ========================================
    // hasWebhookConfig Tests
    // ========================================
    
    @Test
    public void testHasWebhookConfigWithNullContent() {
        assertFalse("Should return false for null content", 
            webhookService.hasWebhookConfig("bedroom", null));
    }
    
    @Test
    public void testHasWebhookConfigWithoutSecondaryType() {
        Content content = createMockContent("doc-1", null, null);
        
        assertFalse("Should return false when no secondary type", 
            webhookService.hasWebhookConfig("bedroom", content));
    }
    
    @Test
    public void testHasWebhookConfigWithSecondaryTypeButNoConfigs() {
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), null);
        
        assertFalse("Should return false when secondary type exists but no configs", 
            webhookService.hasWebhookConfig("bedroom", content));
    }
    
    @Test
    public void testHasWebhookConfigWithValidConfig() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), configJson);
        
        assertTrue("Should return true when valid config exists", 
            webhookService.hasWebhookConfig("bedroom", content));
    }
    
    // ========================================
    // getWebhookConfigs Tests
    // ========================================
    
    @Test
    public void testGetWebhookConfigsWithNullContent() {
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", null);
        
        assertTrue("Should return empty list for null content", configs.isEmpty());
    }
    
    @Test
    public void testGetWebhookConfigsWithoutSecondaryType() {
        Content content = createMockContent("doc-1", null, null);
        
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);
        
        assertTrue("Should return empty list when no secondary type", configs.isEmpty());
    }
    
    @Test
    public void testGetWebhookConfigsWithValidConfigs() {
        String configJson = "[" +
            "{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook1\",\"events\":[\"CREATED\"]}," +
            "{\"id\":\"webhook-2\",\"enabled\":true,\"url\":\"https://example.com/webhook2\",\"events\":[\"UPDATED\"]}" +
            "]";
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), configJson);
        
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);
        
        assertEquals("Should return 2 configs", 2, configs.size());
        assertEquals("First config should have correct ID", "webhook-1", configs.get(0).getId());
        assertEquals("Second config should have correct ID", "webhook-2", configs.get(1).getId());
    }
    
    @Test
    public void testGetWebhookConfigsFiltersDisabledConfigs() {
        String configJson = "[" +
            "{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook1\",\"events\":[\"CREATED\"]}," +
            "{\"id\":\"webhook-2\",\"enabled\":false,\"url\":\"https://example.com/webhook2\",\"events\":[\"UPDATED\"]}" +
            "]";
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), configJson);
        
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);
        
        assertEquals("Should return only enabled configs", 1, configs.size());
        assertEquals("Should return the enabled config", "webhook-1", configs.get(0).getId());
    }
    
    @Test
    public void testGetWebhookConfigsSetsSourceObjectId() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-123", 
            Arrays.asList("nemaki:webhookable"), configJson);
        
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);
        
        assertEquals("Should set sourceObjectId", "doc-123", configs.get(0).getSourceObjectId());
    }
    
    // ========================================
    // getInheritedWebhookConfigs Tests
    // ========================================
    
    @Test
    public void testGetInheritedWebhookConfigsWithNullContent() {
        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", null);
        
        assertTrue("Should return empty list for null content", configs.isEmpty());
    }
    
    @Test
    public void testGetInheritedWebhookConfigsFromParentFolder() {
        // Create parent folder with webhook config that includes children
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":true,\"maxDepth\":5}]";
        Folder parentFolder = createMockFolder("folder-1", null, 
            Arrays.asList("nemaki:webhookable"), parentConfigJson);
        
        // Create child document
        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);
        
        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);
        
        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);
        
        assertEquals("Should inherit config from parent", 1, configs.size());
        assertEquals("Should have parent's webhook ID", "parent-webhook", configs.get(0).getId());
    }
    
    @Test
    public void testGetInheritedWebhookConfigsRespectsMaxDepth() {
        // Create parent folder with webhook config that has maxDepth=1
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":true,\"maxDepth\":1}]";
        Folder grandparentFolder = createMockFolder("folder-0", null, 
            Arrays.asList("nemaki:webhookable"), parentConfigJson);
        
        // Create intermediate folder (depth 1)
        Folder parentFolder = createMockFolder("folder-1", "folder-0", null, null);
        
        // Create child document (depth 2 - beyond maxDepth)
        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);
        
        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);
        when(mockContentService.getContent("bedroom", "folder-0")).thenReturn(grandparentFolder);
        
        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);
        
        // Depth 0 = folder-1 (parent of doc-1)
        // Depth 1 = folder-0 (grandparent of doc-1)
        // maxDepth=1 means only depth 0 is included
        // So grandparent's config should NOT be inherited
        assertTrue("Should not inherit config beyond maxDepth", configs.isEmpty());
    }
    
    @Test
    public void testGetInheritedWebhookConfigsIgnoresNonIncludeChildrenConfigs() {
        // Create parent folder with webhook config that does NOT include children
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":false}]";
        Folder parentFolder = createMockFolder("folder-1", null, 
            Arrays.asList("nemaki:webhookable"), parentConfigJson);
        
        // Create child document
        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);
        
        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);
        
        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);
        
        assertTrue("Should not inherit config when includeChildren=false", configs.isEmpty());
    }
    
    // ========================================
    // triggerWebhook Tests
    // ========================================
    
    @Test
    public void testTriggerWebhookWithNullContent() {
        // Should not throw exception
        webhookService.triggerWebhook(mockCallContext, "bedroom", null, ChangeType.CREATED, null);
    }
    
    @Test
    public void testTriggerWebhookWithUnsupportedEventType() {
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), 
            "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CHILD_CREATED\"]}]");
        
        // CHILD_CREATED is not supported in Phase 1
        // Should not throw exception, just skip
        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.CREATED, null);
    }
    
    @Test
    public void testTriggerWebhookWithNoMatchingConfigs() {
        // Config only listens for DELETED events
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"DELETED\"]}]";
        Content content = createMockContent("doc-1", 
            Arrays.asList("nemaki:webhookable"), configJson);
        
        // Trigger CREATED event - should not match
        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.CREATED, null);
        
        // No exception should be thrown
    }
    
    // ========================================
    // WebhookDeliveryLog Tests
    // ========================================
    
    @Test
    public void testWebhookDeliveryLogCreation() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.setDeliveryId("delivery-123");
        log.setAttemptNumber(1);
        log.generateAttemptId();
        
        assertEquals("delivery-123", log.getDeliveryId());
        assertEquals("delivery-123-1", log.getAttemptId());
        assertEquals(1, log.getAttemptNumber());
        assertEquals(WebhookDeliveryLog.DeliveryStatus.PENDING, log.getStatus());
    }
    
    @Test
    public void testWebhookDeliveryLogMarkSuccess() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.markSuccess(200, "{\"status\":\"ok\"}", 150);
        
        assertTrue("Should be marked as success", log.isSuccess());
        assertEquals(Integer.valueOf(200), log.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", log.getResponseBody());
        assertEquals(Long.valueOf(150), log.getResponseTimeMs());
        assertEquals(WebhookDeliveryLog.DeliveryStatus.SUCCESS, log.getStatus());
    }
    
    @Test
    public void testWebhookDeliveryLogMarkFailed() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.markFailed(500, "Internal Server Error", 200);
        
        assertFalse("Should be marked as failed", log.isSuccess());
        assertEquals(Integer.valueOf(500), log.getStatusCode());
        assertEquals("Internal Server Error", log.getErrorMessage());
        assertEquals(Long.valueOf(200), log.getResponseTimeMs());
        assertEquals(WebhookDeliveryLog.DeliveryStatus.FAILED, log.getStatus());
    }
    
    @Test
    public void testWebhookDeliveryLogTruncatesLongResponse() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        
        // Create a response longer than 4096 characters
        StringBuilder longResponse = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longResponse.append("x");
        }
        
        log.markSuccess(200, longResponse.toString(), 100);
        
        assertTrue("Response should be truncated", log.getResponseBody().length() < 5000);
        assertTrue("Response should end with truncation marker", 
            log.getResponseBody().endsWith("... (truncated)"));
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private Content createMockContent(String id, List<String> secondaryTypes, String webhookConfigs) {
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(id);
        when(content.getSecondaryIds()).thenReturn(secondaryTypes);
        when(content.getName()).thenReturn("test-content");
        when(content.getObjectType()).thenReturn("cmis:document");
        when(content.getChangeToken()).thenReturn("token-123");
        
        if (webhookConfigs != null) {
            List<Property> subTypeProps = new ArrayList<>();
            subTypeProps.add(new Property("nemaki:webhookConfigs", webhookConfigs));
            when(content.getSubTypeProperties()).thenReturn(subTypeProps);
        }
        
        return content;
    }
    
    private Document createMockDocument(String id, String parentId, 
                                         List<String> secondaryTypes, String webhookConfigs) {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(id);
        when(doc.getParentId()).thenReturn(parentId);
        when(doc.getSecondaryIds()).thenReturn(secondaryTypes);
        when(doc.getName()).thenReturn("test-document");
        when(doc.getObjectType()).thenReturn("cmis:document");
        when(doc.getChangeToken()).thenReturn("token-123");
        
        if (webhookConfigs != null) {
            List<Property> subTypeProps = new ArrayList<>();
            subTypeProps.add(new Property("nemaki:webhookConfigs", webhookConfigs));
            when(doc.getSubTypeProperties()).thenReturn(subTypeProps);
        }
        
        return doc;
    }
    
    private Folder createMockFolder(String id, String parentId, 
                                     List<String> secondaryTypes, String webhookConfigs) {
        Folder folder = mock(Folder.class);
        when(folder.getId()).thenReturn(id);
        when(folder.getParentId()).thenReturn(parentId);
        when(folder.getSecondaryIds()).thenReturn(secondaryTypes);
        when(folder.getName()).thenReturn("test-folder");
        when(folder.getObjectType()).thenReturn("cmis:folder");
        when(folder.getChangeToken()).thenReturn("token-123");
        
        if (webhookConfigs != null) {
            List<Property> subTypeProps = new ArrayList<>();
            subTypeProps.add(new Property("nemaki:webhookConfigs", webhookConfigs));
            when(folder.getSubTypeProperties()).thenReturn(subTypeProps);
        }
        
        return folder;
    }
}
