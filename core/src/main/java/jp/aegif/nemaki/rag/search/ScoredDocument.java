package jp.aegif.nemaki.rag.search;

/**
 * Tracks document scores during weighted vector search.
 *
 * This class holds both raw (unweighted) scores for filtering and weighted scores for ranking.
 * It accumulates scores from both chunk vector search and property vector search,
 * then combines them for final ranking.
 *
 * Thread Safety: This class is not inherently thread-safe. When used in concurrent contexts
 * (like parallel KNN searches), external synchronization should be used for compare-then-update
 * operations.
 */
class ScoredDocument {
    private final String documentId;

    // Weighted scores for ranking (after applying boost factors)
    private float propertyScore = 0f;
    private float contentScore = 0f;

    // Raw scores for filtering (before applying boost factors)
    private float rawPropertyScore = 0f;
    private float rawContentScore = 0f;

    // Chunk information (from content search)
    private String chunkId;
    private int chunkIndex;
    private String chunkText;

    // Document metadata (from property search)
    private String documentName;
    private String propertyText;

    public ScoredDocument(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public float getPropertyScore() {
        return propertyScore;
    }

    public void setPropertyScore(float propertyScore) {
        this.propertyScore = propertyScore;
    }

    public float getContentScore() {
        return contentScore;
    }

    public void setContentScore(float contentScore) {
        this.contentScore = contentScore;
    }

    public float getRawPropertyScore() {
        return rawPropertyScore;
    }

    public void setRawPropertyScore(float rawPropertyScore) {
        this.rawPropertyScore = rawPropertyScore;
    }

    public float getRawContentScore() {
        return rawContentScore;
    }

    public void setRawContentScore(float rawContentScore) {
        this.rawContentScore = rawContentScore;
    }

    /**
     * Get the combined weighted score for ranking.
     * This is the sum of property and content scores after boost factors are applied.
     *
     * @return Combined score = propertyScore + contentScore
     */
    public float getCombinedScore() {
        return propertyScore + contentScore;
    }

    /**
     * Get the maximum raw (unweighted) score for filtering.
     * This represents the best similarity match before boost weighting,
     * used to apply minScore threshold consistently.
     *
     * @return Maximum of rawPropertyScore and rawContentScore
     */
    public float getMaxRawScore() {
        return Math.max(rawPropertyScore, rawContentScore);
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getPropertyText() {
        return propertyText;
    }

    public void setPropertyText(String propertyText) {
        this.propertyText = propertyText;
    }

    /**
     * Convert this scored document to a VectorSearchResult.
     *
     * @return VectorSearchResult populated with document data
     */
    public VectorSearchResult toResult() {
        VectorSearchResult result = new VectorSearchResult();
        result.setDocumentId(documentId);
        result.setChunkId(chunkId);
        result.setChunkIndex(chunkIndex);
        result.setChunkText(chunkText);
        result.setDocumentName(documentName);
        return result;
    }
}
