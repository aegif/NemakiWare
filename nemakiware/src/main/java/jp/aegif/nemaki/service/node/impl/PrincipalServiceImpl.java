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
package jp.aegif.nemaki.service.node.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.service.dao.PrincipalDaoService;
import jp.aegif.nemaki.service.dao.impl.PrincipalDaoServiceImpl;
import jp.aegif.nemaki.service.node.PrincipalService;

/**
 * Principal(User / Group) Service implementation.
 * 
 */
public class PrincipalServiceImpl implements PrincipalService {
	
	private static final Log log = LogFactory.getLog(PrincipalServiceImpl.class);

	private PrincipalDaoService principalDaoService;

	@Override
	public List<User> getUsers() {
		//refresh to cope with new user without restarting the server
		List<User> users = principalDaoService.getUsers();
		return users;
	}
	
	@Override
	public List<Group> getGroups() {
		//refresh to cope with new group without restarting the server
		List<Group> groups = principalDaoService.getGroups();
		return groups;
	}

	@Override
	public Set<String> getGroupIdsContainingUser(String userId) {
		Set<String> groupIds = new HashSet<String>();
		List<Group> groups = getGroups();
		for (Group g : groups) {
			if ( cnotainsUserInGroup(userId, g) ) {
				groupIds.add(g.getGroupId());
			}
		}
		groupIds.add(PrincipalService.GROUP_EVERYONE);
		return groupIds;
	}
	
	private boolean cnotainsUserInGroup(String userId, Group group) {
		log.debug("$$ group:" + group.getName());
		if ( group.getUsers().contains(userId)) 
			return true;
		for(String groupId: group.getGroups() ) {
			log.debug("$$ subgroup: " + groupId);
			Group g = this.getGroupById(groupId);
			boolean result = cnotainsUserInGroup(userId, g);
			if ( result ) return true;
		}
		return false;
	}

	@Override
	public User getUserByName(String username) {
		return principalDaoService.getUserByName(username);
	}

	@Override
	public Group getGroupById(String groupId) {
		return principalDaoService.getGroupById(groupId);
	}

	public void setPrincipalDaoService(PrincipalDaoService principalDaoService) {
		this.principalDaoService = principalDaoService;
	}

	@Override
	public User getUserById(String id) {
		return (User) principalDaoService.getUserById(id);
	}

	@Override
	public void createUser(User user) {
		principalDaoService.createUser(user);
	}

	@Override
	public void updateUser(User user) {
		principalDaoService.updateUser(user);
	}

	@Override
	public void deleteUser(String id) {
		principalDaoService.delete(User.class, id);
	}

	@Override
	public void createGroup(Group group) {
		principalDaoService.createGroup(group);
	}

	@Override
	public void updateGroup(Group group) {
		principalDaoService.updateGroup(group);
	}

	@Override
	public void deleteGroup(String id) {
		principalDaoService.delete(Group.class, id);
	}
}
