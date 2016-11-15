package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.PrincipalService;

public abstract class AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(AbstractNemakiPatch.class);

	protected PatchUtil patchUtil;
	protected PrincipalService principalService;


	public void apply(){
		applySystemPatch();

		for(String repositoryId : patchUtil.getRepositoryInfoMap().keys()){
			boolean isApplied = patchUtil.isApplied(repositoryId, getName());
			if(isApplied){
				log.info("[patch=" + getName() + ", repositoryId=" + repositoryId + "]" +  "already applied, skipped");
				continue;
			}else{
				try{
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
		this.patchUtil = patchUtil;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

}
