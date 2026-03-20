package jp.aegif.nemaki.rest.purview.state;

import org.springframework.stereotype.Service;

@Service
public class PurviewJobStateServiceImpl implements PurviewJobStateService {

    private static final String KEY_PREFIX = "purview.job";

    private final PurviewStateStore stateStore;

    public PurviewJobStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewJobState saveJobState(PurviewJobState jobState) {
        String keyPrefix = buildJobKeyPrefix(jobState.getJobId());
        java.util.Map<String, Object> updatedMap = new java.util.LinkedHashMap<>();
        updatedMap.put(keyPrefix + ".jobKind", jobState.getJobKind());
        updatedMap.put(keyPrefix + ".repositoryId", jobState.getRepositoryId());
        updatedMap.put(keyPrefix + ".status", jobState.getStatus());
        updatedMap.put(keyPrefix + ".startedAt", jobState.getStartedAt());
        updatedMap.put(keyPrefix + ".endedAt", jobState.getEndedAt());
        updatedMap.put(keyPrefix + ".processedCount", jobState.getProcessedCount());
        updatedMap.put(keyPrefix + ".failedCount", jobState.getFailedCount());
        updatedMap.put(keyPrefix + ".checkpoint", jobState.getCheckpoint());
        updatedMap.put(keyPrefix + ".errorSummary", jobState.getErrorSummary());
        stateStore.putAll(updatedMap);

        return getJobState(jobState.getJobId());
    }

    @Override
    public PurviewJobState getJobState(String jobId) {
        String keyPrefix = buildJobKeyPrefix(jobId);
        return new PurviewJobState(
                jobId,
                stateStore.getString(keyPrefix + ".jobKind"),
                stateStore.getString(keyPrefix + ".repositoryId"),
                stateStore.getString(keyPrefix + ".status"),
                stateStore.getString(keyPrefix + ".startedAt"),
                stateStore.getString(keyPrefix + ".endedAt"),
                stateStore.getInt(keyPrefix + ".processedCount"),
                stateStore.getInt(keyPrefix + ".failedCount"),
                stateStore.getString(keyPrefix + ".checkpoint"),
                stateStore.getString(keyPrefix + ".errorSummary"));
    }

    private String buildJobKeyPrefix(String jobId) {
        return KEY_PREFIX + "." + jobId;
    }
}
