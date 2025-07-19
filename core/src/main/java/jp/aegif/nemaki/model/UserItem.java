package jp.aegif.nemaki.model;

import java.lang.reflect.InvocationTargetException;

import org.apache.commons.beanutils.BeanUtils;

public class UserItem extends Item{
	private String userId;
	private String password;
	private Boolean admin = false;

	public UserItem(){
		super();
		setAcl(new Acl());
	}
	
	public UserItem(String id, String objectType, String userId, String name, String password, Boolean admin, String parentFolderId){
		this();
		setId(id);
		setObjectType(objectType);
		setUserId(userId);
		setName(name);
		setPassword(password);
		setAdmin(admin);
		setParentId(parentFolderId);
	}
	
	public UserItem(Item item){
		super(item);
		try {
			BeanUtils.copyProperties(this, item);
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
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

	public void setAdmin(Boolean admin) {
		this.admin = admin;
	}
}
