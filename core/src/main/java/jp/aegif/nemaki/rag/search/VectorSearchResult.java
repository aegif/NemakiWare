package jp.aegif.nemaki.rag.search;

/**
 * Represents a single result from vector semantic search.
 */
public class VectorSearchResult {

    private String documentId;
    private String documentName;
    private String chunkId;
    private int chunkIndex;
    private String chunkText;
    private float score;
    private String path;
    private String objectType;

    public VectorSearchResult() {
    }

    public VectorSearchResult(String documentId, String documentName, String chunkId,
                              int chunkIndex, String chunkText, float score) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.score = score;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
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

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }
}
