package jp.aegif.nemaki.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.businesslogic.PrincipalService;

public abstract class AbstractNemakiPatch {
	private static Logger logger = LoggerFactory.getLogger(AbstractNemakiPatch.class);
	protected PatchUtil patchUtil;
	protected PrincipalService principalService;


	public void apply(){
		applySystemPatch();

		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				logger.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "already applied, skipped");
				continue;
			}else{
				try{
					applyPerRepositoryPatch(repositoryId);

					patchUtil.createPathHistory(repositoryId, getName());
					logger.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "applied");
				}catch(Exception e){
					logger.error("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "failed", e);
				}
			}
		}
	}
	protected abstract void applySystemPatch();
	protected abstract void applyPerRepositoryPatch(String repositoryId);
	public abstract String getName();

	public void setPatchUtil(PatchUtil patchUtil) {
		this.patchUtil = patchUtil;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

}
