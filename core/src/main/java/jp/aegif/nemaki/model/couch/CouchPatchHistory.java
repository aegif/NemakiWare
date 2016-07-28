package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.PatchHistory;

public class CouchPatchHistory extends CouchNodeBase{
	private String name;
	private Boolean applied;
	
	public CouchPatchHistory(){
		super();
	}

	public CouchPatchHistory(PatchHistory patchHistory){
		super(patchHistory);
		setName(patchHistory.getName());
		setIsApplied(patchHistory.isApplied());
	}
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public Boolean isApplied() {
		return applied;
	}
	public void setIsApplied(Boolean applied) {
		this.applied = applied;
	}
	
	@Override
	public PatchHistory convert(){
		PatchHistory patchHistory = new PatchHistory(super.convert());
		patchHistory.setName(getName());
		patchHistory.setIsApplied(isApplied());
		
		return patchHistory;
	}
	
}
