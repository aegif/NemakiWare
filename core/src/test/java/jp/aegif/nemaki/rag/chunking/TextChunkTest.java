package jp.aegif.nemaki.rag.chunking;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for TextChunk.
 * 
 * Tests the TextChunk data class including:
 * - Constructor and getters
 * - generateChunkId method
 * - toString method
 */
public class TextChunkTest {

    // ========== Constructor and Getter Tests ==========

    @Test
    public void testConstructorAndGetters() {
        String text = "This is test chunk text.";
        int index = 0;
        int startOffset = 0;
        int endOffset = 24;
        int tokenCount = 6;

        TextChunk chunk = new TextChunk(text, index, startOffset, endOffset, tokenCount);

        assertEquals("Text should match", text, chunk.getText());
        assertEquals("Index should match", index, chunk.getIndex());
        assertEquals("Start offset should match", startOffset, chunk.getStartOffset());
        assertEquals("End offset should match", endOffset, chunk.getEndOffset());
        assertEquals("Token count should match", tokenCount, chunk.getTokenCount());
    }

    @Test
    public void testConstructorWithDifferentValues() {
        String text = "Another chunk with different values.";
        int index = 5;
        int startOffset = 1000;
        int endOffset = 1500;
        int tokenCount = 100;

        TextChunk chunk = new TextChunk(text, index, startOffset, endOffset, tokenCount);

        assertEquals("Text should match", text, chunk.getText());
        assertEquals("Index should match", index, chunk.getIndex());
        assertEquals("Start offset should match", startOffset, chunk.getStartOffset());
        assertEquals("End offset should match", endOffset, chunk.getEndOffset());
        assertEquals("Token count should match", tokenCount, chunk.getTokenCount());
    }

    @Test
    public void testConstructorWithEmptyText() {
        TextChunk chunk = new TextChunk("", 0, 0, 0, 0);

        assertEquals("Empty text should be preserved", "", chunk.getText());
        assertEquals("Index should be 0", 0, chunk.getIndex());
    }

    @Test
    public void testConstructorWithNullText() {
        TextChunk chunk = new TextChunk(null, 0, 0, 0, 0);

        assertNull("Null text should be preserved", chunk.getText());
    }

    // ========== generateChunkId Tests ==========

    @Test
    public void testGenerateChunkIdBasic() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("Chunk ID should follow format", "doc-123_chunk_0", chunkId);
    }

    @Test
    public void testGenerateChunkIdWithDifferentIndex() {
        TextChunk chunk = new TextChunk("text", 5, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("Chunk ID should include correct index", "doc-123_chunk_5", chunkId);
    }

    @Test
    public void testGenerateChunkIdWithLargeIndex() {
        TextChunk chunk = new TextChunk("text", 999, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("Chunk ID should handle large index", "doc-123_chunk_999", chunkId);
    }

    @Test
    public void testGenerateChunkIdWithComplexDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("abc-def-123-456");

        assertEquals("Chunk ID should handle complex document ID", 
                "abc-def-123-456_chunk_0", chunkId);
    }

    @Test
    public void testGenerateChunkIdWithEmptyDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("");

        assertEquals("Chunk ID should handle empty document ID", "_chunk_0", chunkId);
    }

    @Test
    public void testGenerateChunkIdWithNullDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId(null);

        assertEquals("Chunk ID should handle null document ID", "null_chunk_0", chunkId);
    }

    @Test
    public void testGenerateChunkIdUniqueness() {
        TextChunk chunk1 = new TextChunk("text1", 0, 0, 5, 1);
        TextChunk chunk2 = new TextChunk("text2", 1, 5, 10, 1);
        TextChunk chunk3 = new TextChunk("text3", 2, 10, 15, 1);

        String id1 = chunk1.generateChunkId("doc-1");
        String id2 = chunk2.generateChunkId("doc-1");
        String id3 = chunk3.generateChunkId("doc-1");

        assertNotEquals("Chunk IDs should be unique", id1, id2);
        assertNotEquals("Chunk IDs should be unique", id2, id3);
        assertNotEquals("Chunk IDs should be unique", id1, id3);
    }

    @Test
    public void testGenerateChunkIdDifferentDocuments() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String id1 = chunk.generateChunkId("doc-1");
        String id2 = chunk.generateChunkId("doc-2");

        assertNotEquals("Chunk IDs for different documents should be different", id1, id2);
    }

    // ========== toString Tests ==========

    @Test
    public void testToStringShortText() {
        TextChunk chunk = new TextChunk("Short text", 0, 0, 10, 3);

        String result = chunk.toString();

        assertNotNull("toString should not return null", result);
        assertTrue("toString should contain index", result.contains("index=0"));
        assertTrue("toString should contain tokens", result.contains("tokens=3"));
        assertTrue("toString should contain offset", result.contains("offset=0-10"));
        assertTrue("toString should contain text", result.contains("Short text"));
    }

    @Test
    public void testToStringLongText() {
        String longText = "This is a very long text that exceeds fifty characters and should be truncated in toString output.";
        TextChunk chunk = new TextChunk(longText, 0, 0, longText.length(), 25);

        String result = chunk.toString();

        assertNotNull("toString should not return null", result);
        assertTrue("toString should truncate long text", result.contains("..."));
        // The text should be truncated to first 50 characters
        assertTrue("toString should contain beginning of text", 
                result.contains("This is a very long text"));
    }

    @Test
    public void testToStringExactly50Characters() {
        String text50 = "12345678901234567890123456789012345678901234567890";
        assertEquals("Test text should be exactly 50 chars", 50, text50.length());
        
        TextChunk chunk = new TextChunk(text50, 0, 0, 50, 13);

        String result = chunk.toString();

        assertNotNull("toString should not return null", result);
        // Text exactly 50 chars should not be truncated
    }

    // ========== Edge Cases ==========

    @Test
    public void testChunkWithZeroTokenCount() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 0);

        assertEquals("Zero token count should be preserved", 0, chunk.getTokenCount());
    }

    @Test
    public void testChunkWithNegativeIndex() {
        // While not typical, the class should handle negative values
        TextChunk chunk = new TextChunk("text", -1, 0, 4, 1);

        assertEquals("Negative index should be preserved", -1, chunk.getIndex());
        assertEquals("Chunk ID should handle negative index", "doc_chunk_-1", 
                chunk.generateChunkId("doc"));
    }

    @Test
    public void testChunkWithLargeOffsets() {
        TextChunk chunk = new TextChunk("text", 0, Integer.MAX_VALUE - 100, Integer.MAX_VALUE, 1);

        assertEquals("Large start offset should be preserved", 
                Integer.MAX_VALUE - 100, chunk.getStartOffset());
        assertEquals("Large end offset should be preserved", 
                Integer.MAX_VALUE, chunk.getEndOffset());
    }

    @Test
    public void testChunkWithJapaneseText() {
        String japaneseText = "これは日本語のテキストです。";
        TextChunk chunk = new TextChunk(japaneseText, 0, 0, japaneseText.length(), 7);

        assertEquals("Japanese text should be preserved", japaneseText, chunk.getText());
        
        String result = chunk.toString();
        assertTrue("toString should contain Japanese text", result.contains("日本語"));
    }

    @Test
    public void testChunkWithMixedText() {
        String mixedText = "English and 日本語 mixed";
        TextChunk chunk = new TextChunk(mixedText, 0, 0, mixedText.length(), 5);

        assertEquals("Mixed text should be preserved", mixedText, chunk.getText());
    }

    @Test
    public void testChunkWithSpecialCharacters() {
        String specialText = "Text with special chars: @#$%^&*()[]{}|\\";
        TextChunk chunk = new TextChunk(specialText, 0, 0, specialText.length(), 10);

        assertEquals("Special characters should be preserved", specialText, chunk.getText());
    }

    @Test
    public void testChunkWithNewlines() {
        String textWithNewlines = "Line 1\nLine 2\nLine 3";
        TextChunk chunk = new TextChunk(textWithNewlines, 0, 0, textWithNewlines.length(), 6);

        assertEquals("Newlines should be preserved", textWithNewlines, chunk.getText());
    }

    @Test
    public void testChunkWithTabs() {
        String textWithTabs = "Column1\tColumn2\tColumn3";
        TextChunk chunk = new TextChunk(textWithTabs, 0, 0, textWithTabs.length(), 3);

        assertEquals("Tabs should be preserved", textWithTabs, chunk.getText());
    }
}
