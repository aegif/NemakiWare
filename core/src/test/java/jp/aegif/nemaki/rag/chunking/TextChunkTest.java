package jp.aegif.nemaki.rag.chunking;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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

        assertEquals(text, chunk.getText(), "Text should match");
        assertEquals(index, chunk.getIndex(), "Index should match");
        assertEquals(startOffset, chunk.getStartOffset(), "Start offset should match");
        assertEquals(endOffset, chunk.getEndOffset(), "End offset should match");
        assertEquals(tokenCount, chunk.getTokenCount(), "Token count should match");
    }

    @Test
    public void testConstructorWithDifferentValues() {
        String text = "Another chunk with different values.";
        int index = 5;
        int startOffset = 1000;
        int endOffset = 1500;
        int tokenCount = 100;

        TextChunk chunk = new TextChunk(text, index, startOffset, endOffset, tokenCount);

        assertEquals(text, chunk.getText(), "Text should match");
        assertEquals(index, chunk.getIndex(), "Index should match");
        assertEquals(startOffset, chunk.getStartOffset(), "Start offset should match");
        assertEquals(endOffset, chunk.getEndOffset(), "End offset should match");
        assertEquals(tokenCount, chunk.getTokenCount(), "Token count should match");
    }

    @Test
    public void testConstructorWithEmptyText() {
        TextChunk chunk = new TextChunk("", 0, 0, 0, 0);

        assertEquals("", chunk.getText(), "Empty text should be preserved");
        assertEquals(0, chunk.getIndex(), "Index should be 0");
    }

    @Test
    public void testConstructorWithNullText() {
        TextChunk chunk = new TextChunk(null, 0, 0, 0, 0);

        assertNull(chunk.getText(), "Null text should be preserved");
    }

    // ========== generateChunkId Tests ==========

    @Test
    public void testGenerateChunkIdBasic() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("doc-123_chunk_0", chunkId, "Chunk ID should follow format");
    }

    @Test
    public void testGenerateChunkIdWithDifferentIndex() {
        TextChunk chunk = new TextChunk("text", 5, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("doc-123_chunk_5", chunkId, "Chunk ID should include correct index");
    }

    @Test
    public void testGenerateChunkIdWithLargeIndex() {
        TextChunk chunk = new TextChunk("text", 999, 0, 4, 1);

        String chunkId = chunk.generateChunkId("doc-123");

        assertEquals("doc-123_chunk_999", chunkId, "Chunk ID should handle large index");
    }

    @Test
    public void testGenerateChunkIdWithComplexDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("abc-def-123-456");

        assertEquals("abc-def-123-456_chunk_0", chunkId, "Chunk ID should handle complex document ID");
    }

    @Test
    public void testGenerateChunkIdWithEmptyDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId("");

        assertEquals("_chunk_0", chunkId, "Chunk ID should handle empty document ID");
    }

    @Test
    public void testGenerateChunkIdWithNullDocumentId() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String chunkId = chunk.generateChunkId(null);

        assertEquals("null_chunk_0", chunkId, "Chunk ID should handle null document ID");
    }

    @Test
    public void testGenerateChunkIdUniqueness() {
        TextChunk chunk1 = new TextChunk("text1", 0, 0, 5, 1);
        TextChunk chunk2 = new TextChunk("text2", 1, 5, 10, 1);
        TextChunk chunk3 = new TextChunk("text3", 2, 10, 15, 1);

        String id1 = chunk1.generateChunkId("doc-1");
        String id2 = chunk2.generateChunkId("doc-1");
        String id3 = chunk3.generateChunkId("doc-1");

        assertNotEquals(id1, id2, "Chunk IDs should be unique");
        assertNotEquals(id2, id3, "Chunk IDs should be unique");
        assertNotEquals(id1, id3, "Chunk IDs should be unique");
    }

    @Test
    public void testGenerateChunkIdDifferentDocuments() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 1);

        String id1 = chunk.generateChunkId("doc-1");
        String id2 = chunk.generateChunkId("doc-2");

        assertNotEquals(id1, id2, "Chunk IDs for different documents should be different");
    }

    // ========== toString Tests ==========

    @Test
    public void testToStringShortText() {
        TextChunk chunk = new TextChunk("Short text", 0, 0, 10, 3);

        String result = chunk.toString();

        assertNotNull(result, "toString should not return null");
        assertTrue(result.contains("index=0"), "toString should contain index");
        assertTrue(result.contains("tokens=3"), "toString should contain tokens");
        assertTrue(result.contains("offset=0-10"), "toString should contain offset");
        assertTrue(result.contains("Short text"), "toString should contain text");
    }

    @Test
    public void testToStringLongText() {
        String longText = "This is a very long text that exceeds fifty characters and should be truncated in toString output.";
        TextChunk chunk = new TextChunk(longText, 0, 0, longText.length(), 25);

        String result = chunk.toString();

        assertNotNull(result, "toString should not return null");
        assertTrue(result.contains("..."), "toString should truncate long text");
        // The text should be truncated to first 50 characters
        assertTrue(result.contains("This is a very long text"), "toString should contain beginning of text");
    }

    @Test
    public void testToStringExactly50Characters() {
        String text50 = "12345678901234567890123456789012345678901234567890";
        assertEquals(50, text50.length(), "Test text should be exactly 50 chars");
        
        TextChunk chunk = new TextChunk(text50, 0, 0, 50, 13);

        String result = chunk.toString();

        assertNotNull(result, "toString should not return null");
        // Text exactly 50 chars should not be truncated
    }

    // ========== Edge Cases ==========

    @Test
    public void testChunkWithZeroTokenCount() {
        TextChunk chunk = new TextChunk("text", 0, 0, 4, 0);

        assertEquals(0, chunk.getTokenCount(), "Zero token count should be preserved");
    }

    @Test
    public void testChunkWithNegativeIndex() {
        // While not typical, the class should handle negative values
        TextChunk chunk = new TextChunk("text", -1, 0, 4, 1);

        assertEquals(-1, chunk.getIndex(), "Negative index should be preserved");
        assertEquals("doc_chunk_-1", chunk.generateChunkId("doc"), "Chunk ID should handle negative index");
    }

    @Test
    public void testChunkWithLargeOffsets() {
        TextChunk chunk = new TextChunk("text", 0, Integer.MAX_VALUE - 100, Integer.MAX_VALUE, 1);

        assertEquals(Integer.MAX_VALUE - 100, chunk.getStartOffset(), "Large start offset should be preserved");
        assertEquals(Integer.MAX_VALUE, chunk.getEndOffset(), "Large end offset should be preserved");
    }

    @Test
    public void testChunkWithJapaneseText() {
        String japaneseText = "これは日本語のテキストです。";
        TextChunk chunk = new TextChunk(japaneseText, 0, 0, japaneseText.length(), 7);

        assertEquals(japaneseText, chunk.getText(), "Japanese text should be preserved");
        
        String result = chunk.toString();
        assertTrue(result.contains("日本語"), "toString should contain Japanese text");
    }

    @Test
    public void testChunkWithMixedText() {
        String mixedText = "English and 日本語 mixed";
        TextChunk chunk = new TextChunk(mixedText, 0, 0, mixedText.length(), 5);

        assertEquals(mixedText, chunk.getText(), "Mixed text should be preserved");
    }

    @Test
    public void testChunkWithSpecialCharacters() {
        String specialText = "Text with special chars: @#$%^&*()[]{}|\\";
        TextChunk chunk = new TextChunk(specialText, 0, 0, specialText.length(), 10);

        assertEquals(specialText, chunk.getText(), "Special characters should be preserved");
    }

    @Test
    public void testChunkWithNewlines() {
        String textWithNewlines = "Line 1\nLine 2\nLine 3";
        TextChunk chunk = new TextChunk(textWithNewlines, 0, 0, textWithNewlines.length(), 6);

        assertEquals(textWithNewlines, chunk.getText(), "Newlines should be preserved");
    }

    @Test
    public void testChunkWithTabs() {
        String textWithTabs = "Column1\tColumn2\tColumn3";
        TextChunk chunk = new TextChunk(textWithTabs, 0, 0, textWithTabs.length(), 3);

        assertEquals(textWithTabs, chunk.getText(), "Tabs should be preserved");
    }
}
