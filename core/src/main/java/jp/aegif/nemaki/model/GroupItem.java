package jp.aegif.nemaki.model;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang3.ObjectUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class GroupItem extends Item{
	private String groupId;

	public GroupItem(){
		super();
		setAcl(new Acl());
	}

	public GroupItem(String id ,String objectType, String groupId,  String name, List<String>users, List<String>groups){
		this();
		setId(id);
		setObjectType(objectType);
		setGroupId(groupId);
		setName(name);

		List<Property> subTypeProperties = new ArrayList<>();
		subTypeProperties.add(new Property("nemaki:users", users));
		subTypeProperties.add(new Property("nemaki:groups", groups));
		setSubTypeProperties(subTypeProperties);
	}

	public GroupItem(Item item){
		super(item);
		try {
			BeanUtils.copyProperties(this, item);
		} catch (IllegalAccessException | InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}
	@SuppressWarnings("unchecked")
	public List<String> getUsers() {
		Property users = getSubTypeProperty("nemaki:users");
		if(users != null && users.getValue() != null && users.getValue() instanceof List){
			return (List<String>)(users.getValue());
		}
		return new ArrayList<>();
	}
	@SuppressWarnings("unchecked")
	public List<String> getGroups() {
		Property groups = getSubTypeProperty("nemaki:groups");
		if(groups != null && groups.getValue() != null && groups.getValue() instanceof List){
			return (List<String>)(groups.getValue());
		}
		return new ArrayList<>();
	}

	public void setUsers(List<String> userIds) {
		Property users = getSubTypeProperty("nemaki:users");
		if(users != null && users.getValue() != null && users.getValue() instanceof List){
			users.setValue(userIds);
		}
	}

	public void setGroups(List<String> groupIds) {
		Property groups = getSubTypeProperty("nemaki:groups");
		if(groups != null && groups.getValue() != null && groups.getValue() instanceof List){
			groups.setValue(groupIds);
		}
	}

	private Property getSubTypeProperty(String key){
		List<Property> properties = getSubTypeProperties();
		//Map<String, Property> map = new HashMap<>();
		for(Property property : properties) {
			if(ObjectUtils.equals(property.getKey(), key)){
				return property;
			}
		}
		return null;
	}
}
