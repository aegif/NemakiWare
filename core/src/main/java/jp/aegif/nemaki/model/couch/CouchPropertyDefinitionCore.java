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

import java.util.Map;

import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CouchPropertyDefinitionCore extends CouchNodeBase{

	private static final long serialVersionUID = -213127366706433797L;

	@JsonProperty("propertyId")
	private String propertyId;
	@JsonProperty("propertyType")
	private PropertyType propertyType;
	@JsonProperty("queryName")
	private String queryName;
	@JsonProperty("cardinality")
	private Cardinality cardinality;
	
	public CouchPropertyDefinitionCore(){
		super();	
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	// CRITICAL FIX: 汚染防止システム統合 - setterメソッド経由で処理
	@JsonCreator
	public CouchPropertyDefinitionCore(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し

		// TCK DEBUG: Log entire Map contents
		System.err.println("TCK DEBUG: CouchPropertyDefinitionCore Map constructor called with properties: " + properties);

		// CRITICAL FIX: super() doesn't preserve PropertyDefinitionCore-specific fields
		// We need to manually set them from the properties map
		if (properties != null) {
			// TCK DEBUG: Log propertyId extraction
			String propertyIdFromMap = (String) properties.get("propertyId");
			System.err.println("TCK DEBUG: propertyId from Map: " + propertyIdFromMap);

			// ✅ 汚染防止システム通過: setterメソッド経由で処理
			setPropertyId(propertyIdFromMap);
			setQueryName((String) properties.get("queryName"));

			// TCK DEBUG: Verify propertyId was set
			System.err.println("TCK DEBUG: After setPropertyId(), this.propertyId = " + this.propertyId);

			// PropertyType列挙型の処理 - CRITICAL: super() doesn't preserve this!
			if (properties.containsKey("propertyType")) {
				Object propTypeObj = properties.get("propertyType");
				System.err.println("TCK PROPERTY TYPE DEBUG: Raw propertyType value: " + propTypeObj + " (class: " + (propTypeObj != null ? propTypeObj.getClass() : "null") + ")");

				if (propTypeObj instanceof PropertyType) {
					// Already a PropertyType enum
					setPropertyType((PropertyType) propTypeObj);
					System.err.println("TCK PROPERTY TYPE DEBUG: Direct PropertyType enum set: " + propTypeObj);
				} else if (propTypeObj instanceof String) {
					String propTypeStr = (String) propTypeObj;
					if (propTypeStr != null && !propTypeStr.isEmpty()) {
						try {
							PropertyType pt = PropertyType.fromValue(propTypeStr.toLowerCase());
							setPropertyType(pt);
							System.err.println("TCK PROPERTY TYPE DEBUG: Converted string '" + propTypeStr + "' to PropertyType: " + pt);
						} catch (Exception e) {
							// TCK FIX: Default to STRING if conversion fails
							System.err.println("TCK PROPERTY TYPE ERROR: Failed to convert '" + propTypeStr + "' to PropertyType: " + e.getMessage() + ". Defaulting to STRING");
							setPropertyType(PropertyType.STRING);
						}
					}
				}
			} else {
				System.err.println("TCK PROPERTY TYPE WARNING: No propertyType key in properties map!");
			}

			// Verify PropertyType was set
			System.err.println("TCK PROPERTY TYPE VERIFY: After processing, this.propertyType = " + this.propertyType);

			// Cardinality列挙型の処理
			if (properties.containsKey("cardinality")) {
				Object cardinalityObj = properties.get("cardinality");
				if (cardinalityObj instanceof String) {
					String cardinalityStr = (String) cardinalityObj;
					if (cardinalityStr != null && !cardinalityStr.isEmpty()) {
						try {
							setCardinality(Cardinality.fromValue(cardinalityStr.toLowerCase()));
						} catch (Exception e) {
							// 無効な値の場合は無視
							System.err.println("TCK DEBUG: Failed to convert cardinality '" + cardinalityStr + "': " + e.getMessage());
						}
					}
				}
			}
		}
	}
	
	public CouchPropertyDefinitionCore(NemakiPropertyDefinitionCore np){
		super(np);
		setPropertyId(np.getPropertyId());
		setPropertyType(np.getPropertyType());
		setQueryName(np.getQueryName());
		setCardinality(np.getCardinality());
	}
	
	public String getPropertyId() {
		return propertyId;
	}
	public void setPropertyId(String propertyId) {
		this.propertyId = propertyId;
	}
	public PropertyType getPropertyType() {
		return propertyType;
	}
	public void setPropertyType(PropertyType propertyType) {
		this.propertyType = propertyType;
	}
	public String getQueryName() {
		return queryName;
	}
	public void setQueryName(String queryName) {
		this.queryName = queryName;
	}
	public Cardinality getCardinality() {
		return cardinality;
	}
	public void setCardinality(Cardinality cardinality) {
		this.cardinality = cardinality;
	}
	
	public NemakiPropertyDefinitionCore convert(){
		// TCK FIX: Create NemakiPropertyDefinitionCore directly instead of using super.convert()
		// super.convert() returns NodeBase which loses PropertyDefinitionCore-specific fields
		NemakiPropertyDefinitionCore p = new NemakiPropertyDefinitionCore();

		// Set NodeBase fields directly
		p.setId(getId());
		p.setType(getType());
		p.setCreated(getCreated());
		p.setCreator(getCreator());
		p.setModified(getModified());
		p.setModifier(getModifier());
		p.setRevision(getRevision());

		// Set PropertyDefinitionCore-specific fields
		p.setPropertyId(getPropertyId());
		p.setQueryName(getQueryName());
		PropertyType pt = getPropertyType();
		System.err.println("TCK CONVERT DEBUG: CouchPropertyDefinitionCore.convert() - propertyId=" + getPropertyId() +
			", propertyType=" + pt);
		p.setPropertyType(pt);
		p.setCardinality(getCardinality());

		System.err.println("TCK CONVERT DEBUG: After setting, NemakiPropertyDefinitionCore has propertyType=" + p.getPropertyType());

		return p;
	}
	
}
