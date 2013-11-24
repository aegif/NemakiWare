package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

public interface NonCachedPrincipalDaoService {

	/**
	 * Get a user
	 * @param userId
	 * @return
	 */
	User getUserById(String userId);

	/**
	 * Get a user by name
	 * @param userName
	 * @return
	 */
	User getUserByName(String userName);

	/**
	 * Get a user
	 * @return
	 */
	List<User> getUsers();
	
	/**
	 * Get a group
	 * @param groupId
	 * @return
	 */
	Group getGroupById(String groupId);

	/**
	 * Get a group by name
	 * @param groupName
	 * @return
	 */
	Group getGroupByName(String groupName);

	/**
	 * Get all the groups
	 * @return
	 */
	List<Group> getGroups();
	
	/**
	 * Create a user
	 * @param user
	 * @return newly created user
	 */
	User createUser(User user);

	/**
	 * Create a group
	 * @param group
	 * @return newly created group
	 */
	Group createGroup(Group group);

	/**
	 * Update a user
	 * @param user
	 * @return updated user
	 */
	User updateUser(User user);

	/**
	 * Update a group
	 * @param group
	 * @return updated group
	 */
	Group updateGroup(Group group);

	/**
	 * Delete a user
	 * @param principalId
	 */
	void deleteUser(String principalId);
	
	/**
	 * Delete a group
	 * @param principalId
	 */
	void deleteGroup(String principalId);
}