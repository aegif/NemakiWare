package jp.aegif.nemaki.model.couch;

import java.util.List;

import jp.aegif.nemaki.model.Policy;

public class CouchPolicy extends CouchContent{

	private static final long serialVersionUID = -3126374299666885813L;
	
	private String policyText;
	private List<String> appliedIds;
	
	public CouchPolicy(){
		super();
	}
	
	public CouchPolicy(Policy p){
		super(p);
		setPolicyText(p.getPolicyText());
	}
	
	public String getPolicyText() {
		return policyText;
	}

	public void setPolicyText(String policyText) {
		this.policyText = policyText;
	}
	
	public List<String> getAppliedIds() {
		return appliedIds;
	}

	public void setAppliedIds(List<String> appliedIds) {
		this.appliedIds = appliedIds;
	}

	public Policy convert(){
		Policy p = new Policy(super.convert());
		p.setPolicyText(getPolicyText());
		p.setAppliedIds(getAppliedIds());
		return p;
	}
}
