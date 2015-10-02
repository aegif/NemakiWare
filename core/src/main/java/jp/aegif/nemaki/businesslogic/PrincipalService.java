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
package jp.aegif.nemaki.businesslogic;

import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * Users/groups Service interface.
 */
public interface PrincipalService {

	/**
	 * Get a list of all users
	 * @param repositoryId TODO
	 */
	List<User> getUsers(String repositoryId);

	/**
	 * Get a list of all groups
	 * @param repositoryId TODO
	 */
	List<Group> getGroups(String repositoryId);

	/**
	 * Get a list of all groups that contain a given user.
	 * @param repositoryId TODO
	 */
	Set<String> getGroupIdsContainingUser(String repositoryId, String username);

	/**
	 * Get a user, given its id.
	 * @param repositoryId TODO
	 */
	User getUserById(String repositoryId, String userId);

	/**
	 * Get a group, given its identifier.
	 * @param repositoryId TODO
	 */
	Group getGroupById(String repositoryId, String groupId);

	/**
	 * Create a user, given its user object.
	 * @param repositoryId TODO
	 */
	void createUser(String repositoryId, User user);

	/**
	 * Update a user, given its user object;
	 * @param repositoryId TODO
	 */
	void updateUser(String repositoryId, User user);

	/**
	 * Delete a user, given its class and user identifier
	 * @param repositoryId TODO
	 */
	void deleteUser(String repositoryId, String userId);

	/**
	 * Create a group, given its group object.
	 * @param repositoryId TODO
	 */
	void createGroup(String repositoryId, Group group);

	/**
	 * Update a group, given its group object
	 * @param repositoryId TODO
	 */
	void updateGroup(String repositoryId, Group group);

	/**
	 * Delete a group, given its class and group identifier.
	 * @param repositoryId TODO
	 */
	void deleteGroup(String repositoryId, String groupId);

	/**
	 *  Get admin user
	 * @param repositoryId TODO
	 * @return
	 */
	User getAdmin(String repositoryId);

	/**
	 * Get anonymous Id(non authenticated user)
	 * @param repositoryId TODO
	 * @return
	 */
	String getAnonymous(String repositoryId);

	/**
	 * Get anyone Id(any authenticated user)
	 * @param repositoryId TODO
	 * @return
	 */
	String getAnyone(String repositoryId);
}
