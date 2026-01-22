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

    // CJK character detection pattern
    private static final Pattern CJK_PATTERN = Pattern.compile(
            "[\\u4E00-\\u9FFF\\u3040-\\u309F\\u30A0-\\u30FF\\uAC00-\\uD7AF]");

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

        int totalTokens = estimateTokenCount(text);
        int maxTokens = ragConfig.getChunkingMaxTokens();
        int overlapTokens = ragConfig.getChunkingOverlapTokens();
        int minTokens = ragConfig.getChunkingMinTokens();

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

            int sentenceTokens = estimateTokenCount(sentence);

            // Handle very long sentences (longer than max tokens)
            if (sentenceTokens > maxTokens) {
                // Flush current chunk if not empty
                if (currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString(), chunkIndex++,
                            currentOffset - currentChunk.length(), currentOffset, currentTokens));
                    overlapText = extractOverlap(currentChunk.toString(), overlapTokens);
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }

                // Split long sentence by character count
                List<TextChunk> subChunks = splitLongText(sentence, chunkIndex, currentOffset, maxTokens, overlapTokens);
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
                overlapText = extractOverlap(currentChunk.toString(), overlapTokens);
                currentChunk = new StringBuilder(overlapText);
                currentTokens = estimateTokenCount(overlapText);
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
            int finalTokens = estimateTokenCount(currentChunk.toString());
            if (finalTokens >= minTokens || chunks.isEmpty()) {
                chunks.add(createChunk(currentChunk.toString(), chunkIndex,
                        currentOffset - currentChunk.length(), currentOffset, finalTokens));
            } else if (!chunks.isEmpty()) {
                // Merge with previous chunk if too small
                TextChunk lastChunk = chunks.remove(chunks.size() - 1);
                String mergedText = lastChunk.getText() + " " + currentChunk.toString();
                chunks.add(new TextChunk(mergedText, lastChunk.getIndex(),
                        lastChunk.getStartOffset(), currentOffset,
                        estimateTokenCount(mergedText)));
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

    private String extractOverlap(String text, int overlapTokens) {
        if (overlapTokens <= 0 || text.isEmpty()) {
            return "";
        }

        // Estimate characters needed for overlap tokens
        double cjkRatio = calculateCjkRatio(text);
        double charsPerToken = ENGLISH_CHARS_PER_TOKEN * (1 - cjkRatio) + CJK_CHARS_PER_TOKEN * cjkRatio;
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

    private List<TextChunk> splitLongText(String text, int startIndex, int startOffset,
                                           int maxTokens, int overlapTokens) {
        List<TextChunk> chunks = new ArrayList<>();

        double cjkRatio = calculateCjkRatio(text);
        double charsPerToken = ENGLISH_CHARS_PER_TOKEN * (1 - cjkRatio) + CJK_CHARS_PER_TOKEN * cjkRatio;
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
                        estimateTokenCount(chunkText)));
            }

            currentPos = endPos - overlapChars;
            if (currentPos <= 0) {
                currentPos = endPos;
            }
        }

        return chunks;
    }

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

        long cjkCount = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ||
                        Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA ||
                        Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA ||
                        Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)
                .count();

        return (double) cjkCount / text.length();
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
