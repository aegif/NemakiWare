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


	public void apply(){
		log.error("=== AbstractNemakiPatch.apply() called for patch: " + getName() + " ===");
		applySystemPatch();

		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			log.error("Processing repository: " + repositoryId + " for patch: " + getName());
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] already applied, skipped");
				continue;
			}else{
				try{
					log.error("Calling applyPerRepositoryPatch for repository: " + repositoryId + ", patch: " + getName());
					applyPerRepositoryPatch(repositoryId);

					patchUtil.createPathHistory(repositoryId, getName());
					log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] applied successfully");
				}catch(Exception e){
					log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "] failed", e);
				}
			}
		}
		log.error("=== AbstractNemakiPatch.apply() completed for patch: " + getName() + " ===");
	}
	protected abstract void applySystemPatch();
	protected abstract void applyPerRepositoryPatch(String repositoryId);
	public abstract String getName();

	public void setPatchUtil(PatchUtil patchUtil) {
		log.error("=== setPatchUtil called for " + this.getClass().getSimpleName() + " with " + (patchUtil != null ? patchUtil.getClass().getName() : "null") + " ===");
		this.patchUtil = patchUtil;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

}
