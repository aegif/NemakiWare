package jp.aegif.nemaki.rest.purview.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class PurviewJobStateServiceImpl implements PurviewJobStateService {

    private static final Log log = LogFactory.getLog(PurviewJobStateServiceImpl.class);
    private static final String KEY_PREFIX = "purview.job";

    private static final List<String> JOB_FIELD_NAMES = List.of(
            "jobKind", "repositoryId", "status", "startedAt", "endedAt",
            "processedCount", "failedCount", "checkpoint", "errorSummary");

    private final PurviewStateStore stateStore;

    public PurviewJobStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewJobState saveJobState(PurviewJobState jobState) {
        String keyPrefix = buildJobKeyPrefix(jobState.getJobId());
        Map<String, Object> updatedMap = new LinkedHashMap<>();
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

    @Override
    public List<PurviewJobState> listJobStates() {
        Map<String, Object> allByPrefix = stateStore.getAllByPrefix(KEY_PREFIX + ".");
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : allByPrefix.entrySet()) {
            String suffix = entry.getKey().substring((KEY_PREFIX + ".").length());
            int separatorIndex = suffix.indexOf('.');
            if (separatorIndex <= 0 || separatorIndex == suffix.length() - 1) {
                continue;
            }

            String jobId = suffix.substring(0, separatorIndex);
            String fieldName = suffix.substring(separatorIndex + 1);
            grouped.computeIfAbsent(jobId, ignored -> new LinkedHashMap<>()).put(fieldName, entry.getValue());
        }

        List<PurviewJobState> jobs = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            Map<String, Object> fields = entry.getValue();
            jobs.add(new PurviewJobState(
                    entry.getKey(),
                    stringValue(fields.get("jobKind")),
                    stringValue(fields.get("repositoryId")),
                    stringValue(fields.get("status")),
                    stringValue(fields.get("startedAt")),
                    stringValue(fields.get("endedAt")),
                    intValue(fields.get("processedCount")),
                    intValue(fields.get("failedCount")),
                    stringValue(fields.get("checkpoint")),
                    stringValue(fields.get("errorSummary"))));
        }
        jobs.sort(Comparator.comparing(PurviewJobState::getStartedAt).reversed()
                .thenComparing(PurviewJobState::getJobId));
        return jobs;
    }

    @Override
    public int purgeOldJobStates(int retainCount) {
        int effectiveRetainCount = Math.max(1, retainCount);
        List<PurviewJobState> allJobs = listJobStates();

        // Group by (repositoryId, jobKind) pair
        Map<String, List<PurviewJobState>> groupedByKind = new LinkedHashMap<>();
        for (PurviewJobState job : allJobs) {
            String groupKey = job.getRepositoryId() + "\0" + job.getJobKind();
            groupedByKind.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(job);
        }

        int purgedCount = 0;
        for (List<PurviewJobState> jobsInGroup : groupedByKind.values()) {
            // Sort by startedAt descending within group
            jobsInGroup.sort(Comparator.comparing(PurviewJobState::getStartedAt).reversed()
                    .thenComparing(PurviewJobState::getJobId));

            if (jobsInGroup.size() <= effectiveRetainCount) {
                continue;
            }

            List<PurviewJobState> toPurge = jobsInGroup.subList(effectiveRetainCount, jobsInGroup.size());
            for (PurviewJobState job : toPurge) {
                deleteJobState(job.getJobId());
                purgedCount++;
            }
        }
        return purgedCount;
    }

    private void deleteJobState(String jobId) {
        String keyPrefix = buildJobKeyPrefix(jobId);
        List<String> keysToDelete = new ArrayList<>();
        for (String fieldName : JOB_FIELD_NAMES) {
            keysToDelete.add(keyPrefix + "." + fieldName);
        }
        stateStore.removeAll(keysToDelete);
    }

    private String buildJobKeyPrefix(String jobId) {
        return KEY_PREFIX + "." + jobId;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
