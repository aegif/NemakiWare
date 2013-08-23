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
/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.constant.NodeType;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * Root class for all CMIS object in NemakiWare.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Content extends NodeBase {

	private String name;
	private String description;
	private String parentId; // Pure CMIS demands this only for Folder
	private Acl acl;
	private Boolean aclInherited;
	private List<Property> subTypeProperties = new ArrayList<Property>();
	private List<Aspect> aspects = new ArrayList<Aspect>();
	private List<String> secondaryIds = new ArrayList<String>();
	private String objectType;
	private int changeToken;

	public Content() {
		super();
	}

	public Content(NodeBase n) {
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	/*
	 * Getters/Setters
	 */
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	public Acl getAcl() {
		return acl;
	}

	public void setAcl(Acl acl) {
		this.acl = acl;
	}
	
	public Boolean isAclInherited() {
		return aclInherited;
	}

	public void setAclInherited(Boolean aclInherited) {
		this.aclInherited = aclInherited;
	}
	
	public List<Property> getSubTypeProperties() {
		return subTypeProperties;
	}

	public void setSubTypeProperties(List<Property> subTypeProperties) {
		this.subTypeProperties = subTypeProperties;
	}

	public List<Aspect> getAspects() {
		return aspects;
	}

	public void setAspects(List<Aspect> aspects) {
		this.aspects = aspects;
	}

	public List<String> getSecondaryIds() {
		return secondaryIds;
	}

	public void setSecondaryIds(List<String> secondaryIds) {
		this.secondaryIds = secondaryIds;
	}
	
	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}

	public int getChangeToken() {
		return changeToken;
	}

	public void setChangeToken(int changeToken) {
		this.changeToken = changeToken;
	}

	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("name", getName());
				put("type", getType());
				put("creator", getCreator());
				put("created", getCreated());
				put("modifier", getModifier());
				put("modified", getModified());
				put("parentId", getParentId());
				put("aspects", getAspects().toString());
			}
		};
		return m.toString();
	}

	@Override
	public boolean equals(Object obj) {
		return obj != null && obj instanceof Content
				&& ((Content) obj).getId().equals(this.getId());
	}

	@Override
	public int hashCode() {
		return this.getId().hashCode();
	}

	public Boolean isFolder() {
		if (NodeType.CMIS_FOLDER.value().equals(type)) {
			return true;
		} else {
			return false;
		}
	}

	public Boolean isDocument() {
		if (NodeType.CMIS_DOCUMENT.value().equals(type)) {
			return true;
		} else {
			return false;
		}
	}
	
	public Boolean isRelationship() {
		if (NodeType.CMIS_RELATIONSHIP.value().equals(type)) {
			return true;
		} else {
			return false;
		}
	}
	
	public Boolean isPolicy() {
		if (NodeType.CMIS_POLICY.value().equals(type)) {
			return true;
		} else {
			return false;
		}
	}
	
	public Boolean isRoot(){
		return (getId().equals("/")) ? true : false;
	}
}
