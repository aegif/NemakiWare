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
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.TimeZone;

import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.util.constant.NodeType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.HashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchNodeBase {
	
	private static final Log log = LogFactory.getLog(CouchNodeBase.class);
	private static final long serialVersionUID = 8798101386986624403L;

	// CouchDB document fields
	@JsonProperty("_id")
	protected String id;
	
	@JsonProperty("_rev")
	protected String revision;

	protected String type;
	protected GregorianCalendar created;
	protected String creator;
	protected GregorianCalendar modified;
	protected String modifier;
	
	// Cloudant SDK Documentオブジェクトの動的プロパティを処理
	protected Map<String, Object> additionalProperties = new HashMap<>();
	
	public CouchNodeBase(){
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchNodeBase(Map<String, Object> properties) {
		if (properties != null) {
			// 基本フィールドのマッピング
			if (properties.containsKey("_id")) {
				this.id = (String) properties.get("_id");
			}
			if (properties.containsKey("_rev")) {
				this.revision = (String) properties.get("_rev");
			}
			if (properties.containsKey("type")) {
				this.type = (String) properties.get("type");
			}
			
			// 日付フィールドの処理（CouchDBの複数形式をGregorianCalendarに変換）
			if (properties.containsKey("created")) {
				this.created = parseDateTime(properties.get("created"));
			}
			if (properties.containsKey("modified")) {
				this.modified = parseDateTime(properties.get("modified"));
			}
			
			if (properties.containsKey("creator")) {
				this.creator = (String) properties.get("creator");
			}
			if (properties.containsKey("modifier")) {
				this.modifier = (String) properties.get("modifier");
			}
			
			// その他のプロパティを保存
			this.additionalProperties.putAll(properties);
		}
	}
	
	public CouchNodeBase(NodeBase nb){
		//Don't allow setId(null)
		if(nb.getId() != null) setId(nb.getId());
		setType(nb.getType());
		setCreated(nb.getCreated());
		setCreator(nb.getCreator());
		setModified(nb.getModified());
		setModifier(nb.getModifier());
		
		// COMPREHENSIVE REVISION MANAGEMENT: Preserve revision from NodeBase
		setRevision(nb.getRevision());
	}
	
	// 動的プロパティを処理するためのメソッド
	// CouchTypeDefinitionの明示的なフィールドは除外する（Jackson王道パターン）
	@JsonAnySetter
	public void setAdditionalProperty(String name, Object value) {
		// TCK DEBUG: Log all calls to @JsonAnySetter
		boolean explicit = isExplicitField(name);
		System.err.println("[TCK DEBUG] @JsonAnySetter called: name=" + name +
			", value=" + value + ", isExplicit=" + explicit +
			", class=" + this.getClass().getSimpleName());

		// CouchTypeDefinitionで明示的に定義されているフィールドは除外
		if (!explicit) {
			this.additionalProperties.put(name, value);
		}
	}
	
	// 明示的に定義されているフィールドかどうかを判定
	protected boolean isExplicitField(String fieldName) {
		// CouchTypeDefinitionで明示的に@JsonPropertyが定義されているフィールド
		return "properties".equals(fieldName) || 
		       "allowedSourceTypes".equals(fieldName) || 
		       "allowedTargetTypes".equals(fieldName);
	}
	
	// Jackson王道パターン：@JsonAnyGetterでserialization制御
	@JsonAnyGetter
	public Map<String, Object> getAdditionalProperties() {
		return additionalProperties;
	}
	
	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}
	
	public Boolean isFolder(){
		return (NodeType.CMIS_FOLDER.value().equals(type)) ? true : false;  
	}
	
	public Boolean isDocument(){
		return (NodeType.CMIS_DOCUMENT.value().equals(type)) ? true : false;
	}
	
	public Boolean isRelationship(){
		return (NodeType.CMIS_RELATIONSHIP.value().equals(type)) ? true : false;
	}
	
	public Boolean isPolicy(){
		return (NodeType.CMIS_POLICY.value().equals(type)) ? true : false;
	}

	public Boolean isContent(){
		return isDocument() || isFolder() || isRelationship() || isPolicy();
	}
	
	public Boolean isAttachment(){
		return (NodeType.ATTACHMENT.value().equals(type)) ? true : false;
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
	
	// CouchDB document methods (replacing CouchDbDocument functionality)
	public String getId() {
		return id;
	}
	
	public void setId(String id) {
		this.id = id;
	}
	
	public String getRevision() {
		return revision;
	}
	
	public void setRevision(String revision) {
		this.revision = revision;
	}
	
	/**
	 * CouchDBの日時値をGregorianCalendarに変換します
	 * 対応形式:
	 * 1. ISO 8601文字列: "2013-01-01T00:00:00.000+0000"
	 * 2. タイムスタンプ数値: 1388534400000 (Long/Double)
	 */
	protected GregorianCalendar parseDateTime(Object dateValue) {
		if (dateValue == null) {
			return null;
		}

		try {
			// 数値タイムスタンプの場合（Long, Double, Integer）
			if (dateValue instanceof Number) {
				long timestamp = ((Number) dateValue).longValue();
				// TCK FIX (2025-10-21): Use UTC timezone for consistent timestamp handling
				GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
				calendar.setTimeInMillis(timestamp);
				return calendar;
			}

			// 文字列の場合
			if (dateValue instanceof String) {
				String dateStr = (String) dateValue;

				// TCK CRITICAL FIX (2025-10-21): Check if string is numeric timestamp first
				// Cloudant SDK sometimes returns numeric timestamps as strings
				if (dateStr.matches("^\\d+$")) {
					try {
						long timestamp = Long.parseLong(dateStr);
						GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
						calendar.setTimeInMillis(timestamp);
						return calendar;
					} catch (NumberFormatException e) {
						log.debug("String looked like numeric timestamp but failed to parse: " + dateStr);
						// Fall through to ISO 8601 parsing
					}
				}

				// Try ISO 8601 format
				return parseISODateTime(dateStr);
			}

			// TCK CRITICAL FIX (2025-10-21): Return null instead of current time for unexpected types
			// Previous behavior: Returned new GregorianCalendar() causing timestamp discrepancies
			// This caused queryRootFolderTest to fail (Browser API showed wrong timestamps)
			log.error("Unexpected date value type: " + dateValue.getClass().getName() + ", value: " + dateValue);
			return null;
		} catch (Exception e) {
			// TCK CRITICAL FIX (2025-10-21): Return null instead of current time on parse errors
			log.error("Failed to parse date value: " + dateValue + " - " + e.getMessage(), e);
			return null;
		}
	}
	
	/**
	 * CouchDBのISO 8601日時文字列をGregorianCalendarに変換します
	 * 形式: "2013-01-01T00:00:00.000+0000"
	 */
	private GregorianCalendar parseISODateTime(String isoDateString) {
		if (isoDateString == null || isoDateString.trim().isEmpty()) {
			return null;
		}

		try {
			// Create new SimpleDateFormat instance for thread safety (local to method)
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

			// TCK FIX (2025-10-21): Use UTC timezone for consistent timestamp handling
			GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
			calendar.setTime(sdf.parse(isoDateString));

			return calendar;
		} catch (ParseException e) {
			// TCK CRITICAL FIX (2025-10-21): Return null instead of current time on parse errors
			log.error("Failed to parse ISO date string: " + isoDateString + " - " + e.getMessage(), e);
			return null;
		}
	}
	
	public NodeBase convert(){
		NodeBase n = new NodeBase();
		n.setId(getId());
		n.setType(getType());
		n.setCreated(getCreated());
		n.setCreator(getCreator());
		n.setModified(getModified());
		n.setModifier(getModifier());
		
		// COMPREHENSIVE REVISION MANAGEMENT: Preserve revision during conversion
		// This ensures Content objects maintain revision state from CouchDB layer
		n.setRevision(getRevision());
		
		return n;
	}
}
