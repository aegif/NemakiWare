package jp.aegif.nemaki.model;

import jp.aegif.nemaki.util.constant.NodeType;

public class PatchHistory extends NodeBase{
	private String name;
	private boolean applied;
	
	public PatchHistory(){
		this.type = NodeType.PATCH.value();
	}

	public PatchHistory(NodeBase n){
		this();
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public PatchHistory(String name, boolean applied){
		this();
		this.name = name;
		this.applied = applied;
	}
	
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public boolean isApplied() {
		return applied;
	}

	public void setIsApplied(boolean applied) {
		this.applied = applied;
	}
}
