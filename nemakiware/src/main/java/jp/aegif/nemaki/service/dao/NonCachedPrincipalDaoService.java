package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

public interface NonCachedPrincipalDaoService {

	public abstract User createUser(User user);

	public abstract Group createGroup(Group group);

	public abstract User updateUser(User user);

	public abstract Group updateGroup(Group group);

	public abstract void deleteUser(String principalId);
	
	public abstract void deleteGroup(String principalId);
	
	public abstract List<User> getUsers();

	public abstract User getUserById(String userId);

	public abstract User getUserByName(String userName);

	public abstract Group getGroupById(String groupId);

	public abstract Group getGroupByName(String groupName);

	public abstract List<Group> getGroups();

}