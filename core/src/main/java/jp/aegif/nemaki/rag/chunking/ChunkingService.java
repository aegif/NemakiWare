package jp.aegif.nemaki.rag.chunking;

import java.util.List;

/**
 * Service interface for splitting documents into chunks for RAG indexing.
 *
 * Chunking is essential for RAG because:
 * 1. Embedding models have token limits
 * 2. Smaller chunks provide more precise retrieval
 * 3. Overlapping chunks prevent context loss at boundaries
 *
 * The default implementation uses simple token-based chunking with
 * configurable max tokens (512) and overlap (50 tokens).
 *
 * @see TokenBasedChunkingService
 */
public interface ChunkingService {

    /**
     * Split text into chunks suitable for embedding.
     *
     * @param text The full text to chunk
     * @return List of text chunks with metadata
     */
    List<TextChunk> chunk(String text);

    /**
     * Estimate the number of tokens in a text.
     * Uses a simple approximation (characters / 4 for English, / 2 for CJK).
     *
     * @param text The text to estimate
     * @return Estimated token count
     */
    int estimateTokenCount(String text);

    /**
     * Get the maximum tokens per chunk.
     *
     * @return Max tokens setting
     */
    int getMaxTokensPerChunk();

    /**
     * Get the overlap tokens between chunks.
     *
     * @return Overlap tokens setting
     */
    int getOverlapTokens();
}
