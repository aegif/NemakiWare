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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

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
}
