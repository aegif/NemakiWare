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
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
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

	private RepositoryInfoMap repositoryInfoMap; 
	private PrincipalDaoService principalDaoService;

	@Override
	public List<User> getUsers(String repositoryId) {
		//refresh to cope with new user without restarting the server
		List<User> users = principalDaoService.getUsers(repositoryId);
		return users;
	}

	@Override
	public List<Group> getGroups(String repositoryId) {
		//refresh to cope with new group without restarting the server
		List<Group> groups = principalDaoService.getGroups(repositoryId);
		return groups;
	}

	@Override
	public Set<String> getGroupIdsContainingUser(String repositoryId, String userId) {
		String anonymous = getAnonymous(repositoryId);
		
		Set<String> groupIds = new HashSet<String>();

		//Anonymous user doesn't belong to any group, even to Anyone.
		if(userId.equals(anonymous)){
			return groupIds;
		}

		List<Group> groups = getGroups(repositoryId);
		for (Group g : groups) {
			if ( containsUserInGroup(repositoryId, userId, g) ) {
				groupIds.add(g.getGroupId());
			}
		}
		// Note: "anyone" principal is handled separately in ACLExpander.buildReaderFilterQuery()
		// by always including READER_ANYONE in the filter. Do NOT add the "anyone" principal
		// (e.g., GROUP_EVERYONE) to the user's group list, as that would grant access to
		// documents explicitly assigned to GROUP_EVERYONE even if the user is not a member.
		return groupIds;
	}

	private boolean containsUserInGroup(String repositoryId, String userId, Group group) {
		log.debug("$$ group:" + group.getName());
		// Null check for users list
		if (group.getUsers() != null && group.getUsers().contains(userId))
			return true;
		// Null check for groups list
		if (group.getGroups() != null) {
			for(String groupId: group.getGroups() ) {
				log.debug("$$ subgroup: " + groupId);
				Group g = this.getGroupById(repositoryId, groupId);
				if (g != null) {
					boolean result = containsUserInGroup(repositoryId, userId, g);
					if ( result ) return true;
				}
			}
		}
		return false;
	}

	@Override
	public Group getGroupById(String repositoryId, String groupId) {
		return principalDaoService.getGroupById(repositoryId, groupId);
	}

	@Override
	public User getUserById(String repositoryId, String id) {
		return principalDaoService.getUserById(repositoryId, id);
	}

	@Override
	public synchronized void createUser(String repositoryId, User user) {
		//UserID uniqueness
		List<String> principalIds = getPrincipalIds(repositoryId);
		if(principalIds.contains(user.getUserId())){
			log.error("userId=" + user.getUserId() + " already exists.");
		}

		principalDaoService.createUser(repositoryId, user);
	}

	@Override
	public synchronized void updateUser(String repositoryId, User user) {
		principalDaoService.updateUser(repositoryId, user);
	}

	@Override
	public synchronized void deleteUser(String repositoryId, String id) {
		principalDaoService.delete(repositoryId, User.class, id);
	}

	@Override
	public synchronized void createGroup(String repositoryId, Group group) {
		//GroupID uniqueness
		List<String> principalIds = getPrincipalIds(repositoryId);
		if(principalIds.contains(group.getGroupId())){
			log.error("groupId=" + group.getGroupId() + " already exists.");
		}

		principalDaoService.createGroup(repositoryId, group);
	}

	@Override
	public synchronized void updateGroup(String repositoryId, Group group) {
		principalDaoService.updateGroup(repositoryId, group);
	}

	@Override
	public synchronized void deleteGroup(String repositoryId, String id) {
		principalDaoService.delete(repositoryId, Group.class, id);
	}

	private List<String> getPrincipalIds(String repositoryId){
		String anonymous = getAnonymous(repositoryId);
		String anyone = getAnyone(repositoryId);
		
		List<String> principalIds = new ArrayList<String>();

		//UserId
		List<User> users = principalDaoService.getUsers(repositoryId);
		for(User u : users){
			if(principalIds.contains(u.getUserId())){
				log.warn("userId=" + u.getUserId() + " is duplicate in the database.");
			}
			principalIds.add(u.getUserId());
		}

		//GroupId
		List<Group> groups = principalDaoService.getGroups(repositoryId);
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
	public List<User> getAdmins(String repositoryId) {
		return principalDaoService.getAdmins(repositoryId);
	}

	public void setPrincipalDaoService(PrincipalDaoService principalDaoService) {
		this.principalDaoService = principalDaoService;
	}

	@Override
	public String getAnonymous(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnonymous();
	}

	@Override
	public String getAnyone(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnyone();
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
	
}
