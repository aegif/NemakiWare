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
package jp.aegif.nemaki.dao;

import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * DAO Service interface.
 */
public interface PrincipalDaoService {
	User getUser(String repositoryId, String nodeId);

	/**
	 * Get a user by userId(It's supposed to be unique)
	 * @param repositoryId TODO
	 * @param userId
	 * @return
	 */
	User getUserById(String repositoryId, String userId);

	/**
	 * Get a users
	 * @param repositoryId TODO
	 * @return
	 */
	List<User> getUsers(String repositoryId);

	/**
	 * Get Admin user
	 * @param repositoryId TODO
	 * @return
	 */
	User getAdmin(String repositoryId);

	/**
	 * Get a group
	 * @param repositoryId TODO
	 * @param nodeId
	 * @return
	 */
	Group getGroup(String repositoryId, String nodeId);

	/**
	 * Get a group by groupId(It's supposed to be unique)
	 * @param repositoryId TODO
	 * @param groupId
	 * @return
	 */
	Group getGroupById(String repositoryId, String groupId);

	/**
	 * Get all the groups
	 * @param repositoryId TODO
	 * @return
	 */
	List<Group> getGroups(String repositoryId);

	/**
	 * Create a user
	 * @param repositoryId TODO
	 * @param user
	 * @return newly created user
	 */
	User createUser(String repositoryId, User user);

	/**
	 * Create a group
	 * @param repositoryId TODO
	 * @param group
	 * @return newly created group
	 */
	Group createGroup(String repositoryId, Group group);

	/**
	 * Update a user
	 * @param repositoryId TODO
	 * @param user
	 * @return updated user
	 */
	User updateUser(String repositoryId, User user);

	/**
	 * Update a group
	 * @param repositoryId TODO
	 * @param group
	 * @return updated group
	 */
	Group updateGroup(String repositoryId, Group group);

	/**
	 * Delete a user / group
	 * @param repositoryId TODO
	 * @param clazz
	 * @param id
	 */
	void delete(String repositoryId, Class<?> clazz, String nodeId);
}