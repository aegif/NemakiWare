package jp.aegif.nemaki.model.couch;


import jp.aegif.nemaki.model.GroupItem;
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
