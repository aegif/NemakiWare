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

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CouchContent extends CouchNodeBase{

	private static final Log log = LogFactory.getLog(CouchContent.class);
	private static final long serialVersionUID = -4795093916552322103L;
	private String name;
	private String description;
	private String parentId;
	private CouchAcl acl;
	private Boolean aclInherited;
	private List<Property> subTypeProperties = new ArrayList<Property>();
	private List<Aspect> aspects = new ArrayList<Aspect>();
	private List<String> secondaryIds = new ArrayList<String>();
	private String objectType;
	private String changeToken;

	public CouchContent(){
		super();
		log.info("!!! CouchContent: DEFAULT CONSTRUCTOR called (no @JsonCreator)");
	}

	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchContent(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し
		log.info("!!! CouchContent: @JsonCreator CONSTRUCTOR called with " + (properties != null ? properties.size() : 0) + " properties");

		if (properties != null) {
			// CouchContent固有のフィールドマッピング
			this.name = (String) properties.get("name");
			this.description = (String) properties.get("description");
			this.parentId = (String) properties.get("parentId");
			this.objectType = (String) properties.get("objectType");
			this.changeToken = (String) properties.get("changeToken");
			
			// Boolean型の処理
			if (properties.containsKey("aclInherited")) {
				Object aclInheritedValue = properties.get("aclInherited");
				if (aclInheritedValue instanceof Boolean) {
					this.aclInherited = (Boolean) aclInheritedValue;
				}
			}
			
			// subTypePropertiesの変換
			if (properties.containsKey("subTypeProperties")) {
				Object subTypePropsValue = properties.get("subTypeProperties");
				if (subTypePropsValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> subTypePropsList = (List<Map<String, Object>>) subTypePropsValue;
					List<Property> subTypeProperties = new ArrayList<Property>();
					for (Map<String, Object> propMap : subTypePropsList) {
						String key = (String) propMap.get("key");
						Object value = propMap.get("value");
						if (key != null) {
							subTypeProperties.add(new Property(key, value));
						}
					}
					this.subTypeProperties = subTypeProperties;
				}
			}
			
			// ACL conversion (CRITICAL FIX 2025-11-11: ACL was not being loaded from CouchDB)
			// This is why admin/system permissions were missing - only GROUP_EVERYONE showed
			if (properties.containsKey("acl")) {
				Object aclValue = properties.get("acl");
				if (aclValue instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> aclMap = (Map<String, Object>) aclValue;
					Object entriesValue = aclMap.get("entries");
					if (entriesValue instanceof List) {
						@SuppressWarnings("unchecked")
						List<Map<String, Object>> entriesList = (List<Map<String, Object>>) entriesValue;
						JSONArray entries = new JSONArray();
						for (Map<String, Object> entry : entriesList) {
							JSONObject entryObj = new JSONObject();
							entryObj.put("principal", entry.get("principal"));
							entryObj.put("permissions", entry.get("permissions"));
							entries.add(entryObj);
						}
						CouchAcl couchAcl = new CouchAcl();
						couchAcl.setEntries(entries);
						this.acl = couchAcl;
						log.debug("ACL loaded from CouchDB: " + entries.size() + " ACEs for object");
					}
				}
			}

			// CRITICAL FIX (2025-12-17): aspects conversion - Secondary type properties were not being loaded from CouchDB
			// This is why nemaki:comment and other secondary type properties showed as null after update
			log.info("!!! CouchContent @JsonCreator: checking for aspects field in properties map");
			if (properties.containsKey("aspects")) {
				Object aspectsValue = properties.get("aspects");
				log.info("!!! CouchContent @JsonCreator: aspectsValue type=" + (aspectsValue != null ? aspectsValue.getClass().getName() : "null"));
				if (aspectsValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> aspectsList = (List<Map<String, Object>>) aspectsValue;
					log.info("!!! CouchContent @JsonCreator: aspectsList.size()=" + aspectsList.size());
					List<Aspect> convertedAspects = new ArrayList<Aspect>();
					for (Map<String, Object> aspectMap : aspectsList) {
						String aspectName = (String) aspectMap.get("name");
						log.info("!!! CouchContent @JsonCreator: processing aspect name=" + aspectName + ", aspectMap keys=" + aspectMap.keySet());
						if (aspectName != null) {
							Aspect aspect = new Aspect();
							aspect.setName(aspectName);

							// Convert properties within the aspect
							Object propsValue = aspectMap.get("properties");
							log.info("!!! CouchContent @JsonCreator: aspect " + aspectName + " propsValue type=" + (propsValue != null ? propsValue.getClass().getName() : "null") + ", value=" + propsValue);
							if (propsValue instanceof List) {
								@SuppressWarnings("unchecked")
								List<Map<String, Object>> propsList = (List<Map<String, Object>>) propsValue;
								List<Property> aspectProperties = new ArrayList<Property>();
								for (Map<String, Object> propMap : propsList) {
									String key = (String) propMap.get("key");
									Object value = propMap.get("value");
									log.info("!!! CouchContent @JsonCreator: aspect " + aspectName + " property key=" + key + ", value=" + value);
									if (key != null) {
										aspectProperties.add(new Property(key, value));
									}
								}
								aspect.setProperties(aspectProperties);
								log.info("!!! CouchContent @JsonCreator: aspect " + aspectName + " has " + aspectProperties.size() + " properties");
							}
							convertedAspects.add(aspect);
						}
					}
					this.aspects = convertedAspects;
					log.info("!!! CouchContent @JsonCreator: Loaded " + convertedAspects.size() + " aspects total");
				}
			} else {
				log.info("!!! CouchContent @JsonCreator: NO aspects field in properties map. Keys=" + properties.keySet());
			}

			// CRITICAL FIX (2025-12-17): secondaryIds conversion - Secondary type IDs were not being loaded from CouchDB
			if (properties.containsKey("secondaryIds")) {
				Object secondaryIdsValue = properties.get("secondaryIds");
				if (secondaryIdsValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> convertedSecondaryIds = (List<String>) secondaryIdsValue;
					this.secondaryIds = new ArrayList<String>(convertedSecondaryIds);
					if (log.isDebugEnabled()) {
						log.debug("SecondaryIds loaded from CouchDB: " + convertedSecondaryIds.size() + " IDs");
					}
				}
			}
		}
	}

	public CouchContent(Content c){
		super(c);
		setName(c.getName());
		setDescription(c.getDescription());
		setParentId(c.getParentId());
		setAcl(convertToCouchAcl(c.getAcl()));
		setAclInherited(c.isAclInherited());
		setSubTypeProperties(c.getSubTypeProperties());
		setAspects(c.getAspects());
		setSecondaryIds(c.getSecondaryIds());
		setObjectType(c.getObjectType());
		setChangeToken(c.getChangeToken());

		// COMPREHENSIVE REVISION MANAGEMENT: Preserve revision from Content layer
		setRevision(c.getRevision());
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

	public String getChangeToken() {
		return changeToken;
	}

	public void setChangeToken(String changeToken) {
		this.changeToken = changeToken;
	}

	private CouchAcl convertToCouchAcl(Acl acl){
		List<Ace> localAces = acl.getLocalAces();
		JSONArray entries = new org.json.simple.JSONArray();
		for(Ace ace : localAces){
			JSONObject entry = new JSONObject();
			entry.put("principal", ace.getPrincipalId());
			entry.put("permissions", ace.getPermissions());
			entries.add(entry);
		}
		CouchAcl cacl = new CouchAcl();
		cacl.setEntries(entries);
		return cacl;
	}

	@Override
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
		if (cacl != null) {
			c.setAcl(cacl.convertToNemakiAcl());
		} else {
			// Set default ACL if none exists
			c.setAcl(new jp.aegif.nemaki.model.Acl());
		}

		return c;
	}
}
