package jp.aegif.nemaki.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * RC5.5 H1: helper around {@link AuditLogger#logOperation} that
 * preserves the "audit failure must never break the business path"
 * invariant while replacing the silent {@code catch (RuntimeException
 * ignored)} pattern that proliferated across the RC5 cycle.
 *
 * <p>Previously each audit call site had its own try/catch block that
 * swallowed exceptions with no log line at all. External review of
 * RC5.4 caught the pattern as a silent partial-outage risk: if the
 * audit pipeline starts failing in production, operators have no
 * signal until they happen to check audit volume.
 *
 * <p><b>What this helper logs.</b> On failure, the {@code AuditOperation},
 * the actor user ID, the object ID, and the exception message — all
 * fields that already appear in normal audit entries. The
 * {@code details} map is intentionally NOT logged because it can
 * contain entries that callers expect to stay in audit-only sinks
 * (e.g. principal lists, internal IDs); leaking them into the general
 * application log would defeat audit's segregation contract.
 *
 * <p><b>What this helper does NOT do.</b> It does not retry, queue, or
 * persist the failed audit anywhere. Audit emission is best-effort
 * by design; a missed entry is logged as a WARN and the API path
 * proceeds. Operators alarming on the WARN class can detect
 * pipeline-wide failure within one polling interval.
 */
public final class AuditEmitSupport {

    private static final Logger logger = LoggerFactory.getLogger(AuditEmitSupport.class);

    private AuditEmitSupport() {}

    /**
     * Best-effort audit emit. Returns true if the audit was emitted
     * successfully, false if the audit logger was null or the emit
     * threw. The boolean is informational — callers do not need to
     * branch on it; the API path proceeds either way.
     */
    public static boolean safeEmit(AuditLogger auditLogger,
                                   AuditOperation operation,
                                   String repositoryId,
                                   String userId,
                                   String objectId,
                                   boolean success,
                                   String errorMessage,
                                   Map<String, ?> details) {
        if (auditLogger == null) return false;
        try {
            auditLogger.logOperation(
                    operation, repositoryId, userId, objectId, success, errorMessage, details);
            return true;
        } catch (RuntimeException e) {
            // Best-effort logging: include operation + actor + object so
            // ops can correlate the failure with a specific call site.
            // Deliberately omit `details` from the log — the audit
            // pipeline is the only sink allowed to see audit details.
            logger.warn("Audit emit failed: op={}, repositoryId={}, userId={}, objectId={}, errorClass={}, errorMessage={}",
                    operation != null ? operation.name() : "(null)",
                    repositoryId,
                    userId,
                    objectId,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return false;
        }
    }
}
