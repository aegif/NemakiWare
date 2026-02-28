/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Webhook REST API tests
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.webhook.WebhookConfig;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog;

/**
 * Unit tests for WebhookResource REST API.
 * 
 * These tests verify the JSON building methods and basic functionality
 * without requiring a full Spring context or HTTP server.
 */
public class WebhookResourceTest {

    private WebhookResource resource;

    @Before
    public void setUp() {
        resource = new WebhookResource();
    }

    @Test
    public void testBuildDeliveryLogJsonWithAllFields() throws Exception {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.setDeliveryId("delivery-123");
        log.setObjectId("object-456");
        log.setEventType("CREATED");
        log.setWebhookUrl("https://example.com/webhook");
        log.setStatusCode(200);
        log.setSuccess(true);
        log.setAttemptNumber(1);
        log.setTimestamp(new GregorianCalendar(2026, 0, 27, 14, 30, 0));
        log.setResponseBody("OK");

        // Use reflection to call private method
        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildDeliveryLogJson", WebhookDeliveryLog.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, log);

        assertEquals("delivery-123", json.get("deliveryId"));
        assertEquals("object-456", json.get("objectId"));
        assertEquals("CREATED", json.get("eventType"));
        assertEquals("https://example.com/webhook", json.get("webhookUrl"));
        assertEquals(200, json.get("statusCode"));
        assertEquals(true, json.get("success"));
        assertEquals(1, json.get("attemptCount"));
        assertNotNull(json.get("deliveredAt"));
        assertEquals("OK", json.get("responseBody"));
    }

    @Test
    public void testBuildDeliveryLogJsonWithNullTimestamp() throws Exception {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.setDeliveryId("delivery-123");
        log.setObjectId("object-456");
        log.setEventType("UPDATED");
        log.setWebhookUrl("https://example.com/webhook");
        log.setStatusCode(500);
        log.setSuccess(false);
        log.setAttemptNumber(3);
        log.setTimestamp(null);

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildDeliveryLogJson", WebhookDeliveryLog.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, log);

        assertEquals("delivery-123", json.get("deliveryId"));
        assertEquals(false, json.get("success"));
        assertEquals(3, json.get("attemptCount"));
        assertNull(json.get("deliveredAt"));
    }

    @Test
    public void testBuildDeliveryLogJsonWithNullResponseBody() throws Exception {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.setDeliveryId("delivery-123");
        log.setObjectId("object-456");
        log.setEventType("DELETED");
        log.setWebhookUrl("https://example.com/webhook");
        log.setStatusCode(204);
        log.setSuccess(true);
        log.setAttemptNumber(1);
        log.setResponseBody(null);

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildDeliveryLogJson", WebhookDeliveryLog.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, log);

        assertEquals("delivery-123", json.get("deliveryId"));
        assertEquals(true, json.get("success"));
        assertNull(json.get("responseBody"));
    }

    @Test
    public void testBuildWebhookConfigJsonWithAllFields() throws Exception {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED", "UPDATED", "DELETED"))
            .authType("bearer")
            .includeChildren(true)
            .maxDepth(5)
            .retryCount(3)
            .build();
        config.setSourceObjectId("folder-123");

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildWebhookConfigJson", WebhookConfig.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, config);

        assertEquals("webhook-1", json.get("id"));
        assertEquals(true, json.get("enabled"));
        assertEquals("https://example.com/webhook", json.get("url"));
        assertEquals("bearer", json.get("authType"));
        assertEquals(true, json.get("includeChildren"));
        assertEquals(5, json.get("maxDepth"));
        assertEquals(3, json.get("retryCount"));
        assertEquals("folder-123", json.get("sourceObjectId"));

        org.json.simple.JSONArray events = (org.json.simple.JSONArray) json.get("events");
        assertEquals(3, events.size());
        assertTrue(events.contains("CREATED"));
        assertTrue(events.contains("UPDATED"));
        assertTrue(events.contains("DELETED"));
    }

    @Test
    public void testBuildWebhookConfigJsonWithNullEvents() throws Exception {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-2")
            .enabled(false)
            .url("https://example.com/webhook")
            .events(null)
            .build();

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildWebhookConfigJson", WebhookConfig.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, config);

        assertEquals("webhook-2", json.get("id"));
        assertEquals(false, json.get("enabled"));

        org.json.simple.JSONArray events = (org.json.simple.JSONArray) json.get("events");
        assertNotNull(events);
        assertEquals(0, events.size());
    }

    @Test
    public void testBuildWebhookConfigJsonWithEmptyEvents() throws Exception {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-3")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(new ArrayList<>())
            .build();

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildWebhookConfigJson", WebhookConfig.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, config);

        org.json.simple.JSONArray events = (org.json.simple.JSONArray) json.get("events");
        assertNotNull(events);
        assertEquals(0, events.size());
    }

    // ---------------------------------------------------------------
    // mergeWebhookConfigFromJson tests
    // ---------------------------------------------------------------

    private void invokeMerge(WebhookConfig existing, org.json.simple.JSONObject body) throws Exception {
        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "mergeWebhookConfigFromJson", WebhookConfig.class, org.json.simple.JSONObject.class);
        method.setAccessible(true);
        method.invoke(resource, existing, body);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_AuthTypeBearerToNone_ClearsCredentials() throws Exception {
        // Setup: existing config with bearer auth
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-1")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("CREATED"))
            .authType("bearer")
            .build();
        existing.setAuthCredential("my-bearer-token");

        // Simulate UI sending authType=none with empty credentials
        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("authType", "none");
        body.put("authCredential", "");
        body.put("secret", "");

        invokeMerge(existing, body);

        assertEquals("none", existing.getAuthType());
        assertNull(existing.getAuthCredential());
        assertNull(existing.getSecret());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_AuthTypeBasicToNone_ClearsCredentials() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-2")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("UPDATED"))
            .authType("basic")
            .build();
        existing.setAuthCredential("dXNlcjpwYXNz");

        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("authType", "none");
        body.put("authCredential", "");

        invokeMerge(existing, body);

        assertEquals("none", existing.getAuthType());
        assertNull(existing.getAuthCredential());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_AuthTypeHmacToNone_ClearsSecret() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-3")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("DELETED"))
            .authType("hmac")
            .build();
        existing.setSecret("hmac-secret-key");

        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("authType", "none");
        body.put("secret", "");

        invokeMerge(existing, body);

        assertEquals("none", existing.getAuthType());
        assertNull(existing.getSecret());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_AuthCredentialUpdate_PreservesNewValue() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-4")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("CREATED"))
            .authType("bearer")
            .build();
        existing.setAuthCredential("old-token");

        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("authCredential", "new-token");

        invokeMerge(existing, body);

        assertEquals("new-token", existing.getAuthCredential());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_AbsentCredential_PreservesExisting() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-5")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("CREATED"))
            .authType("bearer")
            .build();
        existing.setAuthCredential("existing-token");
        existing.setSecret("existing-secret");

        // Body does NOT contain authCredential or secret keys
        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("url", "https://example.com/hook-v2");

        invokeMerge(existing, body);

        assertEquals("https://example.com/hook-v2", existing.getUrl());
        assertEquals("existing-token", existing.getAuthCredential());
        assertEquals("existing-secret", existing.getSecret());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_NullCredential_ClearsValue() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-6")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("CREATED"))
            .authType("basic")
            .build();
        existing.setAuthCredential("old-cred");

        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("authCredential", null);

        invokeMerge(existing, body);

        assertNull(existing.getAuthCredential());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMerge_PartialUpdate_OnlyUpdatesProvidedFields() throws Exception {
        WebhookConfig existing = new WebhookConfig.Builder()
            .id("wh-7")
            .enabled(true)
            .url("https://example.com/hook")
            .events(Arrays.asList("CREATED", "UPDATED"))
            .authType("bearer")
            .includeChildren(true)
            .maxDepth(10)
            .retryCount(3)
            .build();
        existing.setAuthCredential("bearer-token");

        // Only update enabled and retryCount
        org.json.simple.JSONObject body = new org.json.simple.JSONObject();
        body.put("enabled", false);
        body.put("retryCount", 5L);

        invokeMerge(existing, body);

        // Updated fields
        assertFalse(existing.isEnabled());
        assertEquals(5, (int) existing.getRetryCount());
        // Preserved fields
        assertEquals("https://example.com/hook", existing.getUrl());
        assertEquals(2, existing.getEvents().size());
        assertEquals("bearer", existing.getAuthType());
        assertEquals("bearer-token", existing.getAuthCredential());
        assertTrue(existing.isIncludeChildren());
        assertEquals(Integer.valueOf(10), existing.getMaxDepth());
    }

    @Test
    public void testBuildWebhookConfigJsonWithNullMaxDepth() throws Exception {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-4")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("SECURITY"))
            .maxDepth(null)
            .build();

        java.lang.reflect.Method method = WebhookResource.class.getDeclaredMethod(
            "buildWebhookConfigJson", WebhookConfig.class);
        method.setAccessible(true);
        org.json.simple.JSONObject json = (org.json.simple.JSONObject) method.invoke(resource, config);

        assertNull(json.get("maxDepth"));
    }

    // ---------------------------------------------------------------
    // getAllWebhookConfigs — DAO failure propagation tests
    // ---------------------------------------------------------------

    /**
     * Mangoクエリ失敗（findBySelector → RuntimeException）時に
     * getAllWebhookConfigs がstatus:failの応答を返すことを検証。
     * findBySelectorが例外を握りつぶしていた旧実装では空データ成功に見えていた。
     */
    @Test
    public void testGetAllWebhookConfigs_DaoFailure_ReturnsFailureResponse() throws Exception {
        // Setup: mock WebhookService that throws RuntimeException (simulating Mango query failure)
        WebhookService mockWs = mock(WebhookService.class);
        when(mockWs.getAllWebhookableObjects("bedroom"))
            .thenThrow(new RuntimeException("Mango query failed: Connection refused"));

        // Inject mock WebhookService via reflection
        java.lang.reflect.Field wsField = WebhookResource.class.getDeclaredField("webhookService");
        wsField.setAccessible(true);
        wsField.set(resource, mockWs);

        // Also inject mock ContentService (required to pass null check before getAllWebhookableObjects is called)
        jp.aegif.nemaki.businesslogic.ContentService mockCs = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        java.lang.reflect.Field csField = WebhookResource.class.getDeclaredField("contentService");
        csField.setAccessible(true);
        csField.set(resource, mockCs);

        // Setup: mock HttpServletRequest with admin CallContext
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        CallContext mockCallContext = mock(CallContext.class);
        when(mockCallContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(mockCallContext.getRepositoryId()).thenReturn("bedroom");
        when(mockRequest.getAttribute("CallContext")).thenReturn(mockCallContext);

        // Execute
        String responseJson = resource.getAllWebhookConfigs("bedroom", mockRequest);

        // Verify: response indicates failure
        org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
        org.json.simple.JSONObject response = (org.json.simple.JSONObject) parser.parse(responseJson);

        assertEquals("failure", response.get("status"));
        org.json.simple.JSONArray errors = (org.json.simple.JSONArray) response.get("error");
        assertNotNull("Failure response must contain 'error' array", errors);
        assertTrue("Error message should mention Mango query failure",
            errors.toString().contains("Mango query failed"));
    }

    /**
     * DAO正常（空結果）時にはstatus:okで空objectsを返すことを検証。
     * 障害と空データが正しく区別されることを確認。
     */
    @Test
    public void testGetAllWebhookConfigs_EmptyResult_ReturnsSuccessResponse() throws Exception {
        // Setup: mock WebhookService that returns empty list (no webhookable objects)
        WebhookService mockWs = mock(WebhookService.class);
        when(mockWs.getAllWebhookableObjects("bedroom")).thenReturn(new ArrayList<>());

        java.lang.reflect.Field wsField = WebhookResource.class.getDeclaredField("webhookService");
        wsField.setAccessible(true);
        wsField.set(resource, mockWs);

        // ContentService is also needed (but won't be called since no objects)
        // Inject a non-null ContentService to pass the null check
        jp.aegif.nemaki.businesslogic.ContentService mockCs = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        java.lang.reflect.Field csField = WebhookResource.class.getDeclaredField("contentService");
        csField.setAccessible(true);
        csField.set(resource, mockCs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        CallContext mockCallContext = mock(CallContext.class);
        when(mockCallContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(mockCallContext.getRepositoryId()).thenReturn("bedroom");
        when(mockRequest.getAttribute("CallContext")).thenReturn(mockCallContext);

        String responseJson = resource.getAllWebhookConfigs("bedroom", mockRequest);

        org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
        org.json.simple.JSONObject response = (org.json.simple.JSONObject) parser.parse(responseJson);

        assertEquals("success", response.get("status"));
        org.json.simple.JSONArray objects = (org.json.simple.JSONArray) response.get("objects");
        assertNotNull(objects);
        assertEquals(0, objects.size());
    }
}
