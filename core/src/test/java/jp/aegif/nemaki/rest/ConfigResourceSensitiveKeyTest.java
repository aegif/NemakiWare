package jp.aegif.nemaki.rest;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests ConfigResource's sensitive key detection and category determination.
 * These are pure logic tests using reflection — no Spring context needed.
 */
public class ConfigResourceSensitiveKeyTest {

    private boolean isSensitiveKey(String key) throws Exception {
        ConfigResource resource = new ConfigResource();
        Method method = ConfigResource.class.getDeclaredMethod("isSensitiveKey", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(resource, key);
    }

    private String determineCategory(String key) throws Exception {
        ConfigResource resource = new ConfigResource();
        Method method = ConfigResource.class.getDeclaredMethod("determineCategory", String.class);
        method.setAccessible(true);
        return (String) method.invoke(resource, key);
    }

    // ===== Sensitive key detection =====

    @Test
    public void testSensitive_passwordKeys() throws Exception {
        assertTrue("db.password should be sensitive", isSensitiveKey("db.password"));
        assertTrue("couchdb.password should be sensitive", isSensitiveKey("couchdb.password"));
        assertTrue("admin.password should be sensitive", isSensitiveKey("admin.password"));
    }

    @Test
    public void testSensitive_secretKeys() throws Exception {
        assertTrue("oidc.client.secret should be sensitive", isSensitiveKey("oidc.client.secret"));
        assertTrue("app.secret should be sensitive", isSensitiveKey("app.secret"));
    }

    @Test
    public void testSensitive_accessKeyVariants() throws Exception {
        assertTrue("s3.access.key should be sensitive", isSensitiveKey("s3.access.key"));
        assertTrue("aws.accessKey should be sensitive", isSensitiveKey("aws.accessKey"));
        assertTrue("longterm.s3.secretKey should be sensitive", isSensitiveKey("longterm.s3.secretKey"));
    }

    @Test
    public void testSensitive_clientSecretAndServiceAccountKey() throws Exception {
        assertTrue("oidc.clientSecret should be sensitive", isSensitiveKey("oidc.clientSecret"));
        assertTrue("google.serviceAccountKey should be sensitive", isSensitiveKey("google.serviceAccountKey"));
    }

    @Test
    public void testNonSensitive_normalKeys() throws Exception {
        assertFalse("db.host should not be sensitive", isSensitiveKey("db.host"));
        assertFalse("solr.url should not be sensitive", isSensitiveKey("solr.url"));
        assertFalse("cmis.server.port should not be sensitive", isSensitiveKey("cmis.server.port"));
        assertFalse("rag.embedding.provider should not be sensitive", isSensitiveKey("rag.embedding.provider"));
        assertFalse("retention.cold.move.after.days should not be sensitive",
                isSensitiveKey("retention.cold.move.after.days"));
    }

    // ===== Category determination =====

    @Test
    public void testCategory_dbKeys() throws Exception {
        assertEquals("DB", determineCategory("db.host"));
        assertEquals("DB", determineCategory("db.password"));
        assertEquals("DB", determineCategory("db.port"));
    }

    @Test
    public void testCategory_ragKeys() throws Exception {
        assertEquals("RAG", determineCategory("rag.embedding.provider"));
        assertEquals("RAG", determineCategory("rag.bedrock.region"));
    }

    @Test
    public void testCategory_archiveKeys() throws Exception {
        assertEquals("Archive", determineCategory("longterm.storage.type"));
        assertEquals("Archive", determineCategory("longterm.s3.bucket"));
        assertEquals("Archive", determineCategory("retention.cold.move.after.days"));
        assertEquals("Archive", determineCategory("retention.cold.keep.local.copy"));
    }

    @Test
    public void testCategory_ssoKeys() throws Exception {
        assertEquals("SSO", determineCategory("oidc.provider"));
        assertEquals("SSO", determineCategory("saml.entity.id"));
        assertEquals("SSO", determineCategory("sso.enabled"));
    }

    @Test
    public void testCategory_solrKeys() throws Exception {
        assertEquals("Solr", determineCategory("solr.url"));
        assertEquals("Solr", determineCategory("solr.core.name"));
    }

    @Test
    public void testCategory_cmisKeys() throws Exception {
        assertEquals("CMIS", determineCategory("cmis.server.port"));
        assertEquals("CMIS", determineCategory("cmis.server.default.repository"));
    }

    @Test
    public void testCategory_unknownKeys() throws Exception {
        assertEquals("General", determineCategory("some.unknown.key"));
        assertEquals("General", determineCategory("custom.setting"));
    }

    // ===== Edge cases =====

    @Test
    public void testSensitive_caseInsensitive() throws Exception {
        // The method lowercases and strips dots/underscores before matching
        assertTrue("DB.PASSWORD should be sensitive (case insensitive)",
                isSensitiveKey("DB.PASSWORD"));
        assertTrue("mixed.Password.key should be sensitive",
                isSensitiveKey("mixed.Password.key"));
    }

    @Test
    public void testSensitive_dotAndUnderscoreStripped() throws Exception {
        // "access_key" has underscore → stripped → "accesskey" → matches
        assertTrue("s3.access_key should be sensitive", isSensitiveKey("s3.access_key"));
    }
}
