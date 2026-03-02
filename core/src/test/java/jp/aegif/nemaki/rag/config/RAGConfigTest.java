package jp.aegif.nemaki.rag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeEach
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
        assertTrue(ragConfig.isMimeTypeSupported("text/plain"), "text/plain should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedTextHtml() {
        assertTrue(ragConfig.isMimeTypeSupported("text/html"), "text/html should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedTextXml() {
        assertTrue(ragConfig.isMimeTypeSupported("text/xml"), "text/xml should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedPdf() {
        assertTrue(ragConfig.isMimeTypeSupported("application/pdf"), "application/pdf should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedMsWord() {
        assertTrue(ragConfig.isMimeTypeSupported("application/msword"), "application/msword should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedDocx() {
        assertTrue(ragConfig.isMimeTypeSupported(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), "DOCX should be supported");
    }

    @Test
    public void testIsMimeTypeSupportedUnsupportedType() {
        assertFalse(ragConfig.isMimeTypeSupported("image/png"), "image/png should not be supported");
    }

    @Test
    public void testIsMimeTypeSupportedUnsupportedVideo() {
        assertFalse(ragConfig.isMimeTypeSupported("video/mp4"), "video/mp4 should not be supported");
    }

    @Test
    public void testIsMimeTypeSupportedNull() {
        assertFalse(ragConfig.isMimeTypeSupported(null), "null should not be supported");
    }

    @Test
    public void testIsMimeTypeSupportedEmpty() {
        assertFalse(ragConfig.isMimeTypeSupported(""), "empty string should not be supported");
    }

    @Test
    public void testIsMimeTypeSupportedCaseInsensitive() {
        assertTrue(ragConfig.isMimeTypeSupported("TEXT/PLAIN"), "MIME type check should be case-insensitive");
    }

    @Test
    public void testIsMimeTypeSupportedMixedCase() {
        assertTrue(ragConfig.isMimeTypeSupported("Text/Plain"), "MIME type check should be case-insensitive");
    }

    @Test
    public void testIsMimeTypeSupportedWithWhitespace() {
        assertTrue(ragConfig.isMimeTypeSupported(" text/plain "), "MIME type with whitespace should be trimmed");
    }

    @Test
    public void testIsMimeTypeSupportedWithNullSupportedTypes() throws Exception {
        setField(ragConfig, "supportedMimeTypes", null);
        
        assertFalse(ragConfig.isMimeTypeSupported("text/plain"), "Should return false when supportedMimeTypes is null");
    }

    // ========== getPropertyFieldsArray Tests ==========

    @Test
    public void testGetPropertyFieldsArrayDefault() {
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(2, fields.length, "Should have 2 default fields");
        assertEquals("cmis:name", fields[0], "First field should be cmis:name");
        assertEquals("cmis:description", fields[1], "Second field should be cmis:description");
    }

    @Test
    public void testGetPropertyFieldsArraySingleField() throws Exception {
        setField(ragConfig, "propertyFields", "cmis:name");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(1, fields.length, "Should have 1 field");
        assertEquals("cmis:name", fields[0], "Field should be cmis:name");
    }

    @Test
    public void testGetPropertyFieldsArrayMultipleFields() throws Exception {
        setField(ragConfig, "propertyFields", "cmis:name,cmis:description,nemaki:keywords");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(3, fields.length, "Should have 3 fields");
        assertEquals("cmis:name", fields[0], "First field should be cmis:name");
        assertEquals("cmis:description", fields[1], "Second field should be cmis:description");
        assertEquals("nemaki:keywords", fields[2], "Third field should be nemaki:keywords");
    }

    @Test
    public void testGetPropertyFieldsArrayEmpty() throws Exception {
        setField(ragConfig, "propertyFields", "");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(0, fields.length, "Empty string should return empty array");
    }

    @Test
    public void testGetPropertyFieldsArrayNull() throws Exception {
        setField(ragConfig, "propertyFields", null);
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(0, fields.length, "Null should return empty array");
    }

    @Test
    public void testGetPropertyFieldsArrayWhitespaceOnly() throws Exception {
        setField(ragConfig, "propertyFields", "   ");
        
        String[] fields = ragConfig.getPropertyFieldsArray();
        
        assertNotNull(fields, "Fields array should not be null");
        assertEquals(0, fields.length, "Whitespace-only should return empty array");
    }

    // ========== Getter Tests ==========

    @Test
    public void testIsEnabled() {
        assertFalse(ragConfig.isEnabled(), "Default enabled should be false");
    }

    @Test
    public void testIsEnabledTrue() throws Exception {
        setField(ragConfig, "enabled", true);
        assertTrue(ragConfig.isEnabled(), "Enabled should be true");
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
        assertTrue(ragConfig.isPropertySearchEnabled(), "Property search should be enabled by default");
    }

    @Test
    public void testGetPropertyFields() {
        assertEquals("cmis:name,cmis:description", ragConfig.getPropertyFields());
    }

    @Test
    public void testIsIncludeCustomProperties() {
        assertFalse(ragConfig.isIncludeCustomProperties(), "Include custom properties should be false by default");
    }

    @Test
    public void testGetIndexingBatchSize() {
        assertEquals(100, ragConfig.getIndexingBatchSize());
    }

    @Test
    public void testIsIndexingAsync() {
        assertTrue(ragConfig.isIndexingAsync(), "Indexing async should be true by default");
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
        assertEquals(0, ragConfig.getSolrCommitWithinMs(), "Zero should be allowed for immediate commit");
    }

    @Test
    public void testGetSolrCommitWithinMsNegative() throws Exception {
        setField(ragConfig, "solrCommitWithinMs", -1);
        assertEquals(-1, ragConfig.getSolrCommitWithinMs(), "Negative should be allowed for immediate commit");
    }

    @Test
    public void testGetSupportedMimeTypes() {
        String mimeTypes = ragConfig.getSupportedMimeTypes();
        assertNotNull(mimeTypes, "Supported MIME types should not be null");
        assertTrue(mimeTypes.contains("text/plain"), "Should contain text/plain");
        assertTrue(mimeTypes.contains("application/pdf"), "Should contain application/pdf");
    }

    // ========== Edge Cases ==========

    @Test
    public void testIsMimeTypeSupportedWithExtraCommas() throws Exception {
        setField(ragConfig, "supportedMimeTypes", "text/plain,,text/html,");
        
        assertTrue(ragConfig.isMimeTypeSupported("text/plain"), "text/plain should still be supported");
        assertTrue(ragConfig.isMimeTypeSupported("text/html"), "text/html should still be supported");
    }

    @Test
    public void testIsMimeTypeSupportedPartialMatch() {
        // Should not match partial MIME types
        assertFalse(ragConfig.isMimeTypeSupported("text"), "Partial match 'text' should not be supported");
        assertFalse(ragConfig.isMimeTypeSupported("plain"), "Partial match 'plain' should not be supported");
    }
}
