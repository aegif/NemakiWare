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

import jp.aegif.nemaki.model.UserItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Map;
import java.util.HashMap;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(as = CouchUserItem.class)
public class CouchUserItem extends CouchItem{
	
	private static final long serialVersionUID = 3294975060332894322L;

	@JsonProperty("userId")
	private String userId;
	
	@JsonProperty("password")
	private String password;
	
	@JsonProperty("admin")
	@JsonSetter(nulls = Nulls.SET)
	private Boolean admin = false;
	
	// Cloudant SDK Documentオブジェクトの動的プロパティを処理
	private Map<String, Object> additionalProperties = new HashMap<>();
	
	public CouchUserItem(){
		super();
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchUserItem(Map<String, Object> properties) {
		super();
		if (properties != null) {
			// 直接フィールドマッピング
			this.userId = (String) properties.get("userId");
			this.password = (String) properties.get("password");
			// nameは親クラスCouchContentのsetterを使用
			if (properties.containsKey("name")) {
				setName((String) properties.get("name"));
			}
			
			// admin フィールドの柔軟な処理
			Object adminValue = properties.get("admin");
			if (adminValue instanceof Boolean) {
				this.admin = (Boolean) adminValue;
			} else if (adminValue instanceof String) {
				this.admin = Boolean.parseBoolean((String) adminValue);
			} else if (adminValue != null) {
				this.admin = Boolean.valueOf(adminValue.toString());
			}
			
			// 親クラスのフィールドは親クラスのコンストラクタで処理されるべきだが、
			// 現在の実装では手動で設定する必要がある
			if (properties.containsKey("_id")) {
				setId((String) properties.get("_id"));
			}
			if (properties.containsKey("_rev")) {
				setRevision((String) properties.get("_rev"));
			}
			if (properties.containsKey("type")) {
				setType((String) properties.get("type"));
			}
			if (properties.containsKey("objectType")) {
				setObjectType((String) properties.get("objectType"));
			}
			// created/modifiedはGregorianCalendarなので、変換をスキップ
			// TODO: 日付文字列からGregorianCalendarへの変換実装
			if (properties.containsKey("creator")) {
				setCreator((String) properties.get("creator"));
			}
			if (properties.containsKey("modifier")) {
				setModifier((String) properties.get("modifier"));
			}
			
			// その他のプロパティを保存
			this.additionalProperties.putAll(properties);
		}
	}
	
	public CouchUserItem(UserItem userItem){
		super(userItem);
		setUserId(userItem.getUserId());
		setPassword(userItem.getPassowrd());
		setAdmin(userItem.isAdmin());
	}
	
	// 動的プロパティを処理するためのメソッド
	@JsonAnySetter
	public void setAdditionalProperty(String name, Object value) {
		this.additionalProperties.put(name, value);
	}
	
	public Map<String, Object> getAdditionalProperties() {
		return this.additionalProperties;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
	
	public Boolean isAdmin() {
		return admin;
	}

	public void setAdmin(Boolean isAdmin) {
		this.admin = isAdmin;
	}

	// Add setter methods for direct field access
	public void setId(String id) {
		this.id = id;
	}
	
	public String getId() {
		return this.id;
	}
	
	public void setRevision(String revision) {
		this.revision = revision;
	}
	
	public void setType(String type) {
		this.type = type;
	}
	
	public void setObjectType(String objectType) {
		// Store in additional properties since CouchContent handles this
		if (this.additionalProperties == null) {
			this.additionalProperties = new HashMap<>();
		}
		this.additionalProperties.put("objectType", objectType);
	}

	public UserItem convert(){
		UserItem userItem = new UserItem(super.convert());
		
		// Ensure userId is not null
		if (getUserId() != null) {
			userItem.setUserId(getUserId());
		} else {
			throw new RuntimeException("UserId is required but null in CouchUserItem conversion");
		}
		
		userItem.setPassowrd(getPassword());
		userItem.setAdmin(isAdmin() != null ? isAdmin() : false);
		
		return userItem;
	}
}