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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * DAO Service interface.
 */
public interface PrincipalDaoService {

	/**
	 * Create a node
	 */
	User createUser(User user);
	Group createGroup(Group group);
	
	/**
	 * Update a node
	 * @param node
	 */
	User updateUser(User user);
	Group updateGroup(Group group);
	/**
	 * Delete a principal
	 * @param clazz
	 * @param id
	 */
	void delete(Class<?> clazz, String id);
	
	/**
	 * Get all users from DB
	 */
	List<User> getUsers();

	/**
	 * Get an user with id
	 */
	User getUserById(String userId);
	
	/**
	 * Get an user with user name
	 */
	User getUserByName(String name);

	/**
	 * Get a group, given its id
	 */
	Group getGroupById(String groupId);

	Group getGroupByName(String name);
	
	/**
	 * Get all groups
	 */
	List<Group> getGroups();
		
	
}
