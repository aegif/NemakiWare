package jp.aegif.nemaki.model.couch;


import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonCreator;

public class CouchGroupItem extends CouchItem{
	private String groupId;

	public CouchGroupItem(){
		super();
	}

	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchGroupItem(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し

		if (properties != null) {
			// GroupItem固有のフィールドマッピング
			this.groupId = (String) properties.get("groupId");

			// CRITICAL FIX: Convert users and groups fields to subTypeProperties
			// CouchDB stores users/groups as direct fields, but GroupItem expects them in subTypeProperties
			List<Property> subTypeProps = getSubTypeProperties();
			if (subTypeProps == null) {
				subTypeProps = new ArrayList<>();
				setSubTypeProperties(subTypeProps);
			}

			// Convert users field to nemaki:users property
			if (properties.containsKey("users")) {
				Object usersValue = properties.get("users");
				if (usersValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> usersList = (List<String>) usersValue;
					subTypeProps.add(new Property("nemaki:users", usersList));
				}
			}

			// Convert groups field to nemaki:groups property
			if (properties.containsKey("groups")) {
				Object groupsValue = properties.get("groups");
				if (groupsValue instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> groupsList = (List<String>) groupsValue;
					subTypeProps.add(new Property("nemaki:groups", groupsList));
				}
			}
		}
	}
	
	public CouchGroupItem(GroupItem groupItem){
		super(groupItem);
		setGroupId(groupItem.getGroupId());
	}
	
	public String getGroupId() {
		return groupId;
	}
	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}
	
	public GroupItem convert(){
		GroupItem groupItem = new GroupItem(super.convert());
		groupItem.setGroupId(getGroupId());
		
		return groupItem;
	}
}
