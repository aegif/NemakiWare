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

    /**
     * Load a simple string checkpoint for non-IMAP adapters.
     *
     * <p>{@code null} means "this profile has never polled". A read that FAILED is not that:
     * the poll would then take only the first page, treat every item as new, and write the
     * newest returned timestamp as the checkpoint — moving it PAST the older items it never
     * listed, which are filtered out on every later poll. The refusal is allowed out so the
     * tick fails instead. Three reviews reported it.
     *
     * @throws IntegrationSettingsService.SettingUnreadableException when the store did not
     *         answer.
     */
    public String loadSimpleCheckpoint(String profileId, String scope) {
        if (settingsService == null) return null;
        String key = "ingest.checkpoint." + profileId + "." + scope;
        String value = settingsService.readSettingOrRefuse(key);
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
        // {0, 0} means "never polled" and makes the next poll start from the beginning. A
        // read that FAILED must not produce it — see loadSimpleCheckpoint.
        String value = settingsService.readSettingOrRefuse(key);
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

    /**
     * One enumeration pass, and whether the profile's own row took part in it.
     *
     * <p>The scoped checkpoint keys can only be rebuilt from the profile's
     * {@code schedulerParams}, so without that row this enumeration covers the static scopes
     * ALONE. {@code profileRowRead} says which of the two happened. It exists because the
     * caller cannot tell from the map: a profile with no scoped checkpoints and a profile
     * whose row could not be read both come back with only the static ones.
     *
     * <p>What it does NOT separate: a row that is ABSENT from a row whose read FAILED. Both
     * reach this class as {@code get() == null}, and telling them apart needs the row's
     * repository, which this class is not given. {@code false} therefore means "this pass did
     * not have the row", never "there is no such profile" — the wording of every message
     * derived from it has to stay on that side, and a caller must not read absence out of it.
     */
    public record Enumeration(Map<String, Object> checkpoints, boolean profileRowRead) {}

    /** What one reset pass actually managed to do — see {@link Enumeration}. */
    public record ResetSummary(int keysReset, boolean profileRowRead) {}

    /** Get all checkpoints for a profile (admin diagnostic). */
    public Map<String, Object> getCheckpoints(String profileId) {
        return enumerateCheckpoints(profileId).checkpoints();
    }

    /** As {@link #getCheckpoints}, saying whether the profile row was part of the answer. */
    public Enumeration enumerateCheckpoints(String profileId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (settingsService == null) return new Enumeration(result, false);
        // Static scopes (single-value adapters):
        for (String scope : List.of("gmail", "notion", "salesforce", "dropbox", "INBOX")) {
            String key = "ingest.checkpoint." + profileId + "." + scope;
            // readSettingOrRefuse, like the two loads above. This enumeration answers the
            // admin endpoint, which reports "checkpoints: {}" and "All checkpoints reset for X
            // (0 keys)" — both statements that the profile has never polled — from a store
            // that simply did not answer. A review found the two call sites the earlier round
            // left behind.
            String value = settingsService.readSettingOrRefuse(key);
            if (value != null && !value.isBlank()) result.put(scope, value);
        }
        // Scoped checkpoints: reconstruct the exact key from profile's schedulerParams
        ImportProfileDefinition profile = (profileService != null) ? profileService.get(profileId) : null;
        boolean profileRowRead = profile != null;
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
        return new Enumeration(result, profileRowRead);
    }

    /**
     * Reset checkpoint for a profile (admin operation).
     *
     * <p>Returns what the pass did rather than nothing. Without a scope the pass can only
     * reset what {@link #enumerateCheckpoints} could name, and that depends on reading the
     * profile's row — which answers null for a read that FAILED and for a profile that is not
     * there alike. It used to log "All checkpoints reset for profile X" either way, and the
     * endpoint answered an unqualified success; a review found an incomplete reset reported as
     * a complete one. The caller now has the fact and says so.
     */
    public ResetSummary resetCheckpoint(String profileId, String scope) {
        if (settingsService == null) return new ResetSummary(0, false);
        if (scope != null && !scope.isBlank()) {
            String key = "ingest.checkpoint." + profileId + "." + scope;
            settingsService.writeSetting(key, "");
            logger.info("Checkpoint reset: {}", key);
            // false, not true: the profile row was NOT read on this path — it is not needed,
            // because the caller named the key. Answering true here says "the row took part"
            // about a pass that never asked, and a review found the record contradicting its
            // own javadoc. The caller distinguishes the two passes by the scope it passed.
            return new ResetSummary(1, false);
        }
        // Reset all checkpoints for this profile
        Enumeration enumerated = enumerateCheckpoints(profileId);
        int count = 0;
        for (String cpScope : enumerated.checkpoints().keySet()) {
            settingsService.writeSetting("ingest.checkpoint." + profileId + "." + cpScope, "");
            count++;
        }
        if (enumerated.profileRowRead()) {
            logger.info("All checkpoints reset for profile {} ({} keys)", profileId, count);
        } else {
            logger.warn("Reset {} static checkpoint keys of profile {}, but its definition row"
                    + " was not read, so any scoped checkpoints (slack/teams/mattermost/"
                    + "chatwork/m365mail/box) could not be named and still hold their"
                    + " position", count, profileId);
        }
        return new ResetSummary(count, enumerated.profileRowRead());
    }

    /** Try to read a checkpoint and add to result map if found. */
    private void tryCheckpoint(Map<String, Object> result, String profileId, String scope) {
        if (scope.endsWith(".")) return; // Skip invalid scope
        String key = "ingest.checkpoint." + profileId + "." + scope;
        String val = settingsService.readSettingOrRefuse(key);
        if (val != null && !val.isBlank()) result.put(scope, val);
    }
}
