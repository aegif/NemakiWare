package jp.aegif.nemaki.rag.config;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

import java.lang.reflect.Field;

/**
 * Unit tests for RAGConfig.
 * 
 * Tests configuration functionality including:
 * - MIME type support checking
 * - Property fields parsing
 * - Default values
 */
public class RAGConfigTest {

    private RAGConfig ragConfig;

    @Before
    public void setUp() throws Exception {
        ragConfig = new RAGConfig();
        
        // Set default values using reflection since @Value annotations won't work in unit tests
        setField(ragConfig, "enabled", false);
        setField(ragConfig, "teiUrl", "http://tei:80");
        setField(ragConfig, "teiConnectTimeout", 5000);
        setField(ragConfig, "teiReadTimeout", 30000);
        setField(ragConfig, "teiBatchSize", 32);
        setField(ragConfig, "teiMaxRetries", 3);
        setField(ragConfig, "teiRetryDelay", 1000);
        setField(ragConfig, "chunkingMaxTokens", 512);
        setField(ragConfig, "chunkingOverlapTokens", 50);
        setField(ragConfig, "chunkingMinTokens", 50);
        setField(ragConfig, "searchTopK", 10);
        setField(ragConfig, "searchSimilarityThreshold", 0.7f);
        setField(ragConfig, "propertyBoost", 0.3f);
        setField(ragConfig, "contentBoost", 0.7f);
        setField(ragConfig, "propertySearchEnabled", true);
        setField(ragConfig, "propertyFields", "cmis:name,cmis:description");
        setField(ragConfig, "includeCustomProperties", false);
        setField(ragConfig, "indexingBatchSize", 100);
        setField(ragConfig, "indexingAsync", true);
        setField(ragConfig, "solrCommitWithinMs", 10000);
        setField(ragConfig, "supportedMimeTypes",
                "text/plain,text/html,text/xml,application/pdf,application/msword," +
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = RAGConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ========== isMimeTypeSupported Tests ==========

    @Test
    public void testIsMimeTypeSupportedTextPlain() {
        assertTrue("text/plain should be supported", 
                ragConfig.isMimeTypeSupported("text/plain"));
    }

    @Test
    public void testIsMimeTypeSupportedTextHtml() {
        assertTrue("text/html should be supported", 
                ragConfig.isMimeTypeSupported("text/html"));
    }

    @Test
    public void testIsMimeTypeSupportedTextXml() {
        assertTrue("text/xml should be supported", 
                ragConfig.isMimeTypeSupported("text/xml"));
    }

    @Test
    public void testIsMimeTypeSupportedPdf() {
        assertTrue("application/pdf should be supported", 
                ragConfig.isMimeTypeSupported("application/pdf"));
    }

    @Test
    public void testIsMimeTypeSupportedMsWord() {
        assertTrue("application/msword should be supported", 
                ragConfig.isMimeTypeSupported("application/msword"));
    }

    @Test
    public void testIsMimeTypeSupportedDocx() {
        assertTrue("DOCX should be supported", 
                ragConfig.isMimeTypeSupported(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    public void testIsMimeTypeSupportedUnsupportedType() {
        assertFalse("image/png should not be supported", 
                ragConfig.isMimeTypeSupported("image/png"));
    }

    @Test
    public void testIsMimeTypeSupportedUnsupportedVideo() {
        assertFalse("video/mp4 should not be supported", 
                ragConfig.isMimeTypeSupported("video/mp4"));
    }

    @Test
    public void testIsMimeTypeSupportedNull() {
        assertFalse("null should not be supported", 
                ragConfig.isMimeTypeSupported(null));
    }

    @Test
    public void testIsMimeTypeSupportedEmpty() {
        assertFalse("empty string should not be supported", 
                ragConfig.isMimeTypeSupported(""));
    }

    @Test
    public void testIsMimeTypeSupportedCaseInsensitive() {
        assertTrue("MIME type check should be case-insensitive", 
                ragConfig.isMimeTypeSupported("TEXT/PLAIN"));
    }

    @Test
    public void testIsMimeTypeSupportedMixedCase() {
        assertTrue("MIME type check should be case-insensitive", 
                ragConfig.isMimeTypeSupported("Text/Plain"));
    }

    @Test
    public void testIsMimeTypeSupportedWithWhitespace() {
        assertTrue("MIME type with whitespace should be trimmed", 
                ragConfig.isMimeTypeSupported(" text/plain "));
    }

    @Test
    public void testIsMimeTypeSupportedWithNullSupportedTypes() throws Exception {
        setField(ragConfig, "supportedMimeTypes", null);
        
        assertFalse("Should return false when supportedMimeTypes is null", 
                ragConfig.isMimeTypeSupported("text/plain"));
    }

    // ========== getPropertyFieldsArray Tests ==========

    @Test
    public void testGetPropertyFieldsArrayDefault() {
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Should have 2 default fields", 2, fields.length);
        assertEquals("First field should be cmis:name", "cmis:name", fields[0]);
        assertEquals("Second field should be cmis:description", "cmis:description", fields[1]);
    }

    @Test
    public void testGetPropertyFieldsArraySingleField() throws Exception {
        setField(ragConfig, "propertyFields", "cmis:name");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Should have 1 field", 1, fields.length);
        assertEquals("Field should be cmis:name", "cmis:name", fields[0]);
    }

    @Test
    public void testGetPropertyFieldsArrayMultipleFields() throws Exception {
        setField(ragConfig, "propertyFields", "cmis:name,cmis:description,nemaki:keywords");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Should have 3 fields", 3, fields.length);
        assertEquals("First field should be cmis:name", "cmis:name", fields[0]);
        assertEquals("Second field should be cmis:description", "cmis:description", fields[1]);
        assertEquals("Third field should be nemaki:keywords", "nemaki:keywords", fields[2]);
    }

    @Test
    public void testGetPropertyFieldsArrayEmpty() throws Exception {
        setField(ragConfig, "propertyFields", "");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Empty string should return empty array", 0, fields.length);
    }

    @Test
    public void testGetPropertyFieldsArrayNull() throws Exception {
        setField(ragConfig, "propertyFields", null);
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Null should return empty array", 0, fields.length);
    }

    @Test
    public void testGetPropertyFieldsArrayWhitespaceOnly() throws Exception {
        setField(ragConfig, "propertyFields", "   ");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull("Fields array should not be null", fields);
        assertEquals("Whitespace-only should return empty array", 0, fields.length);
    }

    // ========== Getter Tests ==========

    @Test
    public void testIsEnabled() {
        assertFalse("Default enabled should be false", ragConfig.isEnabled());
    }

    @Test
    public void testIsEnabledTrue() throws Exception {
        setField(ragConfig, "enabled", true);
        assertTrue("Enabled should be true", ragConfig.isEnabled());
    }

    @Test
    public void testGetTeiUrl() {
        assertEquals("http://tei:80", ragConfig.getTeiUrl());
    }

    @Test
    public void testGetTeiConnectTimeout() {
        assertEquals(5000, ragConfig.getTeiConnectTimeout());
    }

    @Test
    public void testGetTeiReadTimeout() {
        assertEquals(30000, ragConfig.getTeiReadTimeout());
    }

    @Test
    public void testGetTeiBatchSize() {
        assertEquals(32, ragConfig.getTeiBatchSize());
    }

    @Test
    public void testGetTeiMaxRetries() {
        assertEquals(3, ragConfig.getTeiMaxRetries());
    }

    @Test
    public void testGetTeiRetryDelay() {
        assertEquals(1000, ragConfig.getTeiRetryDelay());
    }

    @Test
    public void testGetChunkingMaxTokens() {
        assertEquals(512, ragConfig.getChunkingMaxTokens());
    }

    @Test
    public void testGetChunkingOverlapTokens() {
        assertEquals(50, ragConfig.getChunkingOverlapTokens());
    }

    @Test
    public void testGetChunkingMinTokens() {
        assertEquals(50, ragConfig.getChunkingMinTokens());
    }

    @Test
    public void testGetSearchTopK() {
        assertEquals(10, ragConfig.getSearchTopK());
    }

    @Test
    public void testGetSearchSimilarityThreshold() {
        assertEquals(0.7f, ragConfig.getSearchSimilarityThreshold(), 0.001f);
    }

    @Test
    public void testGetPropertyBoost() {
        assertEquals(0.3f, ragConfig.getPropertyBoost(), 0.001f);
    }

    @Test
    public void testGetContentBoost() {
        assertEquals(0.7f, ragConfig.getContentBoost(), 0.001f);
    }

    @Test
    public void testIsPropertySearchEnabled() {
        assertTrue("Property search should be enabled by default", 
                ragConfig.isPropertySearchEnabled());
    }

    @Test
    public void testGetPropertyFields() {
        assertEquals("cmis:name,cmis:description", ragConfig.getPropertyFields());
    }

    @Test
    public void testIsIncludeCustomProperties() {
        assertFalse("Include custom properties should be false by default", 
                ragConfig.isIncludeCustomProperties());
    }

    @Test
    public void testGetIndexingBatchSize() {
        assertEquals(100, ragConfig.getIndexingBatchSize());
    }

    @Test
    public void testIsIndexingAsync() {
        assertTrue("Indexing async should be true by default", 
                ragConfig.isIndexingAsync());
    }

    @Test
    public void testGetSolrCommitWithinMs() {
        assertEquals(10000, ragConfig.getSolrCommitWithinMs());
    }

    @Test
    public void testGetSolrCommitWithinMsCustomValue() throws Exception {
        setField(ragConfig, "solrCommitWithinMs", 5000);
        assertEquals(5000, ragConfig.getSolrCommitWithinMs());
    }

    @Test
    public void testGetSolrCommitWithinMsZero() throws Exception {
        setField(ragConfig, "solrCommitWithinMs", 0);
        assertEquals("Zero should be allowed for immediate commit", 
                0, ragConfig.getSolrCommitWithinMs());
    }

    @Test
    public void testGetSolrCommitWithinMsNegative() throws Exception {
        setField(ragConfig, "solrCommitWithinMs", -1);
        assertEquals("Negative should be allowed for immediate commit", 
                -1, ragConfig.getSolrCommitWithinMs());
    }

    @Test
    public void testGetSupportedMimeTypes() {
        String mimeTypes = ragConfig.getSupportedMimeTypes();
        assertNotNull("Supported MIME types should not be null", mimeTypes);
        assertTrue("Should contain text/plain", mimeTypes.contains("text/plain"));
        assertTrue("Should contain application/pdf", mimeTypes.contains("application/pdf"));
    }

    // ========== Edge Cases ==========

    @Test
    public void testIsMimeTypeSupportedWithExtraCommas() throws Exception {
        setField(ragConfig, "supportedMimeTypes", "text/plain,,text/html,");
        
        assertTrue("text/plain should still be supported", 
                ragConfig.isMimeTypeSupported("text/plain"));
        assertTrue("text/html should still be supported", 
                ragConfig.isMimeTypeSupported("text/html"));
    }

    @Test
    public void testIsMimeTypeSupportedPartialMatch() {
        // Should not match partial MIME types
        assertFalse("Partial match 'text' should not be supported", 
                ragConfig.isMimeTypeSupported("text"));
        assertFalse("Partial match 'plain' should not be supported", 
                ragConfig.isMimeTypeSupported("plain"));
    }
}
