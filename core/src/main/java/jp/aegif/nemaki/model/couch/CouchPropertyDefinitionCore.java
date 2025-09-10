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

public class CouchPropertyDefinitionCore extends CouchNodeBase{
	
	private static final long serialVersionUID = -213127366706433797L;
	
	private String propertyId;
	private PropertyType propertyType;
	private String queryName;
	private Cardinality cardinality;
	
	public CouchPropertyDefinitionCore(){
		super();	
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	// CRITICAL FIX: 汚染防止システム統合 - setterメソッド経由で処理
	@JsonCreator
	public CouchPropertyDefinitionCore(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し
		
		if (properties != null) {
			// ✅ 汚染防止システム通過: setterメソッド経由で処理
			setPropertyId((String) properties.get("propertyId"));
			setQueryName((String) properties.get("queryName"));
			
			// PropertyType列挙型の処理
			if (properties.containsKey("propertyType")) {
				String propTypeStr = (String) properties.get("propertyType");
				if (propTypeStr != null) {
					try {
						setPropertyType(PropertyType.fromValue(propTypeStr));
					} catch (Exception e) {
						// 無効な値の場合は無視
					}
				}
			}
			
			// Cardinality列挙型の処理
			if (properties.containsKey("cardinality")) {
				String cardinalityStr = (String) properties.get("cardinality");
				if (cardinalityStr != null) {
					try {
						setCardinality(Cardinality.fromValue(cardinalityStr));
					} catch (Exception e) {
						// 無効な値の場合は無視
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
		NemakiPropertyDefinitionCore p = new NemakiPropertyDefinitionCore(super.convert());
		
		p.setPropertyId(getPropertyId());
		p.setQueryName(getQueryName());
		p.setPropertyType(getPropertyType());
		p.setCardinality(getCardinality());
		
		return p;
	}
	
}
