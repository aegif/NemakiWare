package jp.aegif.nemaki.epoch;

/**
 * A COLLABORATOR IS MISSING — the deployment is mis-wired (review P2-3).
 *
 * <p>Distinct from the three data-driven outcomes a caller must handle per task
 * ({@link AclEffectiveEpochService.AclEpochPendingException},
 * {@link AclEffectiveEpochService.AclEpochUnavailableException} and
 * {@link AclEpochAnomalyException}). Those describe the repository; this one describes the
 * application context, and no amount of retrying or quarantining a task will fix it.
 *
 * <p>It existed only as prose before: the guards threw a bare {@code IllegalStateException}, which is
 * also what the writer throws for its per-task fail-closed conditions, so a caller could not tell a
 * mis-wired bean from a corrupt document and would have retried or terminal-failed the task for ever.
 * A wiring fault should instead surface immediately and loudly to an operator.
 */
public class AclEpochWiringException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AclEpochWiringException(String message) {
        super(message);
    }
}
