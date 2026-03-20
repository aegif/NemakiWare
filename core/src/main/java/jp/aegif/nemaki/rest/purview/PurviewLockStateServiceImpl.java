package jp.aegif.nemaki.rest.purview;

import org.springframework.stereotype.Service;

@Service
public class PurviewLockStateServiceImpl implements PurviewLockStateService {

    private static final String KEY_PREFIX = "purview.lock.repository";

    private final PurviewStateStore stateStore;

    public PurviewLockStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewLockState getRepositoryLock(String repositoryId, String jobKind) {
        String keyPrefix = buildRepositoryKeyPrefix(repositoryId, jobKind);
        String ownerJobId = stateStore.getString(keyPrefix + ".ownerJobId");
        return new PurviewLockState(
                repositoryId,
                jobKind,
                !ownerJobId.isBlank(),
                ownerJobId,
                stateStore.getString(keyPrefix + ".lockedAt"));
    }

    @Override
    public boolean tryAcquireRepositoryLock(String repositoryId, String jobKind, String ownerJobId, String lockedAt) {
        PurviewLockState current = getRepositoryLock(repositoryId, jobKind);
        if (current.isLocked() && !ownerJobId.equals(current.getOwnerJobId())) {
            return false;
        }

        String keyPrefix = buildRepositoryKeyPrefix(repositoryId, jobKind);
        java.util.Map<String, Object> updatedMap = new java.util.LinkedHashMap<>();
        updatedMap.put(keyPrefix + ".ownerJobId", ownerJobId);
        updatedMap.put(keyPrefix + ".lockedAt", lockedAt);
        stateStore.putAll(updatedMap);
        return true;
    }

    @Override
    public void releaseRepositoryLock(String repositoryId, String jobKind, String ownerJobId) {
        PurviewLockState current = getRepositoryLock(repositoryId, jobKind);
        if (!current.isLocked() || !ownerJobId.equals(current.getOwnerJobId())) {
            return;
        }

        String keyPrefix = buildRepositoryKeyPrefix(repositoryId, jobKind);
        stateStore.removeAll(java.util.List.of(
                keyPrefix + ".ownerJobId",
                keyPrefix + ".lockedAt"));
    }

    private String buildRepositoryKeyPrefix(String repositoryId, String jobKind) {
        return KEY_PREFIX + "." + repositoryId.trim() + "." + jobKind.trim();
    }
}
