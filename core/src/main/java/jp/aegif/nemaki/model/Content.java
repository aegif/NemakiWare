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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
	private String changeToken;
	private List<String> renditionIds;

	// ===== ACL-epoch outbox carrier (design §11.1, increment 12 step 0) =====
	// The DAO update path builds a FRESH CouchDocument from this model object, so any stored
	// field the model does not carry is ERASED by an ordinary rename/property update. These
	// carriers exist ONLY so that unrelated updates stop being destructive: values are read
	// back VERBATIM and written back UNTOUCHED. Nothing here is ever minted, defaulted or
	// interpreted — a document that has no epoch fields keeps having none (the four keys are
	// aclEpochState / aclEpochMutationId / aclSourceEpoch / aclEpochQuarantined, presence-
	// faithful including an explicit null value). contentIncarnation is the ONE exception
	// with assign-once semantics: CouchContent mints it when ABSENT (create / legacy lazy
	// fill, design §8.1) and preserves it verbatim when present.
	private java.util.Map<String, Object> aclEpochFields;
	private String contentIncarnation;

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
		// Copy-constructor chains (Document(Content) etc. call super(c)) must not drop the
		// verbatim carriers — dropping them here is exactly the erasure §11.1 closes.
		if (n instanceof Content) {
			Content c = (Content) n;
			if (c.getAclEpochFields() != null) {
				this.aclEpochFields = new java.util.LinkedHashMap<>(c.getAclEpochFields());
			}
			this.contentIncarnation = c.getContentIncarnation();
		}
		
		// COMPREHENSIVE REVISION MANAGEMENT: Preserve revision from NodeBase
		setRevision(n.getRevision());
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

	public String getChangeToken() {
		return changeToken;
	}

	public void setChangeToken(String changeToken) {
		this.changeToken = changeToken;
	}

	public List<String> getRenditionIds() {
		return renditionIds;
	}

	public void setRenditionIds(List<String> renditionIds) {
		this.renditionIds = renditionIds;
	}

	@Override
	public String toString() {
		Map<String, Object> m = new HashMap<String, Object>();
		m.put("id", getId());
		m.put("name", getName());
		m.put("type", getType());
		m.put("creator", getCreator());
		m.put("created", getCreated());
		m.put("modifier", getModifier());
		m.put("modified", getModified());
		m.put("parentId", getParentId());
		m.put("aspects", getAspects().toString());
		return m.toString();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || !(obj instanceof Content)) {
			return false;
		}
		String thisId = this.getId();
		String otherId = ((Content) obj).getId();
		if (thisId == null || otherId == null) {
			return false;
		}
		return thisId.equals(otherId);
	}

	@Override
	public int hashCode() {
		String id = this.getId();
		return id != null ? id.hashCode() : System.identityHashCode(this);
	}

	// ===== ACL-epoch outbox carrier accessors (§11.1) =====

	/** The verbatim epoch-field carrier; {@code null} when the stored document has none. */
	public java.util.Map<String, Object> getAclEpochFields() {
		return aclEpochFields;
	}

	public void setAclEpochFields(java.util.Map<String, Object> aclEpochFields) {
		this.aclEpochFields = aclEpochFields;
	}

	/** Phase-1 write helper: record one epoch field for the NEXT persist of this object. */
	public void putAclEpochField(String key, Object value) {
		if (this.aclEpochFields == null) {
			this.aclEpochFields = new java.util.LinkedHashMap<>();
		}
		this.aclEpochFields.put(key, value);
	}

	/** Lenient typed reader: the value when it is a String, else {@code null} (corruption is the scanner's job). */
	public String aclEpochFieldAsString(String key) {
		Object v = aclEpochFields == null ? null : aclEpochFields.get(key);
		return (v instanceof String) ? (String) v : null;
	}

	/** Lenient typed reader: the value when it is an integral Number, else {@code null}. */
	public Long aclEpochFieldAsLong(String key) {
		Object v = aclEpochFields == null ? null : aclEpochFields.get(key);
		if (!(v instanceof Number)) {
			return null;
		}
		try {
			return new java.math.BigDecimal(v.toString()).longValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}

	public String getContentIncarnation() {
		return contentIncarnation;
	}

	public void setContentIncarnation(String contentIncarnation) {
		this.contentIncarnation = contentIncarnation;
	}
}
