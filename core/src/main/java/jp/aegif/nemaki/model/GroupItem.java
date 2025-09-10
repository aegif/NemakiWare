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
	public List<String> getUsers() {
		Property users = getSubTypeProperty("nemaki:users");
		if(users != null && users.getValue() != null && users.getValue() instanceof List){
			List<?> rawList = (List<?>) users.getValue();
			List<String> result = new ArrayList<>();
			for (Object item : rawList) {
				if (item instanceof String) {
					result.add((String) item);
				}
			}
			return result;
		}
		return new ArrayList<>();
	}
	public List<String> getGroups() {
		Property groups = getSubTypeProperty("nemaki:groups");
		if(groups != null && groups.getValue() != null && groups.getValue() instanceof List){
			List<?> rawList = (List<?>) groups.getValue();
			List<String> result = new ArrayList<>();
			for (Object item : rawList) {
				if (item instanceof String) {
					result.add((String) item);
				}
			}
			return result;
		}
		return new ArrayList<>();
	}

	public void setUsers(List<String> userIds) {
		Property users = getSubTypeProperty("nemaki:users");
		if(users != null) {
			users.setValue(userIds);
		} else {
			// プロパティが存在しない場合は新規作成
			List<Property> subTypeProperties = getSubTypeProperties();
			if (subTypeProperties == null) {
				subTypeProperties = new ArrayList<>();
				setSubTypeProperties(subTypeProperties);
			}
			subTypeProperties.add(new Property("nemaki:users", userIds));
		}
	}

	public void setGroups(List<String> groupIds) {
		Property groups = getSubTypeProperty("nemaki:groups");
		if(groups != null) {
			groups.setValue(groupIds);
		} else {
			// プロパティが存在しない場合は新規作成
			List<Property> subTypeProperties = getSubTypeProperties();
			if (subTypeProperties == null) {
				subTypeProperties = new ArrayList<>();
				setSubTypeProperties(subTypeProperties);
			}
			subTypeProperties.add(new Property("nemaki:groups", groupIds));
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
