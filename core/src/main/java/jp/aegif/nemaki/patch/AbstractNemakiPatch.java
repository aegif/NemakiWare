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
		if (log.isDebugEnabled()) {
			log.debug("AbstractNemakiPatch.apply() called");
		}
		log.info("AbstractNemakiPatch.apply() called");
		applySystemPatch();

		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			if (log.isDebugEnabled()) {
				log.debug("Processing repository: " + repositoryId);
			}
			log.info("Processing repository: " + repositoryId);
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				log.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "already applied, skipped");
				continue;
			}else{
				try{
					if (log.isDebugEnabled()) {
						log.debug("Calling applyPerRepositoryPatch for: " + repositoryId);
					}
					log.info("Calling applyPerRepositoryPatch for: " + repositoryId);
					applyPerRepositoryPatch(repositoryId);

					patchUtil.createPathHistory(repositoryId, getName());
					log.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "applied");
				}catch(Exception e){
					log.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "failed", e);
				}
			}
		}
	}
	protected abstract void applySystemPatch();
	protected abstract void applyPerRepositoryPatch(String repositoryId);
	public abstract String getName();

	public void setPatchUtil(PatchUtil patchUtil) {
		if (log.isDebugEnabled()) {
			log.debug("setPatchUtil called with " + (patchUtil != null ? patchUtil.getClass().getName() : "null"));
		}
		log.info("setPatchUtil called with " + (patchUtil != null ? patchUtil.getClass().getName() : "null"));
		this.patchUtil = patchUtil;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

}
