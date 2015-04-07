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
package jp.aegif.nemaki.businesslogic.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.dao.PrincipalDaoService;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.constant.PrincipalId;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Principal(User / Group) Service implementation.
 *
 */
public class PrincipalServiceImpl implements PrincipalService {

	private static final Log log = LogFactory.getLog(PrincipalServiceImpl.class);

	private PrincipalDaoService principalDaoService;
	private String anonymous;
	private String anyone;

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

		//Anonymous user doesn't belong to any group, even to Anyone.
		if(userId.equals(anonymous)){
			return groupIds;
		}

		List<Group> groups = getGroups();
		for (Group g : groups) {
			if ( containsUserInGroup(userId, g) ) {
				groupIds.add(g.getGroupId());
			}
		}
		groupIds.add(anyone);
		return groupIds;
	}

	private boolean containsUserInGroup(String userId, Group group) {
		log.debug("$$ group:" + group.getName());
		if ( group.getUsers().contains(userId))
			return true;
		for(String groupId: group.getGroups() ) {
			log.debug("$$ subgroup: " + groupId);
			Group g = this.getGroupById(groupId);
			boolean result = containsUserInGroup(userId, g);
			if ( result ) return true;
		}
		return false;
	}

	@Override
	public Group getGroupById(String groupId) {
		return principalDaoService.getGroupById(groupId);
	}

	@Override
	public User getUserById(String id) {
		return principalDaoService.getUserById(id);
	}

	@Override
	public synchronized void createUser(User user) {
		//UserID uniqueness
		List<String> principalIds = getPrincipalIds();
		if(principalIds.contains(user.getUserId())){
			log.error("userId=" + user.getUserId() + " already exists.");
		}

		principalDaoService.createUser(user);
	}

	@Override
	public synchronized void updateUser(User user) {
		principalDaoService.updateUser(user);
	}

	@Override
	public synchronized void deleteUser(String id) {
		principalDaoService.delete(User.class, id);
	}

	@Override
	public synchronized void createGroup(Group group) {
		//GroupID uniqueness
		List<String> principalIds = getPrincipalIds();
		if(principalIds.contains(group.getGroupId())){
			log.error("groupId=" + group.getGroupId() + " already exists.");
		}

		principalDaoService.createGroup(group);
	}

	@Override
	public synchronized void updateGroup(Group group) {
		principalDaoService.updateGroup(group);
	}

	@Override
	public synchronized void deleteGroup(String id) {
		principalDaoService.delete(Group.class, id);
	}

	private List<String> getPrincipalIds(){
		List<String> principalIds = new ArrayList<String>();

		//UserId
		List<User> users = principalDaoService.getUsers();
		for(User u : users){
			if(principalIds.contains(u.getUserId())){
				log.warn("userId=" + u.getUserId() + " is duplicate in the database.");
			}
			principalIds.add(u.getUserId());
		}

		//GroupId
		List<Group> groups = principalDaoService.getGroups();
		for(Group g : groups){
			if(principalIds.contains(g.getGroupId())){
				log.warn("groupId=" + g.getGroupId() + " is duplicate in the database.");
			}
			principalIds.add(g.getGroupId());
		}

		//Anonymous
		if(principalIds.contains(anonymous)){
			log.warn("CMIS 'anonymous':" +  anonymous + " should have not been registered in the database.");
		}
		principalIds.add(anonymous);
		if(principalIds.contains(PrincipalId.ANONYMOUS_IN_DB)){
			log.warn("CMIS 'anonymous':" +  anonymous + " should have not been registered in the database.(For system use)");
		}
		principalIds.add(PrincipalId.ANONYMOUS_IN_DB);

		//Anyone
		if(principalIds.contains(anyone) ){
			log.warn("CMIS 'anyone': " + anyone + " should have not been registered in the database.");
		}
		principalIds.add(anyone);
		if(principalIds.contains(PrincipalId.ANYONE_IN_DB)){
			log.warn("CMIS 'anyone':" + PrincipalId.ANYONE_IN_DB + " should have not been registered in the database.(For system use)");
		}
		principalIds.add(PrincipalId.ANYONE_IN_DB);

		return principalIds;
	}

	@Override
	public User getAdmin() {
		return principalDaoService.getAdmin();
	}

	@Override
	public String getAnonymous() {
		return anonymous;
	}

	public void setAnonymous(String anonymous) {
		this.anonymous = anonymous;
	}

	@Override
	public String getAnyone() {
		return anyone;
	}

	public void setAnyone(String anyone) {
		this.anyone = anyone;
	}

	public void setPrincipalDaoService(PrincipalDaoService principalDaoService) {
		this.principalDaoService = principalDaoService;
	}
}
