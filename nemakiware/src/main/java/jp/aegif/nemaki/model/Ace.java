package jp.aegif.nemaki.model;

import java.util.List;

public class Ace {
	private String principalId;
	private List<String> permissions;
	private boolean objectOnly;
	
	public Ace(){
		
	}
	
	public Ace(String principalId, List<String> permissions, boolean objectOnly) {
		super();
		this.principalId = principalId;
		this.permissions = permissions;
		this.objectOnly = objectOnly;
	}

	public String getPrincipalId() {
		return principalId;
	}
	public void setPrincipalId(String principalId) {
		this.principalId = principalId;
	}
	public List<String> getPermissions() {
		return permissions;
	}
	public void setPermissions(List<String> permissions) {
		this.permissions = permissions;
	}

	public boolean isObjectOnly() {
		return objectOnly;
	}

	public void setObjectOnly(boolean objectOnly) {
		this.objectOnly = objectOnly;
	}
}
