package jp.aegif.nemaki.model;

import java.util.GregorianCalendar;
import java.util.List;

import jp.aegif.nemaki.model.constant.NodeType;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;

public class Change extends NodeBase{

	private String name;
	private String baseType;
	private String objectType;
	private String versionSeriesId;
	private String versionLabel;
	private List<String> policyIds;
	private Acl acl;
	private String parentId;
	
	private String objectId;
	private int changeToken;
	private ChangeType changeType;
	private GregorianCalendar time;
	private boolean latest;

	
	public Change() {
		super();
	}

	public Change(NodeBase n) {
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public String getParentId() {
		return parentId;
	}
	public void setParentId(String parentId) {
		this.parentId = parentId;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getBaseType() {
		return baseType;
	}
	public void setBaseType(String baseType) {
		this.baseType = baseType;
	}
	public String getObjectType() {
		return objectType;
	}
	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}
	public String getVersionSeriesId() {
		return versionSeriesId;
	}
	public void setVersionSeriesId(String versionSeriesId) {
		this.versionSeriesId = versionSeriesId;
	}
	public String getVersionLabel() {
		return versionLabel;
	}
	public void setVersionLabel(String versionLabel) {
		this.versionLabel = versionLabel;
	}
	public List<String> getPolicyIds() {
		return policyIds;
	}
	public void setPolicyIds(List<String> policyIds) {
		this.policyIds = policyIds;
	}
	public Acl getAcl() {
		return acl;
	}
	public void setAcl(Acl acl) {
		this.acl = acl;
	}
	public String getObjectId() {
		return objectId;
	}
	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}
	
	
	public int getChangeToken() {
		return changeToken;
	}
	public void setChangeToken(int changeToken) {
		this.changeToken = changeToken;
	}
	public ChangeType getChangeType() {
		return changeType;
	}
	public void setChangeType(ChangeType type) {
		this.changeType = type;
	}
	public GregorianCalendar getTime() {
		return time;
	}
	public void setTime(GregorianCalendar time) {
		this.time = time;
	}
	public boolean isLatest() {
		return latest;
	}
	public void setLatest(boolean latest) {
		this.latest = latest;
	}
	
	public boolean isDocument(){
		return baseType.equals(NodeType.CMIS_DOCUMENT.value()) ? true : false;
	}
}
