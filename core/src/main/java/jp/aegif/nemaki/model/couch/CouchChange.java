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

import java.util.GregorianCalendar;
import java.util.List;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Change;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;

public class CouchChange extends CouchNodeBase implements Comparable<CouchChange>{
	/**
	 *
	 */
	private static final long serialVersionUID = 3016760183200314355L;

	private static final Log log = LogFactory
			.getLog(CouchChange.class);

	private String name;
	private String baseType;
	private String objectType;
	private String versionSeriesId;
	private String versionLabel;
	private List<String> policyIds;
	private Acl acl;
	private String paretnId;

	private String objectId;
	private Long token;
	private ChangeType changeType;
	private GregorianCalendar time;

	
	public CouchChange(){
		super();
	}
	
	// Map-based constructor for Cloudant Document conversion
	@JsonCreator
	public CouchChange(Map<String, Object> properties) {
		super(properties);
		if (properties != null) {
			// Handle CouchChange-specific fields
			if (properties.containsKey("name")) {
				this.name = (String) properties.get("name");
			}
			if (properties.containsKey("baseType")) {
				this.baseType = (String) properties.get("baseType");
			}
			if (properties.containsKey("objectType")) {
				this.objectType = (String) properties.get("objectType");
			}
			if (properties.containsKey("versionSeriesId")) {
				this.versionSeriesId = (String) properties.get("versionSeriesId");
			}
			if (properties.containsKey("versionLabel")) {
				this.versionLabel = (String) properties.get("versionLabel");
			}
			if (properties.containsKey("objectId")) {
				this.objectId = (String) properties.get("objectId");
			}
			if (properties.containsKey("token")) {
				Object tokenValue = properties.get("token");
				if (tokenValue instanceof Number) {
					this.token = ((Number) tokenValue).longValue();
				} else if (tokenValue instanceof String) {
					try {
						this.token = Long.valueOf((String) tokenValue);
					} catch (NumberFormatException e) {
						log.warn("Failed to parse token value: " + tokenValue);
						this.token = null;
					}
				}
			}
			if (properties.containsKey("changeType")) {
				Object changeTypeValue = properties.get("changeType");
				if (changeTypeValue instanceof String) {
					try {
						this.changeType = ChangeType.valueOf((String) changeTypeValue);
					} catch (IllegalArgumentException e) {
						log.warn("Unknown change type: " + changeTypeValue + ", defaulting to UPDATED");
						this.changeType = ChangeType.UPDATED;
					}
				}
			}
			if (properties.containsKey("time")) {
				// Use the same date parsing logic as parent class
				this.time = parseDateTime(properties.get("time"));
			}
		}
	}

	public CouchChange(Change c){
		super(c);
		GregorianCalendar time = c.getTime();
		setObjectId(c.getObjectId());
		Long token = convertChangeToken(c.getToken());
		setToken(token);
		// CRITICAL FIX: Ensure ChangeType is not null to prevent NullPointerException
		org.apache.chemistry.opencmis.commons.enums.ChangeType sourceChangeType = c.getChangeType();
		if (sourceChangeType == null) {
			// Default to UPDATED if not specified
			sourceChangeType = org.apache.chemistry.opencmis.commons.enums.ChangeType.UPDATED;
			log.warn("CouchChange constructor: ChangeType was null, defaulting to UPDATED for object: " + c.getObjectId());
		}
		setChangeType(sourceChangeType);
		setTime(time != null ? time : c.getCreated());
		setType(c.getType());
		setName(c.getName());
		// CRITICAL FIX: Ensure baseType is set properly from source Change object
		String sourceBaseType = c.getBaseType();
		if (sourceBaseType == null || sourceBaseType.isEmpty()) {
			// Try to infer from objectType if baseType is null
			String sourceObjectType = c.getObjectType();
			if (sourceObjectType != null) {
				if (sourceObjectType.startsWith("cmis:document") || sourceObjectType.equals("cmis:document")) {
					sourceBaseType = "cmis:document";
				} else if (sourceObjectType.startsWith("cmis:folder") || sourceObjectType.equals("cmis:folder")) {
					sourceBaseType = "cmis:folder";
				} else if (sourceObjectType.startsWith("cmis:relationship") || sourceObjectType.equals("cmis:relationship")) {
					sourceBaseType = "cmis:relationship";
				} else if (sourceObjectType.startsWith("cmis:policy") || sourceObjectType.equals("cmis:policy")) {
					sourceBaseType = "cmis:policy";
				} else if (sourceObjectType.startsWith("cmis:item") || sourceObjectType.equals("cmis:item")) {
					sourceBaseType = "cmis:item";
				} else {
					sourceBaseType = "cmis:document"; // Default
				}
				log.warn("CouchChange constructor: BaseType was null, inferred '" + sourceBaseType + "' from objectType '" + sourceObjectType + "'");
			} else {
				sourceBaseType = "cmis:document"; // Final fallback
				log.error("CouchChange constructor: Both baseType and objectType were null, defaulting to 'cmis:document'");
			}
		}
		setBaseType(sourceBaseType);
		setObjectType(c.getObjectType());
		setVersionSeriesId(c.getVersionSeriesId());
		setVersionLabel(c.getVersionLabel());
		setPolicyIds(c.getPolicyIds());
		setAcl(c.getAcl());
	}

	public String getParetnId() {
		return paretnId;
	}

	public void setParetnId(String paretnId) {
		this.paretnId = paretnId;
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

	public Long getToken() {
		return token;
	}

	public void setToken(Long token) {
		this.token = token;
	}

	public Long convertChangeToken(String changeToken) {
		Long _changeToken = null;
		try{
			_changeToken = Long.valueOf(changeToken);
		}catch(Exception e){
			log.error("Change token must be long type value", e);
		}

		return _changeToken;
	}

	public ChangeType getChangeType() {
		return changeType;
	}
	public void setChangeType(ChangeType changeType) {
		this.changeType = changeType;
	}
	public GregorianCalendar getTime() {
		return time;
	}

	public void setTime(GregorianCalendar time) {
		this.time = time;
	}

	/**
	 * descending by created time
	 */
	@Override
	public int compareTo(CouchChange o) {
		return o.getCreated().compareTo(this.created);
	}

	@Override
	public Change convert(){
		Change change = new Change(super.convert());
		// CRITICAL FIX: Ensure ChangeType is not null during conversion
		org.apache.chemistry.opencmis.commons.enums.ChangeType changeType = getChangeType();
		if (changeType == null) {
			changeType = org.apache.chemistry.opencmis.commons.enums.ChangeType.UPDATED;
			log.warn("CouchChange.convert(): ChangeType was null, defaulting to UPDATED for object: " + getObjectId());
		}
		change.setChangeType(changeType);
		change.setTime(getTime());
		change.setObjectId(getObjectId());
		change.setToken(String.valueOf(getToken()));
		change.setType(getType());

		change.setName(getName());
		// CRITICAL FIX: Ensure baseType is not null to prevent NullPointerException in query operations
		String baseType = getBaseType();
		if (baseType == null || baseType.isEmpty()) {
			// Set default baseType based on object type if not set
			String objectType = getObjectType();
			if (objectType != null) {
				if (objectType.startsWith("cmis:document") || objectType.equals("cmis:document")) {
					baseType = "cmis:document";
				} else if (objectType.startsWith("cmis:folder") || objectType.equals("cmis:folder")) {
					baseType = "cmis:folder";
				} else if (objectType.startsWith("cmis:relationship") || objectType.equals("cmis:relationship")) {
					baseType = "cmis:relationship";
				} else if (objectType.startsWith("cmis:policy") || objectType.equals("cmis:policy")) {
					baseType = "cmis:policy";
				} else if (objectType.startsWith("cmis:item") || objectType.equals("cmis:item")) {
					baseType = "cmis:item";
				} else {
					// Default to document for unknown types
					baseType = "cmis:document";
				}
				log.warn("BaseType was null, inferred '" + baseType + "' from objectType '" + objectType + "' for change event");
			} else {
				baseType = "cmis:document"; // Final fallback
				log.warn("Both baseType and objectType were null, defaulting to 'cmis:document' for change event");
			}
		}
		change.setBaseType(baseType);
		change.setObjectType(getObjectType());
		change.setVersionSeriesId(getVersionSeriesId());
		change.setVersionLabel(getVersionLabel());
		change.setPolicyIds(getPolicyIds());
		change.setAcl(getAcl());
		
		return change;
	}
}
