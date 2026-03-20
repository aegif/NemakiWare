package jp.aegif.nemaki.rest.purview.state;

public class PurviewLockState {

    private final String repositoryId;
    private final String jobKind;
    private final boolean locked;
    private final String ownerJobId;
    private final String lockedAt;

    public PurviewLockState(
            String repositoryId,
            String jobKind,
            boolean locked,
            String ownerJobId,
            String lockedAt) {
        this.repositoryId = repositoryId;
        this.jobKind = jobKind;
        this.locked = locked;
        this.ownerJobId = ownerJobId;
        this.lockedAt = lockedAt;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getJobKind() {
        return jobKind;
    }

    public boolean isLocked() {
        return locked;
    }

    public String getOwnerJobId() {
        return ownerJobId;
    }

    public String getLockedAt() {
        return lockedAt;
    }
}
