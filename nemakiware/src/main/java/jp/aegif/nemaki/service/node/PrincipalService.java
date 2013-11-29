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
package jp.aegif.nemaki.service.node;

import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * Users/groups Service interface.
 */
public interface PrincipalService {

	public static final String GROUP_EVERYONE = "GROUP_EVERYONE";
	public static final String USER_UNKNOWN = "USER_UNKNOWN";

	/**
	 * Get a list of all users
	 */
	List<User> getUsers();

	/**
	 * Get a list of all groups
	 */
	List<Group> getGroups();

	/**
	 * Get a list of all groups that contain a given user.
	 */
	Set<String> getGroupIdsContainingUser(String username);

	/**
	 * Get a user, given its id.
	 */
	User getUserById(String userId);

	/**
	 * Get a group, given its identifier.
	 */
	Group getGroupById(String groupId);

	/**
	 * Create a user, given its user object.
	 */
	void createUser(User user);
	
	/**
	 * Update a user, given its user object;
	 */
	void updateUser(User user);
	
	/**
	 * Delete a user, given its class and user identifier
	 */
	void deleteUser(String userId);
	
	/**
	 * Create a group, given its group object.
	 */
	void createGroup(Group group);
	
	/**
	 * Update a group, given its group object
	 */
	void updateGroup(Group group);
	
	/**
	 * Delete a group, given its class and group identifier.
	 */
	void deleteGroup(String groupId);
}
