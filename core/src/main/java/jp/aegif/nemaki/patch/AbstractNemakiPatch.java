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
	 * Apply this patch to all repositories.
	 * @return true if the patch succeeded for all repositories, false if any failed
	 */
	public boolean apply(){
		log.info("=== AbstractNemakiPatch.apply() called for patch: " + getName() + " ===");
		applySystemPatch();

		boolean allSucceeded = true;
		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			log.info("Processing repository: " + repositoryId + " for patch: " + getName());
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				log.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "] already applied, skipped");
				continue;
			}else{
				try{
					log.info("Calling applyPerRepositoryPatch for repository: " + repositoryId + ", patch: " + getName());
					applyPerRepositoryPatch(repositoryId);

					patchUtil.createPathHistory(repositoryId, getName());
					log.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "] applied successfully");
				}catch(Exception e){
					log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] failed", e);
					allSucceeded = false;
					// Continue with other repositories even if one fails
				}
			}
		}
		log.info("=== AbstractNemakiPatch.apply() completed for patch: " + getName() + " (success=" + allSucceeded + ") ===");
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
