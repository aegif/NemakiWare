package jp.aegif.nemaki.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.rag.config.RAGConfig;

/**
 * Token-based implementation of ChunkingService.
 *
 * Splits text into chunks based on estimated token count.
 * Uses sentence boundaries when possible to maintain semantic coherence.
 *
 * Token Estimation:
 * - English text: ~4 characters per token
 * - CJK text (Japanese/Chinese/Korean): ~2 characters per token
 * - Mixed text: Weighted average based on CJK character ratio
 *
 * Chunking Strategy:
 * 1. Split text into sentences
 * 2. Accumulate sentences until max tokens reached
 * 3. Create overlap by including last N tokens from previous chunk
 * 4. Handle edge cases (very long sentences, short documents)
 */
@Service
public class TokenBasedChunkingService implements ChunkingService {

    private static final Log log = LogFactory.getLog(TokenBasedChunkingService.class);

    // Sentence splitting pattern (handles Japanese, English, and mixed text)
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "(?<=[。！？.!?])\\s*|(?<=\\n)\\s*");

    // Token estimation constants
    private static final double ENGLISH_CHARS_PER_TOKEN = 4.0;
    private static final double CJK_CHARS_PER_TOKEN = 2.0;

    private final RAGConfig ragConfig;

    @Autowired
    public TokenBasedChunkingService(RAGConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    @Override
    public List<TextChunk> chunk(String text) {
        List<TextChunk> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // Normalize whitespace
        text = text.replaceAll("\\s+", " ").trim();

        // Calculate CJK ratio once for the entire text (sampled) and cache it
        double cjkRatio = calculateCjkRatio(text);
        double charsPerToken = ENGLISH_CHARS_PER_TOKEN * (1 - cjkRatio) + CJK_CHARS_PER_TOKEN * cjkRatio;

        int totalTokens = estimateTokenCountFast(text.length(), charsPerToken);
        int maxTokens = ragConfig.getChunkingMaxTokens();
        int overlapTokens = ragConfig.getChunkingOverlapTokens();
        int minTokens = ragConfig.getChunkingMinTokens();

        if (log.isDebugEnabled()) {
            log.debug(String.format("Chunking text: length=%d, cjkRatio=%.2f, charsPerToken=%.2f, totalTokens=%d",
                    text.length(), cjkRatio, charsPerToken, totalTokens));
        }

        // If text fits in one chunk, return as single chunk
        if (totalTokens <= maxTokens) {
            chunks.add(new TextChunk(text, 0, 0, text.length(), totalTokens));
            return chunks;
        }

        // Split into sentences
        String[] sentences = SENTENCE_PATTERN.split(text);

        int chunkIndex = 0;
        int currentOffset = 0;
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        String overlapText = "";

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }

            int sentenceTokens = estimateTokenCountFast(sentence.length(), charsPerToken);

            // Handle very long sentences (longer than max tokens)
            if (sentenceTokens > maxTokens) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Long sentence detected: length=%d, tokens=%d", sentence.length(), sentenceTokens));
                }
                // Flush current chunk if not empty
                if (currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString(), chunkIndex++,
                            currentOffset - currentChunk.length(), currentOffset, currentTokens));
                    overlapText = extractOverlapFast(currentChunk.toString(), overlapTokens, charsPerToken);
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }

                // Split long sentence by character count
                List<TextChunk> subChunks = splitLongTextFast(sentence, chunkIndex, currentOffset, maxTokens, overlapTokens, charsPerToken);
                for (TextChunk subChunk : subChunks) {
                    chunks.add(subChunk);
                    chunkIndex++;
                }
                currentOffset += sentence.length() + 1;  // +1 for separator
                continue;
            }

            // Check if adding this sentence exceeds max tokens
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                // Create chunk from accumulated text
                chunks.add(createChunk(currentChunk.toString(), chunkIndex++,
                        currentOffset - currentChunk.length(), currentOffset, currentTokens));

                // Prepare overlap for next chunk
                overlapText = extractOverlapFast(currentChunk.toString(), overlapTokens, charsPerToken);
                currentChunk = new StringBuilder(overlapText);
                currentTokens = estimateTokenCountFast(overlapText.length(), charsPerToken);
            }

            // Add sentence to current chunk
            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
            currentTokens += sentenceTokens;
            currentOffset += sentence.length() + 1;
        }

        // Add final chunk if not empty and meets minimum size
        if (currentChunk.length() > 0) {
            int finalTokens = estimateTokenCountFast(currentChunk.toString().length(), charsPerToken);
            if (finalTokens >= minTokens || chunks.isEmpty()) {
                chunks.add(createChunk(currentChunk.toString(), chunkIndex,
                        currentOffset - currentChunk.length(), currentOffset, finalTokens));
            } else if (!chunks.isEmpty()) {
                // Merge with previous chunk if too small
                TextChunk lastChunk = chunks.remove(chunks.size() - 1);
                String mergedText = lastChunk.getText() + " " + currentChunk.toString();
                chunks.add(new TextChunk(mergedText, lastChunk.getIndex(),
                        lastChunk.getStartOffset(), currentOffset,
                        estimateTokenCountFast(mergedText.length(), charsPerToken)));
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Chunked text into %d chunks (total tokens: %d, max: %d)",
                    chunks.size(), totalTokens, maxTokens));
        }

        return chunks;
    }

    private TextChunk createChunk(String text, int index, int startOffset, int endOffset, int tokenCount) {
        return new TextChunk(text.trim(), index, startOffset, endOffset, tokenCount);
    }

    // Note: extractOverlap and splitLongText methods removed - replaced by fast implementations below

    @Override
    public int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double cjkRatio = calculateCjkRatio(text);
        double charsPerToken = ENGLISH_CHARS_PER_TOKEN * (1 - cjkRatio) + CJK_CHARS_PER_TOKEN * cjkRatio;

        return (int) Math.ceil(text.length() / charsPerToken);
    }

    private double calculateCjkRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }

        // Sample only first 10000 characters for performance
        // This is sufficient to estimate the language mix for token calculation
        final int SAMPLE_SIZE = 10000;
        String sample = text.length() > SAMPLE_SIZE ? text.substring(0, SAMPLE_SIZE) : text;

        int cjkCount = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.HAN ||
                script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA ||
                script == Character.UnicodeScript.HANGUL) {
                cjkCount++;
            }
        }

        return (double) cjkCount / sample.length();
    }

    /**
     * Fast token count estimation using pre-calculated charsPerToken.
     */
    private int estimateTokenCountFast(int textLength, double charsPerToken) {
        if (textLength <= 0) {
            return 0;
        }
        return (int) Math.ceil(textLength / charsPerToken);
    }

    /**
     * Fast overlap extraction using pre-calculated charsPerToken.
     */
    private String extractOverlapFast(String text, int overlapTokens, double charsPerToken) {
        if (overlapTokens <= 0 || text.isEmpty()) {
            return "";
        }

        int overlapChars = (int) (overlapTokens * charsPerToken);

        if (overlapChars >= text.length()) {
            return text;
        }

        // Find word/sentence boundary near the overlap point
        int startIndex = text.length() - overlapChars;
        int boundaryIndex = text.indexOf(' ', startIndex);
        if (boundaryIndex == -1 || boundaryIndex >= text.length() - 10) {
            boundaryIndex = startIndex;
        }

        return text.substring(boundaryIndex).trim();
    }

    /**
     * Fast long text splitting using pre-calculated charsPerToken.
     */
    private List<TextChunk> splitLongTextFast(String text, int startIndex, int startOffset,
                                               int maxTokens, int overlapTokens, double charsPerToken) {
        List<TextChunk> chunks = new ArrayList<>();

        int charsPerChunk = (int) (maxTokens * charsPerToken);
        int overlapChars = (int) (overlapTokens * charsPerToken);

        int currentPos = 0;
        int chunkIndex = startIndex;

        while (currentPos < text.length()) {
            int endPos = Math.min(currentPos + charsPerChunk, text.length());

            // Try to find word boundary
            if (endPos < text.length()) {
                int boundaryPos = text.lastIndexOf(' ', endPos);
                if (boundaryPos > currentPos + charsPerChunk / 2) {
                    endPos = boundaryPos;
                }
            }

            String chunkText = text.substring(currentPos, endPos).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new TextChunk(chunkText, chunkIndex++,
                        startOffset + currentPos, startOffset + endPos,
                        estimateTokenCountFast(chunkText.length(), charsPerToken)));
            }

            // Calculate next position with overlap
            int nextPos = endPos - overlapChars;
            // Ensure we always make forward progress to avoid infinite loop
            // When near the end, we might not have room for overlap, so just advance to endPos
            if (nextPos <= currentPos || endPos >= text.length()) {
                currentPos = endPos;
            } else {
                currentPos = nextPos;
            }
        }

        return chunks;
    }

    @Override
    public int getMaxTokensPerChunk() {
        return ragConfig.getChunkingMaxTokens();
    }

    @Override
    public int getOverlapTokens() {
        return ragConfig.getChunkingOverlapTokens();
    }
}
