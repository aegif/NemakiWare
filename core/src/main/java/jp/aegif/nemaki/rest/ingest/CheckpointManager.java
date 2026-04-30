package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages ingest checkpoint persistence via IntegrationSettingsService.
 *
 * <p>Checkpoints track the last successfully imported position for each
 * adapter/profile combination, enabling incremental sync.
 */
public class CheckpointManager {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);

    private IntegrationSettingsService settingsService;
    private ImportProfileDefinitionService profileService;

    // ── DI setters ──

    public void setSettingsService(IntegrationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setProfileService(ImportProfileDefinitionService profileService) {
        this.profileService = profileService;
    }

    // ── Simple checkpoint (string value) ──

    /** Load a simple string checkpoint for non-IMAP adapters. */
    public String loadSimpleCheckpoint(String profileId, String scope) {
        if (settingsService == null) return null;
        String key = "ingest.checkpoint." + profileId + "." + scope;
        String value = settingsService.readSetting(key);
        return (value != null && !value.isBlank()) ? value : null;
    }

    /** Save a simple string checkpoint for non-IMAP adapters. */
    public void saveSimpleCheckpoint(String profileId, String scope, String value) {
        if (settingsService == null || value == null) return;
        String key = "ingest.checkpoint." + profileId + "." + scope;
        settingsService.writeSetting(key, value);
        logger.debug("Checkpoint saved: {} → {}", key, value);
    }

    // ── IMAP checkpoint (uidValidity:uid) ──

    /**
     * Load checkpoint as [uidValidity, lastUid].
     */
    public long[] loadCheckpointWithValidity(String profileId, String mailboxFolder) {
        if (settingsService == null) return new long[]{0, 0};
        String key = "ingest.checkpoint." + profileId + "." + mailboxFolder;
        String value = settingsService.readSetting(key);
        if (value == null || value.isBlank()) return new long[]{0, 0};
        try {
            String[] parts = value.split(":");
            if (parts.length == 2) {
                return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
            }
            // Legacy format: uid only
            return new long[]{0, Long.parseLong(value)};
        } catch (NumberFormatException e) {
            return new long[]{0, 0};
        }
    }

    public void saveCheckpointWithValidity(String profileId, String mailboxFolder,
                                           long uidValidity, long uid) {
        if (settingsService == null) return;
        String key = "ingest.checkpoint." + profileId + "." + mailboxFolder;
        settingsService.writeSetting(key, uidValidity + ":" + uid);
        logger.debug("Checkpoint saved: {}/{} → {}:{}", profileId, mailboxFolder, uidValidity, uid);
    }

    // ── Admin operations ──

    /** Get all checkpoints for a profile (admin diagnostic). */
    public Map<String, Object> getCheckpoints(String profileId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (settingsService == null) return result;
        // Static scopes (single-value adapters):
        for (String scope : List.of("gmail", "notion", "salesforce", "dropbox", "INBOX")) {
            String key = "ingest.checkpoint." + profileId + "." + scope;
            String value = settingsService.readSetting(key);
            if (value != null && !value.isBlank()) result.put(scope, value);
        }
        // Scoped checkpoints: reconstruct the exact key from profile's schedulerParams
        ImportProfileDefinition profile = (profileService != null) ? profileService.get(profileId) : null;
        if (profile != null && profile.getSchedulerParams() != null) {
            Map<String, String> sp = profile.getSchedulerParams();
            tryCheckpoint(result, profileId, "slack." + sp.getOrDefault("channelId", ""));
            String teamId = sp.getOrDefault("teamId", "");
            String channelId = sp.getOrDefault("channelId", "");
            if (!teamId.isBlank() && !channelId.isBlank()) {
                tryCheckpoint(result, profileId, "teams." + teamId + "." + channelId);
            }
            tryCheckpoint(result, profileId, "mattermost." + sp.getOrDefault("channelId", ""));
            tryCheckpoint(result, profileId, "chatwork." + sp.getOrDefault("roomId", ""));
            tryCheckpoint(result, profileId, "m365mail." + sp.getOrDefault("folderId", "inbox"));
            tryCheckpoint(result, profileId, "box." + sp.getOrDefault("folderId", "0"));
        }
        return result;
    }

    /** Reset checkpoint for a profile (admin operation). */
    public void resetCheckpoint(String profileId, String scope) {
        if (settingsService == null) return;
        if (scope != null && !scope.isBlank()) {
            String key = "ingest.checkpoint." + profileId + "." + scope;
            settingsService.writeSetting(key, "");
            logger.info("Checkpoint reset: {}", key);
        } else {
            // Reset all checkpoints for this profile
            Map<String, Object> allCp = getCheckpoints(profileId);
            int count = 0;
            for (String cpScope : allCp.keySet()) {
                settingsService.writeSetting("ingest.checkpoint." + profileId + "." + cpScope, "");
                count++;
            }
            logger.info("All checkpoints reset for profile {} ({} keys)", profileId, count);
        }
    }

    /** Try to read a checkpoint and add to result map if found. */
    private void tryCheckpoint(Map<String, Object> result, String profileId, String scope) {
        if (scope.endsWith(".")) return; // Skip invalid scope
        String key = "ingest.checkpoint." + profileId + "." + scope;
        String val = settingsService.readSetting(key);
        if (val != null && !val.isBlank()) result.put(scope, val);
    }
}
