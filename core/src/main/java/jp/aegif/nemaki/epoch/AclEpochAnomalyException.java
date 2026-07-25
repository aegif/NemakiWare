package jp.aegif.nemaki.epoch;

/**
 * Corrupt / untrustworthy ACL-epoch data — fail-closed, never guessed around.
 *
 * <p>Top-level (review 3a): the finalization service and the effective-epoch service previously
 * each declared their own nested {@code AclEpochAnomalyException}, which invited the two sides to
 * drift apart. There is now ONE anomaly type, thrown by the ONE shared validator
 * ({@link AclEpochFields}).
 *
 * <p>Distinct from a PENDING gate (a legitimate mid-mutation state → back off and retry) and from
 * an UNAVAILABLE dependency (a transient read failure → retry): an anomaly means the data itself
 * must be repaired.
 */
public class AclEpochAnomalyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AclEpochAnomalyException(String message) {
        super(message);
    }
}
