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
 *
 * <p><b>Extends {@link RuntimeException}, NOT {@code IllegalStateException}</b> (review P1-2). The
 * first version extended {@code IllegalStateException} — the very type it exists to be distinguished
 * FROM — so {@code catch (IllegalStateException e) { terminalFail(task); }} behaved exactly as
 * before and the separation was opt-in rather than enforced. It now sits beside the three
 * data-driven exceptions, all of which are direct {@code RuntimeException} subclasses.
 */
public class AclEpochWiringException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AclEpochWiringException(String message) {
        super(message);
    }
}
