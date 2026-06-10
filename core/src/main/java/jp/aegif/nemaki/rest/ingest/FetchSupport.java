package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.util.PropertyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for fetch orchestrators.
 *
 * <p>Contains stateless helpers (static) and stateful helpers (instance) that
 * are used across all adapter fetch methods. Extracted from IngestSchedulerService
 * to enable independent testing and reuse by FetchOrchestrator implementations.
 */
public class FetchSupport {

    private static final Logger logger = LoggerFactory.getLogger(FetchSupport.class);

    private static final int MAX_ERROR_LIST_SIZE = 10_000;

    private PropertyManager propertyManager;
    private IngestJobService ingestJobService;
    private CanonicalImportService canonicalImportService;

    // ── DI setters ──

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public void setIngestJobService(IngestJobService ingestJobService) {
        this.ingestJobService = ingestJobService;
    }

    public void setCanonicalImportService(CanonicalImportService canonicalImportService) {
        this.canonicalImportService = canonicalImportService;
    }

    // ── Static utilities ──

    /** Add error to list with cap to prevent OOM from unbounded accumulation. */
    public static void addError(List<String> errors, String msg) {
        if (errors.size() < MAX_ERROR_LIST_SIZE) {
            errors.add(msg);
        } else if (errors.size() == MAX_ERROR_LIST_SIZE) {
            errors.add("... (error list truncated at " + MAX_ERROR_LIST_SIZE + " entries)");
        }
    }

    /**
     * Filename suffixes for OS-/desktop-generated pseudo files that carry no
     * value in an ECM and should never be ingested as documents. The canonical
     * trigger is the macOS text clipping ({@code .textClipping}, often
     * re-suffixed to {@code .textclipping} on upload): a 12 KB Apple-proprietary
     * plist blob that opens to nothing in any normal viewer. Matched
     * case-insensitively against the trailing characters of the filename.
     */
    private static final java.util.List<String> PSEUDO_FILE_SUFFIXES = java.util.List.of(
            ".textclipping", ".ds_store");

    /**
     * Returns true when {@code fileName} looks like an OS/desktop pseudo file
     * (e.g. a macOS {@code .textClipping} or {@code .DS_Store}) that should be
     * skipped during ingestion rather than persisted as a content-less or
     * opaque document.
     */
    public static boolean isPseudoSystemFile(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : PSEUDO_FILE_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    /** Guess MIME type from filename extension; returns null if unknown. */
    public static String guessMimeType(String fileName) {
        if (fileName == null) return null;
        try {
            return java.nio.file.Files.probeContentType(java.nio.file.Path.of(fileName));
        } catch (Exception e) {
            return null;
        }
    }

    /** Replace filesystem-illegal characters with underscores. */
    public static String sanitizeSubject(String subject) {
        if (subject == null || subject.isBlank()) return "untitled";
        return subject.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    /** Truncate text to a reasonable size for externalContext storage (max 2000 chars). */
    public static String truncateForContext(String text) {
        if (text == null) return "";
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    /**
     * Calculate per-request throttle delay in milliseconds based on connector rateLimitRpm.
     * Returns 0 if no rate limit is configured.
     */
    public static long calculateThrottleDelayMs(ConnectorDefinition connector) {
        if (connector.getRateLimitRpm() == null || connector.getRateLimitRpm() <= 0) return 0;
        return 60_000L / connector.getRateLimitRpm();
    }

    // ── Instance methods (stateful) ──

    /** Resolve credential secret from connector's credentialRef. */
    public String resolvePassword(ConnectorDefinition connector) {
        String credentialRef = connector.getCredentialRef();
        if (credentialRef == null || credentialRef.isBlank()) return null;
        if (propertyManager != null) {
            String value = propertyManager.readValue(credentialRef);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * ThreadLocal carrying the current job record for progress-based heartbeat.
     * Set by pollScheduledProfiles before executeFetch, read by throttle() on every item.
     */
    private static final ThreadLocal<IngestJobRecord> currentJobHolder = new ThreadLocal<>();
    private static final ThreadLocal<long[]> lastProgressHeartbeat = ThreadLocal.withInitial(() -> new long[]{0});

    /** Get the ThreadLocal current job holder (for setting from the scheduler). */
    public static ThreadLocal<IngestJobRecord> currentJobHolder() { return currentJobHolder; }

    /** Get the ThreadLocal last heartbeat tracker (for setting from the scheduler). */
    public static ThreadLocal<long[]> lastProgressHeartbeat() { return lastProgressHeartbeat; }

    /**
     * Sleep for the throttle delay, if configured. Also sends a progress-based
     * heartbeat at most once per 5 minutes.
     */
    public void throttle(long delayMs) {
        // Check for cancellation (timeout or explicit cancel)
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Fetch cancelled");
        }
        // Progress heartbeat
        IngestJobRecord job = currentJobHolder.get();
        if (job != null && ingestJobService != null) {
            long now = System.currentTimeMillis();
            long[] last = lastProgressHeartbeat.get();
            if (now - last[0] > 5 * 60 * 1000L) {
                try { ingestJobService.heartbeat(job); } catch (Exception ignored) {}
                last[0] = now;
            }
        }
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Create a direct relationship, logging errors instead of throwing. */
    public void createRelationshipSafe(org.apache.chemistry.opencmis.commons.server.CallContext callContext,
                                       String repositoryId, String sourceId, String targetId,
                                       List<String> errors) {
        try {
            String err = canonicalImportService.createDirectRelationship(callContext, repositoryId, sourceId, targetId);
            if (err != null) addError(errors, err);
        } catch (Exception e) {
            addError(errors, "Relationship " + sourceId + " → " + targetId + ": " + e.getMessage());
        }
    }

    /** Build a standard mail ingest request (shared by IMAP/Gmail/M365). */
    public ExternalIngestRequest buildMailRequest(ImportProfileDefinition profile, ConnectorDefinition connector,
                                                  String sourceId, String subject, InputStream eml,
                                                  String mailboxId) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId(profile.getProfileId());
        req.setConnectorId(connector.getConnectorId());
        req.setRepositoryId(profile.getRepositoryId());
        req.setSourceObjectId(sourceId);
        req.setSourceObjectType("message");
        req.setFileName(sanitizeSubject(subject) + ".eml");
        req.setMimeType("message/rfc822");
        req.setContentStream(eml);
        req.setExecutionMode("scheduled");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mailboxId", mailboxId != null ? mailboxId : "inbox");
        metadata.put("messageStableId", sourceId);
        req.setMetadata(metadata);
        return req;
    }
}
