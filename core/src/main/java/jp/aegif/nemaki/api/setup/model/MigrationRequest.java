package jp.aegif.nemaki.api.setup.model;

import java.util.List;

public class MigrationRequest {
    private List<String> repositoryIds;
    private boolean confirmed;

    public List<String> getRepositoryIds() { return repositoryIds; }
    public void setRepositoryIds(List<String> repositoryIds) { this.repositoryIds = repositoryIds; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
}
