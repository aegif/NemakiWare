package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.Relationship;

public class CouchRelationship extends CouchContent {
	private static final long serialVersionUID = 9105018834841974510L;
	
	private String sourceId;
	private String targetId;
	
	public CouchRelationship(){
		super();
	}
	
	public CouchRelationship(Relationship r){
		super(r);
		setSourceId(r.getSourceId());
		setTargetId(r.getTargetId());
	}
	
	public String getSourceId() {
		return sourceId;
	}
	public void setSourceId(String sourceId) {
		this.sourceId = sourceId;
	}
	public String getTargetId() {
		return targetId;
	}
	public void setTargetId(String targetId) {
		this.targetId = targetId;
	}
	
	public Relationship convert(){
		Relationship r = new Relationship(super.convert());
		r.setSourceId(getSourceId());
		r.setTargetId(getTargetId());
		return r;
	}
}
