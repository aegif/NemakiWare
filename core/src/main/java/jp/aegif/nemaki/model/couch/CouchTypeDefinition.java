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

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = CouchTypeDefinition.class)
public class CouchTypeDefinition extends CouchNodeBase {

	private static final long serialVersionUID = 8066284826946206320L;

	// Attributes Common
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
	
	@JsonProperty("properties")
	private List<String> properties;

	// Attributes specific to Document
	private ContentStreamAllowed contentStreamAllowed;
	private Boolean versionable;

	// Attributes specific to Relationship
	private List<String> allowedSourceTypes;
	private List<String> allowedTargetTypes;

	public CouchTypeDefinition() {
		super();
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchTypeDefinition(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し
		
		if (properties != null) {
			// 文字列フィールドの処理
			this.typeId = (String) properties.get("typeId");
			this.localName = (String) properties.get("localName");
			this.localNameSpace = (String) properties.get("localNameSpace");
			this.queryName = (String) properties.get("queryName");
			this.displayName = (String) properties.get("displayName");
			this.parentId = (String) properties.get("parentId");
			this.description = (String) properties.get("description");
			
			// BaseTypeId列挙型の処理（CouchDB形式からCMIS形式に変換）
			if (properties.containsKey("baseId")) {
				String baseIdStr = (String) properties.get("baseId");
				if (baseIdStr != null) {
					try {
						// CouchDB形式（CMIS_ITEM）からCMIS形式（cmis:item）に変換
						String cmisFormat = convertToNormalizedBaseTypeId(baseIdStr);
						this.baseId = BaseTypeId.fromValue(cmisFormat);
					} catch (Exception e) {
						// フォールバック：元の値を試す
						try {
							this.baseId = BaseTypeId.fromValue(baseIdStr);
						} catch (Exception e2) {
							// 無効な値の場合は無視（ログに記録すべき）
							System.err.println("Warning: Invalid BaseTypeId value: " + baseIdStr);
						}
					}
				}
			}
			
			// Boolean型フィールドの処理
			String[] booleanFields = {
				"creatable", "filable", "queryable", "controllablePolicy", 
				"controllableACL", "fulltextIndexed", "includedInSupertypeQuery",
				"typeMutabilityCreate", "typeMutabilityUpdate", "typeMutabilityDelete",
				"versionable"
			};
			
			for (String field : booleanFields) {
				if (properties.containsKey(field)) {
					Object value = properties.get(field);
					Boolean boolValue = value instanceof Boolean ? (Boolean) value : 
						value != null ? Boolean.parseBoolean(String.valueOf(value)) : null;
					
					// リフレクションを使わず、フィールドごとに設定
					switch (field) {
						case "creatable": this.creatable = boolValue; break;
						case "filable": this.filable = boolValue; break;
						case "queryable": this.queryable = boolValue; break;
						case "controllablePolicy": this.controllablePolicy = boolValue; break;
						case "controllableACL": this.controllableACL = boolValue; break;
						case "fulltextIndexed": this.fulltextIndexed = boolValue; break;
						case "includedInSupertypeQuery": this.includedInSupertypeQuery = boolValue; break;
						case "typeMutabilityCreate": this.typeMutabilityCreate = boolValue; break;
						case "typeMutabilityUpdate": this.typeMutabilityUpdate = boolValue; break;
						case "typeMutabilityDelete": this.typeMutabilityDelete = boolValue; break;
						case "versionable": this.versionable = boolValue; break;
					}
				}
			}
			
			// ContentStreamAllowed列挙型の処理
			if (properties.containsKey("contentStreamAllowed")) {
				String csaStr = (String) properties.get("contentStreamAllowed");
				if (csaStr != null) {
					try {
						this.contentStreamAllowed = ContentStreamAllowed.fromValue(csaStr);
					} catch (Exception e) {
						// 無効な値の場合は無視
					}
				}
			}
			
			// List型フィールドの処理
			if (properties.containsKey("properties")) {
				Object value = properties.get("properties");
				if (value instanceof List) {
					this.properties = (List<String>) value;
				}
			}
			if (properties.containsKey("allowedSourceTypes")) {
				Object value = properties.get("allowedSourceTypes");
				if (value instanceof List) {
					this.allowedSourceTypes = (List<String>) value;
				}
			}
			if (properties.containsKey("allowedTargetTypes")) {
				Object value = properties.get("allowedTargetTypes");
				if (value instanceof List) {
					this.allowedTargetTypes = (List<String>) value;
				}
			}
		}
	}

	public CouchTypeDefinition(NemakiTypeDefinition t) {
		super(t);
		setTypeId(t.getTypeId());
		setLocalName(t.getLocalName());
		setLocalNameSpace(t.getLocalNameSpace());
		setQueryName(t.getQueryName());
		setDisplayName(t.getDisplayName());
		setBaseTypeId(t.getBaseId());
		setParentId(t.getParentId());
		setDescription(t.getDescription());
		setCreatable(t.isCreatable());
		setFilable(t.isFilable());
		setQueryable(t.isQueryable());
		setControllablePolicy(t.isControllablePolicy());
		setControllableACL(t.isControllableACL());
		setFulltextIndexed(t.isFulltextIndexed());
		setIncludedInSupertypeQuery(t.isIncludedInSupertypeQuery());
		setTypeMutabilityCreate(t.isTypeMutabilityCreate());
		setTypeMutabilityUpdate(t.isTypeMutabilityUpdate());
		setTypeMutabilityDelete(t.isTypeMutabilityDelete());
		
		setProperties(t.getProperties());
		
		setContentStreamAllowed(t.getContentStreamAllowed());
		setVersionable(t.isVersionable());
		setAllowedSourceTypes(t.getAllowedSourceTypes());
		setAllowedTargetTypes(t.getAllowedTargetTypes());
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

	public void setBaseTypeId(BaseTypeId baseId) {
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

	public void setBaseId(BaseTypeId baseId) {
		this.baseId = baseId;
	}

	public NemakiTypeDefinition convert() {
		NemakiTypeDefinition t = new NemakiTypeDefinition(super.convert());
		t.setTypeId(getTypeId());
		t.setLocalName(getLocalName());
		t.setLocalNameSpace(getLocalNameSpace());
		t.setQueryName(getQueryName());
		t.setDisplayName(getDisplayName());
		t.setBaseId(getBaseId());
		t.setParentId(getParentId());
		t.setDescription(getDescription());
		t.setCreatable(isCreatable());
		t.setFilable(isFilable());
		t.setQueryable(isQueryable());
		t.setControllablePolicy(isControllablePolicy());
		t.setControllableACL(isControllableACL());
		t.setFulltextIndexed(isFulltextIndexed());
		t.setIncludedInSupertypeQuery(isIncludedInSupertypeQuery());
		t.setTypeMutabilityCreate(isTypeMutabilityCreate());
		t.setTypeMutabilityUpdate(isTypeMutabilityUpdate());
		t.setTypeMutabilityDelete(isTypeMutabilityDelete());
		t.setProperties(getProperties());
		
		t.setContentStreamAllowed(getContentStreamAllowed());
		t.setVersionable(isVersionable());
		t.setAllowedSourceTypes(getAllowedSourceTypes());
		t.setAllowedTargetTypes(getAllowedTargetTypes());

		return t;
	}
	
	/**
	 * CouchDB形式のBaseTypeId（CMIS_ITEM）をCMIS形式（cmis:item）に変換
	 */
	private String convertToNormalizedBaseTypeId(String couchDbFormat) {
		if (couchDbFormat == null) {
			return null;
		}
		
		// CouchDB形式からCMIS形式への変換マッピング
		switch (couchDbFormat.toUpperCase()) {
			case "CMIS_DOCUMENT":
				return "cmis:document";
			case "CMIS_FOLDER":
				return "cmis:folder";
			case "CMIS_ITEM":
				return "cmis:item";
			case "CMIS_RELATIONSHIP":
				return "cmis:relationship";
			case "CMIS_POLICY":
				return "cmis:policy";
			default:
				// 既にCMIS形式の場合はそのまま返す
				if (couchDbFormat.startsWith("cmis:")) {
					return couchDbFormat;
				}
				// 不明な形式の場合は元の値を返す
				return couchDbFormat;
		}
	}
}