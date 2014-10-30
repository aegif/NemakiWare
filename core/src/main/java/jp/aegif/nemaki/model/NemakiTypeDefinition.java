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

import java.util.List;

import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

public class NemakiTypeDefinition extends NodeBase {
	//Attributes Common
	private String typeId;
	private String localName;
	private String localNameSpace;
	private String queryName;
	private String displayName;
	private BaseTypeId baseId;
	private String parentId;
	private String description;
	private Boolean creatable;
	private Boolean filable;
	private Boolean queryable;
	private Boolean controllablePolicy;
	private Boolean controllableACL;
	private Boolean fulltextIndexed;
	private Boolean includedInSupertypeQuery;
	private Boolean typeMutabilityCreate;
	private Boolean typeMutabilityUpdate;
	private Boolean typeMutabilityDelete;
	private List<String> properties;
	
	//Attributes specific to Document
	private ContentStreamAllowed contentStreamAllowed;
	private Boolean versionable;
	
	//Attributes specific to Relationship
	private List<String> allowedSourceTypes;
	private List<String> allowedTargetTypes;
	
	public NemakiTypeDefinition(){
		super();
		setType(NodeType.TYPE_DEFINITION.value());
	}
	
	public NemakiTypeDefinition(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	/**
	 * Getter & Setter
	 */
	public String getTypeId() {
		return typeId;
	}

	public void setTypeId(String typeId) {
		this.typeId = typeId;
	}

	public String getLocalName() {
		return localName;
	}

	public void setLocalName(String localName) {
		this.localName = localName;
	}

	public String getLocalNameSpace() {
		return localNameSpace;
	}

	public void setLocalNameSpace(String localNameSpace) {
		this.localNameSpace = localNameSpace;
	}

	public String getQueryName() {
		return queryName;
	}

	public void setQueryName(String queryName) {
		this.queryName = queryName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public BaseTypeId getBaseId() {
		return baseId;
	}

	public void setBaseId(BaseTypeId baseId) {
		this.baseId = baseId;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Boolean isCreatable() {
		return creatable;
	}

	public void setCreatable(Boolean creatable) {
		this.creatable = creatable;
	}

	public Boolean isFilable() {
		return filable;
	}

	public void setFilable(Boolean filable) {
		this.filable = filable;
	}

	public Boolean isQueryable() {
		return queryable;
	}

	public void setQueryable(Boolean queryable) {
		this.queryable = queryable;
	}

	public Boolean isControllablePolicy() {
		return controllablePolicy;
	}

	public void setControllablePolicy(Boolean controllablePolicy) {
		this.controllablePolicy = controllablePolicy;
	}

	public Boolean isControllableACL() {
		return controllableACL;
	}

	public void setControllableACL(Boolean controllableACL) {
		this.controllableACL = controllableACL;
	}

	public Boolean isFulltextIndexed() {
		return fulltextIndexed;
	}

	public void setFulltextIndexed(Boolean fulltextIndexed) {
		this.fulltextIndexed = fulltextIndexed;
	}

	public Boolean isIncludedInSupertypeQuery() {
		return includedInSupertypeQuery;
	}

	public void setIncludedInSupertypeQuery(Boolean includedInSupertypeQuery) {
		this.includedInSupertypeQuery = includedInSupertypeQuery;
	}

	public Boolean isTypeMutabilityCreate() {
		return typeMutabilityCreate;
	}

	public void setTypeMutabilityCreate(Boolean typeMutabilityCreate) {
		this.typeMutabilityCreate = typeMutabilityCreate;
	}

	public Boolean isTypeMutabilityUpdate() {
		return typeMutabilityUpdate;
	}

	public void setTypeMutabilityUpdate(Boolean typeMutabilityUpdate) {
		this.typeMutabilityUpdate = typeMutabilityUpdate;
	}

	public Boolean isTypeMutabilityDelete() {
		return typeMutabilityDelete;
	}

	public void setTypeMutabilityDelete(Boolean typeMutabilityDelete) {
		this.typeMutabilityDelete = typeMutabilityDelete;
	}

	public List<String> getProperties() {
		return properties;
	}

	public void setProperties(List<String> properties) {
		this.properties = properties;
	}

	public ContentStreamAllowed getContentStreamAllowed() {
		return contentStreamAllowed;
	}

	public void setContentStreamAllowed(ContentStreamAllowed contentStreamAllowed) {
		this.contentStreamAllowed = contentStreamAllowed;
	}

	public Boolean isVersionable() {
		return versionable;
	}

	public void setVersionable(Boolean versionable) {
		this.versionable = versionable;
	}

	public List<String> getAllowedSourceTypes() {
		return allowedSourceTypes;
	}

	public void setAllowedSourceTypes(List<String> allowedSourceTypes) {
		this.allowedSourceTypes = allowedSourceTypes;
	}

	public List<String> getAllowedTargetTypes() {
		return allowedTargetTypes;
	}

	public void setAllowedTargetTypes(List<String> allowedTargetTypes) {
		this.allowedTargetTypes = allowedTargetTypes;
	}
}