package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

import jp.aegif.nemaki.businesslogic.PrincipalService;

public abstract class AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(AbstractNemakiPatch.class);

	@Autowired
	protected PatchUtil patchUtil;

	@Autowired
	protected PrincipalService principalService;

	/**
	 * What the patch currently running reported as NOT done, if anything.
	 *
	 * <p>Per thread because patches run on the startup thread and nothing runs two at once,
	 * but a ThreadLocal costs nothing and removes the question.
	 */
	private static final ThreadLocal<java.util.List<String>> incomplete = new ThreadLocal<>();

	/**
	 * Says "I returned, but I did not do my work" — without throwing.
	 *
	 * <h2>The hole this closes</h2>
	 *
	 * <p>{@link #apply} writes the patch-history row the moment
	 * {@link #applyPerRepositoryPatch} returns without throwing, and never calls it again. Most
	 * patches in this package catch their own failures and log them, so a patch that failed
	 * entirely is recorded as applied and <b>never runs again</b>: the thing it was supposed to
	 * do never happens, silently, for the life of the deployment (roadmap §2-2, "残り 16 パッチ
	 * の unprepared-return").
	 *
	 * <p>Throwing from every catch was the obvious fix and is why this was held as a breaking
	 * change: some of those catches exist because the failure genuinely is tolerable, and
	 * turning them all into startup errors would stop deployments that have always come up.
	 * This gives a third answer between "threw" and "returned normally" — <b>the history row is
	 * withheld, so the patch is retried on the next start</b>, and the run reports itself
	 * unsuccessful, but startup continues.
	 *
	 * <p>Additive: a patch that never calls this behaves exactly as before.
	 *
	 * @param reason what was not done, in words. It goes in the log; keep it actionable.
	 */
	protected void reportIncomplete(String reason) {
		java.util.List<String> reasons = incomplete.get();
		if (reasons != null) {
			reasons.add(reason);
		}
	}

	public boolean apply(){
		log.info("Applying patch: " + getName());
		applySystemPatch();

		boolean allSucceeded = true;
		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			// Skip archive repositories — patches are only for main repositories
			if (patchUtil.getRepositoryInfoMap().isArchiveRepository(repositoryId)) {
				if (log.isDebugEnabled()) {
					log.debug("[patch=" + getName() + "] Skipping archive repository: " + repositoryId);
				}
				continue;
			}
			if (log.isDebugEnabled()) {
				log.debug("[patch=" + getName() + "] Processing repository: " + repositoryId);
			}
			// One gate for all patches. Most existence checks in this package run through a
			// _design/_repo view, and a view being rebuilt answers 200 with zero rows — so
			// "already exists?" comes back "no" and the patch creates a duplicate with a
			// generated id that no conflict can stop. See PatchUtil.cmisViewsAreAnswering.
			if (!patchUtil.cmisViewsAreAnswering(repositoryId)) {
				log.error("[patch=" + getName() + ", repositoryId=" + repositoryId
						+ "] skipped: the repository's views are not answering, so an existence"
						+ " check cannot be trusted. It will be applied on a later startup.");
				allSucceeded = false;
				continue;
			}
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				if (log.isDebugEnabled()) {
					log.debug("[patch=" + getName() + ", repositoryId=" + repositoryId + "] already applied, skipped");
				}
				continue;
			}else{
				try{
					if (log.isDebugEnabled()) {
						log.debug("[patch=" + getName() + "] Calling applyPerRepositoryPatch for repository: " + repositoryId);
					}
					java.util.List<String> reasons = new java.util.ArrayList<>();
					incomplete.set(reasons);
					try {
						applyPerRepositoryPatch(repositoryId);
					} finally {
						incomplete.remove();
					}

					if (!reasons.isEmpty()) {
						// Returned, but told us it did not finish. Writing the history row here
						// would record it as applied and it would never run again — the exact
						// silent-permanent-skip §2-2 is about.
						log.error("[patch=" + getName() + ", repositoryId=" + repositoryId
								+ "] reported incomplete work; NOT recording it as applied so"
								+ " that it is retried on the next start: " + reasons);
						allSucceeded = false;
						continue;
					}

					patchUtil.createPathHistory(repositoryId, getName());
					if (log.isDebugEnabled()) {
						log.debug("[patch=" + getName() + ", repositoryId=" + repositoryId + "] applied successfully");
					}
				}catch(Exception e){
					log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] failed", e);
					allSucceeded = false;
					// Continue with other repositories even if one fails
				}
			}
		}
		log.info("Patch " + getName() + " completed (success=" + allSucceeded + ")");
		return allSucceeded;
	}
	protected abstract void applySystemPatch();
	protected abstract void applyPerRepositoryPatch(String repositoryId);
	public abstract String getName();

	public void setPatchUtil(PatchUtil patchUtil) {
		log.info("=== setPatchUtil called for " + this.getClass().getSimpleName() + " with " + (patchUtil != null ? patchUtil.getClass().getName() : "null") + " ===");
		this.patchUtil = patchUtil;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

}
