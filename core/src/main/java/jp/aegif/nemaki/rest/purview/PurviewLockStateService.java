package jp.aegif.nemaki.rest.purview;

public interface PurviewLockStateService {

    PurviewLockState getRepositoryLock(String repositoryId, String jobKind);

    boolean tryAcquireRepositoryLock(String repositoryId, String jobKind, String ownerJobId, String lockedAt);

    void releaseRepositoryLock(String repositoryId, String jobKind, String ownerJobId);
}
