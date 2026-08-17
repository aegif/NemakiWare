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
	/**
	 * The CONTENT axis' identity (design §8.1, wiring gate 3). Declared HERE, on the shared Couch
	 * content base, so every content type — document, folder, relationship, policy, item — persists
	 * it in the SAME CouchDB commit that creates it. Assigning it per-DAO-create method would have
	 * been five places to keep in step, and one missed would leave a whole type permanently
	 * incarnation-less.
	 *
	 * <p>Persisted at creation and NEVER Solr-first: a Solr-only value would let two concurrent
	 * writers pick different UUIDs and clobber each other for ever. Pre-migration content acquires
	 * one via {@code Patch_ContentIncarnationBackfill} or lazily via
	 * {@code ContentIncarnation.resolve}, whichever wins the _rev CAS.
	 */
	private String contentIncarnation;

	/**
	 * Verbatim carrier for the ACL-epoch outbox fields (§11.1). NOT a bean property — it must
	 * never serialize as a nested {@code aclEpochFields} object; emission goes through the
	 * {@code @JsonAnyGetter} map so each key lands at the TOP LEVEL of the stored document,
	 * presence-faithfully (an explicit null is emitted as an explicit null).
	 */
	@com.fasterxml.jackson.annotation.JsonIgnore
	private java.util.Map<String, Object> aclEpochFields;

	private static final String[] ACL_EPOCH_CARRIER_KEYS = {
			jp.aegif.nemaki.epoch.AclEpochState.FIELD_STATE,
			jp.aegif.nemaki.epoch.AclEpochState.FIELD_MUTATION_ID,
			jp.aegif.nemaki.epoch.AclEpochState.FIELD_SOURCE_EPOCH,
			jp.aegif.nemaki.epoch.AclEpochState.FIELD_QUARANTINED };

	private static java.util.Map<String, Object> readAclEpochFieldsVerbatim(java.util.Map<String, Object> properties) {
		java.util.Map<String, Object> out = null;
		for (String key : ACL_EPOCH_CARRIER_KEYS) {
			if (properties.containsKey(key)) {
				if (out == null) {
					out = new java.util.LinkedHashMap<>();
				}
				out.put(key, properties.get(key)); // verbatim: whatever shape is stored, including null
			}
		}
		return out;
	}
	private String changeToken;

	public CouchContent(){
		super();
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchContent(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し
		
		if (properties != null) {
			// CouchContent固有のフィールドマッピング
			this.name = (String) properties.get("name");
			this.description = (String) properties.get("description");
			this.parentId = (String) properties.get("parentId");
			this.objectType = (String) properties.get("objectType");
			// Read back verbatim; the creator must never MINT one, or every read of a
			// pre-migration document would look like an assignment that was never persisted.
			Object ci = properties.get(jp.aegif.nemaki.epoch.ContentIncarnation.FIELD);
			this.contentIncarnation = (ci instanceof String) ? (String) ci : null;
			// ACL-epoch outbox fields (§11.1): captured PRESENCE-FAITHFULLY and verbatim —
			// an explicit null is PRESENT (the 2e containsKey contract) and must survive the
			// round-trip as an explicit null, not degrade to absent.
			this.aclEpochFields = readAclEpochFieldsVerbatim(properties);
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
			if (properties.containsKey("aspects")) {
				Object aspectsValue = properties.get("aspects");
				if (aspectsValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> aspectsList = (List<Map<String, Object>>) aspectsValue;
					List<Aspect> convertedAspects = new ArrayList<Aspect>();
					for (Map<String, Object> aspectMap : aspectsList) {
						String aspectName = (String) aspectMap.get("name");
						if (aspectName != null) {
							Aspect aspect = new Aspect();
							aspect.setName(aspectName);

							// Convert properties within the aspect
							Object propsValue = aspectMap.get("properties");
							if (propsValue instanceof List) {
								@SuppressWarnings("unchecked")
								List<Map<String, Object>> propsList = (List<Map<String, Object>>) propsValue;
								List<Property> aspectProperties = new ArrayList<Property>();
								for (Map<String, Object> propMap : propsList) {
									String key = (String) propMap.get("key");
									Object value = propMap.get("value");
									if (key != null) {
										aspectProperties.add(new Property(key, value));
									}
								}
								aspect.setProperties(aspectProperties);
							}
							convertedAspects.add(aspect);
						}
					}
					this.aspects = convertedAspects;
					if (log.isDebugEnabled()) {
						log.debug("Aspects loaded from CouchDB: " + convertedAspects.size() + " aspects");
					}
				}
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

		// §11.1: the model round-trip used to LOSE contentIncarnation (convert() never copied it
		// and the model had no field), so the mint below fired on EVERY update — each ordinary
		// rename silently started a new "lifetime" and the content fence treated it as a restore.
		// Copy the model's value FIRST; the mint then fires only when the model genuinely has
		// none: a CREATE (same commit, design §8.1) or the legacy lazy fill.
		this.contentIncarnation = c.getContentIncarnation();
		if (this.contentIncarnation == null || this.contentIncarnation.isBlank()) {
			this.contentIncarnation = jp.aegif.nemaki.epoch.ContentIncarnation.mint();
		}

		// ACL-epoch outbox fields (§11.1): verbatim, and NEVER minted — absent stays absent.
		// Emission is routed through the @JsonAnyGetter map so presence (including an explicit
		// null marker) survives; a bean property could not express present-null.
		if (c.getAclEpochFields() != null && !c.getAclEpochFields().isEmpty()) {
			this.aclEpochFields = new java.util.LinkedHashMap<>(c.getAclEpochFields());
			getAdditionalProperties().putAll(this.aclEpochFields);
		}

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

	@com.fasterxml.jackson.annotation.JsonProperty(jp.aegif.nemaki.epoch.ContentIncarnation.FIELD)
	public String getContentIncarnation() { return contentIncarnation; }

	@com.fasterxml.jackson.annotation.JsonProperty(jp.aegif.nemaki.epoch.ContentIncarnation.FIELD)
	public void setContentIncarnation(String contentIncarnation) {
		this.contentIncarnation = contentIncarnation;
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

		// §11.1 carriers: without these two copies the model is blind to the stored values and
		// the next update erases them (epoch fields) or re-mints them (contentIncarnation).
		c.setContentIncarnation(getContentIncarnation());
		if (aclEpochFields != null) {
			c.setAclEpochFields(new java.util.LinkedHashMap<>(aclEpochFields));
		}

		return c;
	}
}
