package jp.aegif.nemaki.init;

/**
 * Overview of a single CouchDB repository's state.
 */
public class RepositoryOverview {

    private String repositoryId;
    private String name;
    private long documentCount;
    private int viewCount;
    private int requiredViewCount;
    private boolean hasSystemFolder;
    private boolean mainRepository; // true for main repos, false for archive/config DBs
    private String judgment; // "current" | "legacy" | "empty"
    private String error;    // non-null when probe failed (auth error, connection error, etc.)

    public RepositoryOverview() {
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(long documentCount) {
        this.documentCount = documentCount;
    }

    public int getViewCount() {
        return viewCount;
    }

    public void setViewCount(int viewCount) {
        this.viewCount = viewCount;
    }

    public int getRequiredViewCount() {
        return requiredViewCount;
    }

    public void setRequiredViewCount(int requiredViewCount) {
        this.requiredViewCount = requiredViewCount;
    }

    public boolean isHasSystemFolder() {
        return hasSystemFolder;
    }

    public void setHasSystemFolder(boolean hasSystemFolder) {
        this.hasSystemFolder = hasSystemFolder;
    }

    public String getJudgment() {
        return judgment;
    }

    public void setJudgment(String judgment) {
        this.judgment = judgment;
    }

    public boolean isMainRepository() {
        return mainRepository;
    }

    public void setMainRepository(boolean mainRepository) {
        this.mainRepository = mainRepository;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
