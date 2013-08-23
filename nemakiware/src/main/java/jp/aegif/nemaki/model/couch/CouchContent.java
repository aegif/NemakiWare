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
package jp.aegif.nemaki.model.couch;

import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchContent extends CouchNodeBase{

	private static final long serialVersionUID = -4795093916552322103L;
	private String name;
	private String description;
	private String parentId;
	private CouchAcl acl;
	private Boolean aclInherited = true;	//TODO Deault to false. Is this place adequate?
	private List<Property> subTypeProperties = new ArrayList<Property>();
	private List<Aspect> aspects = new ArrayList<Aspect>();
	private List<String> secondaryIds = new ArrayList<String>();
	private String objectType;
	private int changeToken;

	public CouchContent(){
		super();
	}
	
	public CouchContent(Content c){
		super(c);
		setName(c.getName());
		setDescription(c.getDescription());
		setParentId(c.getParentId());
		setAcl(convertToCouchAcl(c.getAcl()));
		setAclInherited(c.isAclInherited());
		setAspects(c.getAspects());
		setSecondaryIds(c.getSecondaryIds());
		setObjectType(c.getObjectType());
		setChangeToken(c.getChangeToken());
	}
	
	/**
	 * Getter & Setter
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


	public CouchAcl getAcl() {
		return acl;
	}

	public void setAcl(CouchAcl acl) {
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

	private CouchAcl convertToCouchAcl(Acl acl){
		List<Ace> localAces = acl.getLocalAces();
		JSONArray entries = new org.json.simple.JSONArray();
		for(Ace ace : localAces){
			JSONObject entry = new JSONObject();
			entry.put("principal", ace.getPrincipalId());
			entry.put("permissions", ace.getPermissions());
			entry.put("objectOnly", ace.isObjectOnly());
			entries.add(entry);
		}
		CouchAcl cacl = new CouchAcl();
		cacl.setEntries(entries);
		return cacl;
	}
	
	public Content convert(){
		Content c = new Content(super.convert());
		c.setName(getName());
		c.setDescription(getDescription());
		c.setParentId(getParentId());
		c.setAclInherited(isAclInherited());
		c.setSubTypeProperties(getSubTypeProperties());
		c.setAspects(getAspects());
		c.setSecondaryIds(getSecondaryIds());
		c.setObjectType(getObjectType());
		c.setChangeToken(getChangeToken());
		
		CouchAcl cacl = getAcl();
		c.setAcl(cacl.convertToNemakiAcl());
		
		return c;
	}
}
