package jp.aegif.nemaki.rag.chunking;

/**
 * Represents a chunk of text extracted from a document for RAG indexing.
 *
 * Each chunk contains:
 * - The text content
 * - Its index position within the document
 * - Start/end character offsets for tracking
 * - Token count for validation
 */
public class TextChunk {

    private final String text;
    private final int index;
    private final int startOffset;
    private final int endOffset;
    private final int tokenCount;

    public TextChunk(String text, int index, int startOffset, int endOffset, int tokenCount) {
        this.text = text;
        this.index = index;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.tokenCount = tokenCount;
    }

    /**
     * Get the chunk text content.
     */
    public String getText() {
        return text;
    }

    /**
     * Get the 0-based index of this chunk within the document.
     */
    public int getIndex() {
        return index;
    }

    /**
     * Get the character offset where this chunk starts in the original document.
     */
    public int getStartOffset() {
        return startOffset;
    }

    /**
     * Get the character offset where this chunk ends in the original document.
     */
    public int getEndOffset() {
        return endOffset;
    }

    /**
     * Get the estimated token count for this chunk.
     */
    public int getTokenCount() {
        return tokenCount;
    }

    /**
     * Generate a unique chunk ID based on document ID and chunk index.
     *
     * @param documentId The parent document ID
     * @return Unique chunk identifier
     */
    public String generateChunkId(String documentId) {
        return documentId + "_chunk_" + index;
    }

    @Override
    public String toString() {
        return String.format("TextChunk[index=%d, tokens=%d, offset=%d-%d, text=%s...]",
                index, tokenCount, startOffset, endOffset,
                text.length() > 50 ? text.substring(0, 50) : text);
    }
}
