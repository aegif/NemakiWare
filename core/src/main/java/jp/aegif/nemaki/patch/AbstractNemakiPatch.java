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
					applyPerRepositoryPatch(repositoryId);

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
