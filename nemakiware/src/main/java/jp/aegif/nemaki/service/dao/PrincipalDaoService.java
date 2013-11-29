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
package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * DAO Service interface.
 */
public interface PrincipalDaoService {
	User getUserById(String userId);
	List<User> getUsers();
	Group getGroupById(String groupId);
	List<Group> getGroups();
	User createUser(User user);
	Group createGroup(Group group);
	User updateUser(User user);
	Group updateGroup(Group group);
	void delete(Class<?> clazz, String id);
}