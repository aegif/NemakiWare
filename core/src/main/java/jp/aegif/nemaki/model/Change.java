/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model;

import java.util.GregorianCalendar;
import java.util.List;

import jp.aegif.nemaki.util.constant.NodeType;

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
	private String token;
	private Long createdInMillis;
	private ChangeType changeType;
	private GregorianCalendar time;
	
	public Change() {
		super();
		setType(NodeType.CHANGE.value());
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
	
	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
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
	
	public Long getCreatedInMillis() {
		return createdInMillis;
	}

	public void setCreatedInMillis(Long createdInMillis) {
		this.createdInMillis = createdInMillis;
	}

	public boolean isOnDocument(){
		return baseType.equals(NodeType.CMIS_DOCUMENT.value()) ? true : false;
	}
}
