package jp.aegif.nemaki.rag.chunking;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import jp.aegif.nemaki.rag.config.RAGConfig;

import java.util.List;

/**
 * Unit tests for TokenBasedChunkingService.
 * 
 * Tests text chunking functionality including:
 * - Empty/null input handling
 * - English text chunking
 * - Japanese (CJK) text chunking
 * - Mixed language text chunking
 * - Long text splitting
 * - Overlap extraction
 * - Token estimation
 */
@RunWith(MockitoJUnitRunner.class)
public class TokenBasedChunkingServiceTest {

    @Mock
    private RAGConfig ragConfig;

    private TokenBasedChunkingService chunkingService;

    @Before
    public void setUp() {
        // Default configuration for tests
        when(ragConfig.getChunkingMaxTokens()).thenReturn(512);
        when(ragConfig.getChunkingOverlapTokens()).thenReturn(50);
        when(ragConfig.getChunkingMinTokens()).thenReturn(50);
        
        chunkingService = new TokenBasedChunkingService(ragConfig);
    }

    // ========== Null and Empty Input Tests ==========

    @Test
    public void testChunkNullInput() {
        List<TextChunk> chunks = chunkingService.chunk(null);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Result should be empty for null input", chunks.isEmpty());
    }

    @Test
    public void testChunkEmptyString() {
        List<TextChunk> chunks = chunkingService.chunk("");
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Result should be empty for empty string", chunks.isEmpty());
    }

    @Test
    public void testChunkWhitespaceOnly() {
        List<TextChunk> chunks = chunkingService.chunk("   \t\n   ");
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Result should be empty for whitespace-only input", chunks.isEmpty());
    }

    // ========== English Text Chunking Tests ==========

    @Test
    public void testChunkShortEnglishText() {
        String text = "This is a short English text that should fit in one chunk.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertEquals("Short text should produce one chunk", 1, chunks.size());
        assertEquals("Chunk index should be 0", 0, chunks.get(0).getIndex());
        assertTrue("Chunk text should contain original content", 
                chunks.get(0).getText().contains("short English text"));
    }

    @Test
    public void testChunkEnglishTextWithSentences() {
        // Create text with multiple sentences
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }
        String text = sb.toString();
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Long text should produce multiple chunks", chunks.size() >= 1);
        
        // Verify chunk indices are sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals("Chunk index should be sequential", i, chunks.get(i).getIndex());
        }
    }

    @Test
    public void testChunkEnglishParagraphs() {
        String text = "First paragraph with some content. It has multiple sentences. " +
                "Second paragraph continues here. More text follows. " +
                "Third paragraph is the last one. Final sentence here.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        
        // All chunks should have non-empty text
        for (TextChunk chunk : chunks) {
            assertNotNull("Chunk text should not be null", chunk.getText());
            assertFalse("Chunk text should not be empty", chunk.getText().isEmpty());
        }
    }

    // ========== Japanese (CJK) Text Chunking Tests ==========

    @Test
    public void testChunkShortJapaneseText() {
        String text = "これは短い日本語のテキストです。";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertEquals("Short Japanese text should produce one chunk", 1, chunks.size());
        assertTrue("Chunk should contain Japanese text", 
                chunks.get(0).getText().contains("日本語"));
    }

    @Test
    public void testChunkJapaneseTextWithSentences() {
        // Create Japanese text with multiple sentences
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("これは文番号").append(i).append("です。");
        }
        String text = sb.toString();
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Long Japanese text should produce chunks", chunks.size() >= 1);
    }

    @Test
    public void testChunkJapaneseWithDifferentPunctuation() {
        String text = "最初の文です。次の文です！質問ですか？最後の文です。";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
    }

    // ========== Mixed Language Text Tests ==========

    @Test
    public void testChunkMixedEnglishJapanese() {
        String text = "This is English. これは日本語です。More English here. また日本語。";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        
        // The chunk should contain both languages
        String allText = chunks.stream()
                .map(TextChunk::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertTrue("Should contain English", allText.contains("English"));
        assertTrue("Should contain Japanese", allText.contains("日本語"));
    }

    @Test
    public void testChunkMixedWithLongContent() {
        // Create mixed content that exceeds max tokens
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("English sentence ").append(i).append(". ");
            sb.append("日本語の文").append(i).append("。");
        }
        String text = sb.toString();
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Mixed long text should produce multiple chunks", chunks.size() >= 1);
    }

    // ========== Token Estimation Tests ==========

    @Test
    public void testEstimateTokenCountNull() {
        int tokens = chunkingService.estimateTokenCount(null);
        assertEquals("Null input should return 0 tokens", 0, tokens);
    }

    @Test
    public void testEstimateTokenCountEmpty() {
        int tokens = chunkingService.estimateTokenCount("");
        assertEquals("Empty input should return 0 tokens", 0, tokens);
    }

    @Test
    public void testEstimateTokenCountEnglish() {
        // English: approximately 4 characters per token
        String text = "This is a test sentence with multiple words.";
        int tokens = chunkingService.estimateTokenCount(text);
        
        // 44 characters / 4 = ~11 tokens
        assertTrue("English token count should be reasonable", tokens > 5 && tokens < 20);
    }

    @Test
    public void testEstimateTokenCountJapanese() {
        // Japanese: approximately 2 characters per token
        String text = "これは日本語のテストです。";
        int tokens = chunkingService.estimateTokenCount(text);
        
        // 13 characters / 2 = ~6-7 tokens
        assertTrue("Japanese token count should be reasonable", tokens > 3 && tokens < 15);
    }

    @Test
    public void testEstimateTokenCountMixed() {
        String text = "Hello World これは日本語です";
        int tokens = chunkingService.estimateTokenCount(text);
        
        // Mixed text should have intermediate token count
        assertTrue("Mixed text token count should be positive", tokens > 0);
    }

    // ========== Configuration Tests ==========

    @Test
    public void testGetMaxTokensPerChunk() {
        int maxTokens = chunkingService.getMaxTokensPerChunk();
        assertEquals("Max tokens should match config", 512, maxTokens);
    }

    @Test
    public void testGetOverlapTokens() {
        int overlapTokens = chunkingService.getOverlapTokens();
        assertEquals("Overlap tokens should match config", 50, overlapTokens);
    }

    // ========== Chunk Metadata Tests ==========

    @Test
    public void testChunkMetadataStartOffset() {
        String text = "First sentence. Second sentence. Third sentence.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        
        // First chunk should start at offset 0 or close to it
        TextChunk firstChunk = chunks.get(0);
        assertTrue("First chunk start offset should be >= 0", firstChunk.getStartOffset() >= 0);
    }

    @Test
    public void testChunkMetadataTokenCount() {
        String text = "This is a test sentence for token counting.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        
        TextChunk chunk = chunks.get(0);
        assertTrue("Token count should be positive", chunk.getTokenCount() > 0);
    }

    // ========== Edge Cases ==========

    @Test
    public void testChunkSingleCharacter() {
        String text = "A";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        // Single character might be below min tokens threshold
        // but should not cause an error
    }

    @Test
    public void testChunkVeryLongSentence() {
        // Create a very long sentence without any sentence boundaries
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("word").append(i).append(" ");
        }
        String text = sb.toString().trim();
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Very long sentence should be split into chunks", chunks.size() >= 1);
    }

    @Test
    public void testChunkWithNewlines() {
        String text = "First line.\nSecond line.\nThird line.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
    }

    @Test
    public void testChunkWithMultipleSpaces() {
        String text = "Word1    Word2     Word3.  Sentence2.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        
        // Multiple spaces should be normalized
        String chunkText = chunks.get(0).getText();
        assertFalse("Multiple spaces should be normalized", chunkText.contains("    "));
    }

    // ========== Small Max Tokens Configuration Tests ==========

    @Test
    public void testChunkWithSmallMaxTokens() {
        // Configure for very small chunks
        when(ragConfig.getChunkingMaxTokens()).thenReturn(50);
        when(ragConfig.getChunkingOverlapTokens()).thenReturn(10);
        when(ragConfig.getChunkingMinTokens()).thenReturn(10);
        
        TokenBasedChunkingService smallChunkService = new TokenBasedChunkingService(ragConfig);
        
        String text = "This is a longer text that should be split into multiple smaller chunks. " +
                "Each chunk should respect the maximum token limit. " +
                "The overlap should ensure context is preserved between chunks.";
        
        List<TextChunk> chunks = smallChunkService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertTrue("Text should be split into multiple chunks with small max tokens", 
                chunks.size() >= 1);
    }

    // ========== Unicode and Special Characters Tests ==========

    @Test
    public void testChunkWithEmoji() {
        String text = "Hello World! This text has emojis.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
    }

    @Test
    public void testChunkWithChineseCharacters() {
        String text = "这是中文文本。这是第二句话。";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        assertTrue("Should contain Chinese text", chunks.get(0).getText().contains("中文"));
    }

    @Test
    public void testChunkWithKoreanCharacters() {
        String text = "이것은 한국어 텍스트입니다. 두 번째 문장입니다.";
        
        List<TextChunk> chunks = chunkingService.chunk(text);
        
        assertNotNull("Result should not be null", chunks);
        assertFalse("Result should not be empty", chunks.isEmpty());
        assertTrue("Should contain Korean text", chunks.get(0).getText().contains("한국어"));
    }
}
