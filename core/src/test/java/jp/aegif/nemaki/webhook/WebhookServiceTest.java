package jp.aegif.nemaki.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * - Trigger webhook logic with dispatch verification
 */
public class WebhookServiceTest {

    private WebhookServiceImpl webhookService;
    private ContentService mockContentService;
    private CallContext mockCallContext;
    private WebhookDeliveryService mockDeliveryService;
    private ExecutorService mockExecutorService;

    @BeforeEach
    public void setUp() throws Exception {
        webhookService = new WebhookServiceImpl();
        mockContentService = mock(ContentService.class);
        mockCallContext = mock(CallContext.class);
        mockDeliveryService = mock(WebhookDeliveryService.class);
        webhookService.setContentService(mockContentService);

        when(mockCallContext.getUsername()).thenReturn("testuser");

        // Inject mock deliveryService via reflection for dispatch verification
        setPrivateField(webhookService, "deliveryService", mockDeliveryService);

        // Replace executorService with a same-thread executor so dispatch runs synchronously
        mockExecutorService = new SameThreadExecutorService();
        setPrivateField(webhookService, "executorService", mockExecutorService);

        // Default: deliveryService returns a valid payload
        when(mockDeliveryService.buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString()))
            .thenAnswer(inv -> {
                WebhookPayload p = new WebhookPayload();
                p.setEventType(inv.getArgument(0));
                p.setObjectId(inv.getArgument(1));
                p.setRepositoryId(inv.getArgument(2));
                p.setDeliveryId("test-delivery-id");
                p.setTimestamp(System.currentTimeMillis());
                return p;
            });
        when(mockDeliveryService.serializePayload(any(WebhookPayload.class)))
            .thenReturn("{\"test\":\"payload\"}");
        when(mockDeliveryService.generateAuthHeaders(any(WebhookConfig.class)))
            .thenReturn(new HashMap<>());
    }

    // ========================================
    // hasWebhookConfig Tests
    // ========================================

    @Test
    public void testHasWebhookConfigWithNullContent() {
        assertFalse(webhookService.hasWebhookConfig("bedroom", null), "Should return false for null content");
    }

    @Test
    public void testHasWebhookConfigWithoutSecondaryType() {
        Content content = createMockContent("doc-1", null, null);

        assertFalse(webhookService.hasWebhookConfig("bedroom", content), "Should return false when no secondary type");
    }

    @Test
    public void testHasWebhookConfigWithSecondaryTypeButNoConfigs() {
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), null);

        assertFalse(webhookService.hasWebhookConfig("bedroom", content), "Should return false when secondary type exists but no configs");
    }

    @Test
    public void testHasWebhookConfigWithValidConfig() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        assertTrue(webhookService.hasWebhookConfig("bedroom", content), "Should return true when valid config exists");
    }

    // ========================================
    // getWebhookConfigs Tests
    // ========================================

    @Test
    public void testGetWebhookConfigsWithNullContent() {
        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", null);

        assertTrue(configs.isEmpty(), "Should return empty list for null content");
    }

    @Test
    public void testGetWebhookConfigsWithoutSecondaryType() {
        Content content = createMockContent("doc-1", null, null);

        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);

        assertTrue(configs.isEmpty(), "Should return empty list when no secondary type");
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

        assertEquals(2, configs.size(), "Should return 2 configs");
        assertEquals("webhook-1", configs.get(0).getId(), "First config should have correct ID");
        assertEquals("webhook-2", configs.get(1).getId(), "Second config should have correct ID");
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

        assertEquals(1, configs.size(), "Should return only enabled configs");
        assertEquals("webhook-1", configs.get(0).getId(), "Should return the enabled config");
    }

    @Test
    public void testGetWebhookConfigsSetsSourceObjectId() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-123",
            Arrays.asList("nemaki:webhookable"), configJson);

        List<WebhookConfig> configs = webhookService.getWebhookConfigs("bedroom", content);

        assertEquals("doc-123", configs.get(0).getSourceObjectId(), "Should set sourceObjectId");
    }

    // ========================================
    // getInheritedWebhookConfigs Tests
    // ========================================

    @Test
    public void testGetInheritedWebhookConfigsWithNullContent() {
        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", null);

        assertTrue(configs.isEmpty(), "Should return empty list for null content");
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

        assertEquals(1, configs.size(), "Should inherit config from parent");
        assertEquals("parent-webhook", configs.get(0).getId(), "Should have parent's webhook ID");
    }

    @Test
    public void testGetInheritedWebhookConfigsRespectsMaxDepth() {
        String grandparentConfigJson = "[{\"id\":\"grandparent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":true,\"maxDepth\":1}]";
        Folder grandparentFolder = createMockFolder("folder-0", null,
            Arrays.asList("nemaki:webhookable"), grandparentConfigJson);

        Folder parentFolder = createMockFolder("folder-1", "folder-0", null, null);
        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);

        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);
        when(mockContentService.getContent("bedroom", "folder-0")).thenReturn(grandparentFolder);

        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);

        assertTrue(configs.isEmpty(), "Should not inherit config beyond maxDepth");
    }

    @Test
    public void testGetInheritedWebhookConfigsIncludesMaxDepthBoundary() {
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":true,\"maxDepth\":1}]";
        Folder parentFolder = createMockFolder("folder-1", null,
            Arrays.asList("nemaki:webhookable"), parentConfigJson);

        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);

        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);

        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);

        assertEquals(1, configs.size(), "Should inherit config at maxDepth boundary");
        assertEquals("parent-webhook", configs.get(0).getId(), "Should have parent's webhook ID");
    }

    @Test
    public void testGetInheritedWebhookConfigsIgnoresNonIncludeChildrenConfigs() {
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":false}]";
        Folder parentFolder = createMockFolder("folder-1", null,
            Arrays.asList("nemaki:webhookable"), parentConfigJson);

        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);

        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);

        List<WebhookConfig> configs = webhookService.getInheritedWebhookConfigs("bedroom", childDoc);

        assertTrue(configs.isEmpty(), "Should not inherit config when includeChildren=false");
    }

    // ========================================
    // triggerWebhook Tests
    // ========================================

    @Test
    public void testTriggerWebhookWithNullContent() {
        webhookService.triggerWebhook(mockCallContext, "bedroom", null, ChangeType.CREATED, null);

        // Verify no dispatch attempt was made
        verifyNoInteractions(mockDeliveryService);
    }

    @Test
    public void testTriggerWebhookWithNoMatchingConfigs() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"DELETED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.CREATED, null);

        // CREATED event does not match DELETED config → no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookDispatchesForMatchingConfig() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.CREATED, null);

        // Verify dispatch was invoked with correct event type and object ID
        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CREATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
        verify(mockDeliveryService, times(1)).serializePayload(any(WebhookPayload.class));
    }

    @Test
    public void testTriggerWebhookHandlesAllSupportedEventTypes() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\",\"UPDATED\",\"DELETED\",\"SECURITY\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.CREATED, null);
        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.UPDATED, null);
        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.DELETED, null);
        webhookService.triggerWebhook(mockCallContext, "bedroom", content, ChangeType.SECURITY, null);

        // All 4 events should trigger dispatch
        verify(mockDeliveryService, times(4)).buildPayload(anyString(), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
    }

    @Test
    public void testTriggerWebhookWithNullCallContext() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        // Should dispatch even with null CallContext (userId will be null in payload)
        webhookService.triggerWebhook(null, "bedroom", content, ChangeType.CREATED, null);

        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CREATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
    }

    @Test
    public void testTriggerWebhookWithNullRepositoryId() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhook(mockCallContext, null, content, ChangeType.CREATED, null);

        // Null repositoryId → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookWithNullChangeType() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhook(mockCallContext, "bedroom", content, null, null);

        // Null ChangeType → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookInheritsFromParentFolder() {
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"],\"includeChildren\":true,\"maxDepth\":5}]";
        Folder parentFolder = createMockFolder("folder-1", null,
            Arrays.asList("nemaki:webhookable"), parentConfigJson);

        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);

        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);

        webhookService.triggerWebhook(mockCallContext, "bedroom", childDoc, ChangeType.CREATED, null);

        // Child has no own config, but inherits from parent → dispatch should occur
        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CREATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
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

        assertTrue(log.isSuccess(), "Should be marked as success");
        assertEquals(Integer.valueOf(200), log.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", log.getResponseBody());
        assertEquals(Long.valueOf(150), log.getResponseTimeMs());
        assertEquals(WebhookDeliveryLog.DeliveryStatus.SUCCESS, log.getStatus());
    }

    @Test
    public void testWebhookDeliveryLogMarkFailed() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.markFailed(500, "Internal Server Error", 200);

        assertFalse(log.isSuccess(), "Should be marked as failed");
        assertEquals(Integer.valueOf(500), log.getStatusCode());
        assertEquals("Internal Server Error", log.getErrorMessage());
        assertEquals(Long.valueOf(200), log.getResponseTimeMs());
        assertEquals(WebhookDeliveryLog.DeliveryStatus.FAILED, log.getStatus());
    }

    @Test
    public void testWebhookDeliveryLogTruncatesLongResponse() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();

        StringBuilder longResponse = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longResponse.append("x");
        }

        log.markSuccess(200, longResponse.toString(), 100);

        assertTrue(log.getResponseBody().length() < 5000, "Response should be truncated");
        assertTrue(log.getResponseBody().endsWith("... (truncated)"), "Response should end with truncation marker");
    }

    // ========================================
    // triggerWebhookByEventType Tests (P2/P3 Regression for CONTENT_UPDATED)
    // ========================================

    @Test
    public void testTriggerWebhookByEventTypeWithNullContent() {
        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", null, "CONTENT_UPDATED", null);

        // Null content → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookByEventTypeWithNullRepositoryId() {
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"),
            "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]");

        webhookService.triggerWebhookByEventType(mockCallContext, null, content, "CONTENT_UPDATED", null);

        // Null repositoryId → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookByEventTypeWithNullEventType() {
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"),
            "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]");

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, null, null);

        // Null eventType → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookByEventTypeWithInvalidEventType() {
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"),
            "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]");

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "INVALID_TYPE", null);

        // Invalid eventType → early return, no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookByEventTypeContentUpdatedDispatches() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", null);

        // Verify dispatch was invoked with correct event type and object ID
        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
        verify(mockDeliveryService, times(1)).serializePayload(any(WebhookPayload.class));
    }

    @Test
    public void testTriggerWebhookByEventTypePayloadContainsProperties() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);
        when(content.getCreator()).thenReturn("admin");
        when(content.getModifier()).thenReturn("testuser");

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", null);

        // Capture the properties map passed to buildPayload
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockDeliveryService).buildPayload(eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"),
            propsCaptor.capture(), eq("token-123"));

        Map<String, Object> props = propsCaptor.getValue();
        assertEquals("doc-1", props.get("cmis:objectId"), "cmis:objectId should be set");
        assertEquals("test-content", props.get("cmis:name"), "cmis:name should be set");
        assertEquals("cmis:document", props.get("cmis:objectTypeId"), "cmis:objectTypeId should be set");
        assertEquals("admin", props.get("cmis:createdBy"), "cmis:createdBy should be set");
        assertEquals("testuser", props.get("cmis:lastModifiedBy"), "cmis:lastModifiedBy should be set");
        assertEquals("token-123", props.get("cmis:changeToken"), "cmis:changeToken should be set");
    }

    @Test
    public void testTriggerWebhookByEventTypeWithAdditionalProperties() {
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        Map<String, Object> additionalProps = new HashMap<>();
        additionalProps.put("contentLength", 12345L);
        additionalProps.put("mimeType", "application/pdf");

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", additionalProps);

        // Verify dispatch occurred and additional properties are included
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockDeliveryService).buildPayload(eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"),
            propsCaptor.capture(), eq("token-123"));

        Map<String, Object> props = propsCaptor.getValue();
        assertEquals(12345L, props.get("contentLength"), "Additional property contentLength should be passed");
        assertEquals("application/pdf", props.get("mimeType"), "Additional property mimeType should be passed");
        // Standard properties should still be present
        assertEquals("doc-1", props.get("cmis:objectId"), "cmis:objectId should not be overwritten");
    }

    @Test
    public void testTriggerWebhookByEventTypeInheritsFromParentDispatches() {
        String parentConfigJson = "[{\"id\":\"parent-webhook\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"],\"includeChildren\":true,\"maxDepth\":5}]";
        Folder parentFolder = createMockFolder("folder-1", null,
            Arrays.asList("nemaki:webhookable"), parentConfigJson);

        Document childDoc = createMockDocument("doc-1", "folder-1", null, null);

        when(mockContentService.getContent("bedroom", "folder-1")).thenReturn(parentFolder);

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", childDoc, "CONTENT_UPDATED", null);

        // Child inherits CONTENT_UPDATED config from parent → dispatch should occur
        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
    }

    @Test
    public void testTriggerWebhookByEventTypeNoMatchingConfigNoDispatch() {
        // Config only listens for CREATED, not CONTENT_UPDATED
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CREATED\"]}]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", null);

        // CONTENT_UPDATED does not match CREATED config → no dispatch
        verify(mockDeliveryService, never()).buildPayload(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    public void testTriggerWebhookByEventTypeMultipleMatchingConfigs() {
        String configJson = "[" +
            "{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://a.example.com/hook\",\"events\":[\"CONTENT_UPDATED\"]}," +
            "{\"id\":\"webhook-2\",\"enabled\":true,\"url\":\"https://b.example.com/hook\",\"events\":[\"CONTENT_UPDATED\",\"CREATED\"]}" +
            "]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", null);

        // Both configs match CONTENT_UPDATED → 2 dispatches
        verify(mockDeliveryService, times(2)).buildPayload(
            eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
    }

    @Test
    public void testTriggerWebhookByEventTypeDisabledConfigNotDispatched() {
        String configJson = "[" +
            "{\"id\":\"webhook-active\",\"enabled\":true,\"url\":\"https://a.example.com/hook\",\"events\":[\"CONTENT_UPDATED\"]}," +
            "{\"id\":\"webhook-disabled\",\"enabled\":false,\"url\":\"https://b.example.com/hook\",\"events\":[\"CONTENT_UPDATED\"]}" +
            "]";
        Content content = createMockContent("doc-1",
            Arrays.asList("nemaki:webhookable"), configJson);

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", content, "CONTENT_UPDATED", null);

        // Only the enabled config should dispatch
        verify(mockDeliveryService, times(1)).buildPayload(
            eq("CONTENT_UPDATED"), eq("doc-1"), eq("bedroom"), anyMap(), eq("token-123"));
    }

    // ========================================
    // Consecutive Content Update Regression Tests
    // ========================================

    @Test
    public void testConsecutiveContentUpdatesHaveCorrectObjectIdInPayload() {
        // Regression: consecutive setContentStream calls must pass the correct
        // objectId and changeToken for each document, not stale values.
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]";

        Document doc1 = createMockDocument("doc-v1.0", "folder-1",
            Arrays.asList("nemaki:webhookable"), configJson);
        when(doc1.getChangeToken()).thenReturn("token-v1.0");
        when(doc1.getName()).thenReturn("doc-v1");

        Document doc2 = createMockDocument("doc-v2.0", "folder-1",
            Arrays.asList("nemaki:webhookable"), configJson);
        when(doc2.getChangeToken()).thenReturn("token-v2.0");
        when(doc2.getName()).thenReturn("doc-v2");

        // Consecutive calls simulating two rapid setContentStream operations
        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", doc1, "CONTENT_UPDATED", null);
        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", doc2, "CONTENT_UPDATED", null);

        // Capture all buildPayload invocations
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> objectIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> changeTokenCaptor = ArgumentCaptor.forClass(String.class);

        verify(mockDeliveryService, times(2)).buildPayload(
            eq("CONTENT_UPDATED"), objectIdCaptor.capture(), eq("bedroom"),
            anyMap(), changeTokenCaptor.capture());

        List<String> objectIds = objectIdCaptor.getAllValues();
        List<String> changeTokens = changeTokenCaptor.getAllValues();

        assertEquals("doc-v1.0", objectIds.get(0), "First call should use doc-v1.0 objectId");
        assertEquals("token-v1.0", changeTokens.get(0), "First call should use token-v1.0");
        assertEquals("doc-v2.0", objectIds.get(1), "Second call should use doc-v2.0 objectId");
        assertEquals("token-v2.0", changeTokens.get(1), "Second call should use token-v2.0");
    }

    @Test
    public void testConsecutiveContentUpdatesPropertiesMapIsolation() {
        // Regression: each triggerWebhookByEventType call must produce an
        // independent properties Map, preventing cross-contamination between events.
        String configJson = "[{\"id\":\"webhook-1\",\"enabled\":true,\"url\":\"https://example.com/webhook\",\"events\":[\"CONTENT_UPDATED\"]}]";

        Document doc1 = createMockDocument("doc-alpha", "folder-1",
            Arrays.asList("nemaki:webhookable"), configJson);
        when(doc1.getName()).thenReturn("alpha-document");
        when(doc1.getChangeToken()).thenReturn("token-alpha");

        Document doc2 = createMockDocument("doc-beta", "folder-1",
            Arrays.asList("nemaki:webhookable"), configJson);
        when(doc2.getName()).thenReturn("beta-document");
        when(doc2.getChangeToken()).thenReturn("token-beta");

        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", doc1, "CONTENT_UPDATED", null);
        webhookService.triggerWebhookByEventType(mockCallContext, "bedroom", doc2, "CONTENT_UPDATED", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockDeliveryService, times(2)).buildPayload(
            eq("CONTENT_UPDATED"), anyString(), eq("bedroom"),
            propsCaptor.capture(), anyString());

        List<Map<String, Object>> allProps = propsCaptor.getAllValues();
        Map<String, Object> props1 = allProps.get(0);
        Map<String, Object> props2 = allProps.get(1);

        // Verify each Map has the correct values for its respective document
        assertEquals("alpha-document", props1.get("cmis:name"), "First props should have alpha name");
        assertEquals("doc-alpha", props1.get("cmis:objectId"), "First props should have alpha objectId");
        assertEquals("beta-document", props2.get("cmis:name"), "Second props should have beta name");
        assertEquals("doc-beta", props2.get("cmis:objectId"), "Second props should have beta objectId");

        // Verify isolation: the two Maps must not be the same instance
        assertNotSame(props1, props2, "Properties maps should be independent instances");
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

    /**
     * Set a private field on an object via reflection.
     */
    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * ExecutorService that runs tasks on the calling thread for synchronous test verification.
     */
    private static class SameThreadExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown = false;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() { shutdown = true; }

        @Override
        public List<Runnable> shutdownNow() { shutdown = true; return new ArrayList<>(); }

        @Override
        public boolean isShutdown() { return shutdown; }

        @Override
        public boolean isTerminated() { return shutdown; }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
    }
}
