package jp.aegif.nemaki.model.couch;


import jp.aegif.nemaki.model.GroupItem;

public class CouchGroupItem extends CouchItem{
	private String groupId;
	
	public CouchGroupItem(){
		super();
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
