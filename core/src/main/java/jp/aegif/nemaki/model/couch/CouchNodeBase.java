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
// Key order is DECLARED, not inherited from reflection. HotSpot keeps a class's method
// array sorted by the ADDRESS of each method-name Symbol, and Symbols are interned
// process-wide in load order — so which class happened to load first decided the order
// Jackson wrote these documents in, and it could differ between two boots of the same
// binary. Alphabetical is the one order that does not depend on that history.
@com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
public class CouchNodeBase {
	
	private static final Log log = LogFactory.getLog(CouchNodeBase.class);
	private static final long serialVersionUID = 8798101386986624403L;

	// CouchDB document fields
	@JsonProperty("_id")
	protected String id;
	
	@JsonProperty("_rev")
	protected String revision;

	@JsonProperty("type")
	protected String type;
	protected GregorianCalendar created;
	protected String creator;
	protected GregorianCalendar modified;
	protected String modifier;
	
	// Cloudant SDK Documentオブジェクトの動的プロパティを処理
	// Sorted, for the same reason as @JsonPropertyOrder above: HashMap iteration order is a
	// function of capacity and insertion history, so two nodes could emit the same extra
	// properties in different orders.
	protected Map<String, Object> additionalProperties = new java.util.TreeMap<>();
	
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

			// EXTRA properties only. Putting the whole document here would echo every typed
			// field back out through the @JsonAnyGetter below, so a stored document would
			// serialize each of its properties twice — and because the map is written LAST,
			// its (stale) copy is the one that survives a Map conversion or a JSON reader.
			// A read-modify-write would then discard the modification silently.
			retainExtraProperties(properties);
		}
	}

	/** Keeps only the keys this class does not already serialize as a typed property. */
	protected void retainExtraProperties(Map<String, Object> properties) {
		java.util.Set<String> typed = serializedPropertyNames(getClass());
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			if (!typed.contains(entry.getKey())) {
				this.additionalProperties.put(entry.getKey(), entry.getValue());
			}
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
	// このクラスが型付きプロパティとして書き出す名前は除外する（Jackson王道パターン）
	@JsonAnySetter
	public void setAdditionalProperty(String name, Object value) {
		if (!isExplicitField(name)) {
			this.additionalProperties.put(name, value);
		}
	}

	/**
	 * Whether this class already serializes {@code fieldName} as a typed property.
	 *
	 * <p>The any-setter receives every key Jackson could not bind, and that includes the
	 * READ-ONLY derived properties — {@code isDocument()}, {@code isFolder()},
	 * {@code isContent()} and friends have no field and no setter, so they are written on
	 * serialization but cannot be read back. Echoing them into the any-getter map made a
	 * stored document grow a duplicate of each on every round trip.
	 */
	protected boolean isExplicitField(String fieldName) {
		return serializedPropertyNames(getClass()).contains(fieldName);
	}

	// ------------------------------------------------------------------ typed-property set

	private static final java.util.Map<Class<?>, java.util.Set<String>> SERIALIZED_NAMES =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The property names this class writes itself, so the any-getter map never repeats one.
	 *
	 * <p>Mirrors the visibility the persistence mappers configure (see
	 * {@code ObjectMapperFactory}): fields at ANY visibility, getters and is-getters at
	 * PUBLIC_ONLY. {@code CouchModelSerializationShapeTest} compares this set against the keys
	 * Jackson actually emits for every model, so the two cannot drift apart.
	 */
	protected static java.util.Set<String> serializedPropertyNames(Class<?> type) {
		return SERIALIZED_NAMES.computeIfAbsent(type, CouchNodeBase::computeSerializedPropertyNames);
	}

	private static java.util.Set<String> computeSerializedPropertyNames(Class<?> type) {
		java.util.Set<String> names = new java.util.HashSet<>();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
						|| f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)
						|| isAnyGetterCarrier(f.getName())) {
					continue;
				}
				names.add(explicitName(f.getAnnotation(JsonProperty.class), f.getName()));
			}
			for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
				String property = readPropertyName(m);
				if (property != null && !isAnyGetterCarrier(property)) {
					names.add(explicitName(m.getAnnotation(JsonProperty.class), property));
				}
			}
		}
		return java.util.Set.copyOf(names);
	}

	/** The bean-property name a public no-arg getter contributes, or null if it is not one. */
	private static String readPropertyName(java.lang.reflect.Method m) {
		if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())
				|| java.lang.reflect.Modifier.isStatic(m.getModifiers())
				|| m.getParameterCount() != 0
				|| m.getReturnType() == void.class
				|| m.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)
				|| m.isAnnotationPresent(JsonAnyGetter.class)
				|| m.isSynthetic()) {
			return null;
		}
		String name = m.getName();
		if (name.startsWith("get") && name.length() > 3) {
			return decapitalize(name.substring(3));
		}
		if (name.startsWith("is") && name.length() > 2
				&& (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
			return decapitalize(name.substring(2));
		}
		return null;
	}

	private static String explicitName(JsonProperty annotation, String fallback) {
		return (annotation != null && !annotation.value().isEmpty()) ? annotation.value() : fallback;
	}

	/** The map itself is the any-getter channel, never one of the properties it may carry. */
	private static boolean isAnyGetterCarrier(String name) {
		return "additionalProperties".equals(name);
	}

	private static String decapitalize(String name) {
		if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
			return name; // URL -> URL, matching the standard bean rule Jackson follows
		}
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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

	// CRITICAL TCK FIX (2025-11-03): Accept Object type to handle Jackson deserialization
	// Jackson with PropertyAccessor.SETTER calls this method with numeric timestamps from CouchDB
	// Previously expected GregorianCalendar only, causing null dates in CMIS API
	// DEFENSIVE FIX: Don't override existing non-null value with null (Jackson calls setter after @JsonCreator)
	public void setCreated(Object created) {

		// DEFENSIVE: Don't override existing non-null value with null
		if (created == null && this.created != null) {
			return;
		}

		if (created == null) {
			this.created = null;
		} else if (created instanceof GregorianCalendar) {
			this.created = (GregorianCalendar) created;
		} else {
			// Handle numeric timestamps (Long, Double) or string timestamps from CouchDB
			this.created = parseDateTime(created);
		}
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

	// CRITICAL TCK FIX (2025-11-03): Accept Object type to handle Jackson deserialization
	// Jackson with PropertyAccessor.SETTER calls this method with numeric timestamps from CouchDB
	// Previously expected GregorianCalendar only, causing null dates in CMIS API
	// DEFENSIVE FIX: Don't override existing non-null value with null (Jackson calls setter after @JsonCreator)
	public void setModified(Object modified) {
		// DEFENSIVE: Don't override existing non-null value with null
		if (modified == null && this.modified != null) {
			return;
		}

		if (modified == null) {
			this.modified = null;
		} else if (modified instanceof GregorianCalendar) {
			this.modified = (GregorianCalendar) modified;
		} else {
			// Handle numeric timestamps (Long, Double) or string timestamps from CouchDB
			this.modified = parseDateTime(modified);
		}
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
