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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jp.aegif.nemaki.util.constant.NodeType;

@JsonIgnoreProperties(ignoreUnknown=true)
public class NodeBase{

	protected String id;
	protected String type;
	protected GregorianCalendar created;
	protected String creator;
	protected GregorianCalendar modified;
	protected String modifier;
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}
	
	public GregorianCalendar getCreated() {
		return created;
	}

	public void setCreated(GregorianCalendar created) {
		this.created = created;
	}

	public String getCreator() {
		return creator;
	}

	public void setCreator(String creator) {
		this.creator = creator;
	}

	public GregorianCalendar getModified() {
		return modified;
	}

	public void setModified(GregorianCalendar modified) {
		this.modified = modified;
	}

	public String getModifier() {
		return modifier;
	}

	public void setModifier(String modifier) {
		this.modifier = modifier;
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
	
	public Boolean isItem(){
		if (NodeType.CMIS_ITEM.value().equals(type)) {
			return true;
		} else {
			return false;
		}
	}
	
	public Boolean isAttachment(){
		return (NodeType.ATTACHMENT.value().equals(type)) ? true : false;
	}
	
	public Boolean isTypeDefinition(){
		return (NodeType.TYPE_DEFINITION.value().equals(type)) ? true : false;
	}
	
	public Boolean isPropertyDefinitionCore(){
		return (NodeType.PROPERTY_DEFINITION_CORE.value().equals(type)) ? true : false;
	}
	
	public Boolean isPropertyDefinitionDetail(){
		return (NodeType.PROPERTY_DEFINITION_DETAIL.value().equals(type)) ? true : false;
	}
	
	public Boolean isUser(){
		return (NodeType.USER.value().equals(type)) ? true : false;
	}
	
	public Boolean isGroup(){
		return (NodeType.GROUP.value().equals(type)) ? true : false;
	}
}
