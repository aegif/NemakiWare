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
package jp.aegif.nemaki.service.dao.impl;

import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.couch.CouchGroup;
import jp.aegif.nemaki.model.couch.CouchUser;
import jp.aegif.nemaki.service.dao.NonCachedPrincipalDaoService;
import jp.aegif.nemaki.service.db.CouchConnector;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.CouchDbConnector;
import org.ektorp.ViewQuery;
import org.springframework.stereotype.Component;

/**
 * Dao Service for Principal(User/Group)
 * Implementation for CouchDB
 * @author linzhixing
 */
@Component
public class CouchPrincipalDaoServiceImpl implements NonCachedPrincipalDaoService {

	private CouchDbConnector connector;
	private static final Log logger = LogFactory.getLog(CouchPrincipalDaoServiceImpl.class);
	
	
	/**
	 * 
	 */
	
	public CouchPrincipalDaoServiceImpl() {

	}
	

	@Override
	public User createUser(User user) {
		CouchUser cu = new CouchUser(user);
		connector.create(cu);
		User created = cu.convert();
		return created;
	}

	
	@Override
	public Group createGroup(Group group) {
		CouchGroup cg = new CouchGroup(group);
		connector.create(cg);
		Group created = cg.convert();
		return created;
	}

	@Override
	public User updateUser(User user) {
		CouchUser cd = connector.get(CouchUser.class, user.getId());
		
		//Set the latest revision for avoid conflict
		CouchUser update = new CouchUser(user);
		update.setRevision(cd.getRevision());

		connector.update(update);
		User u = update.convert();
		
		return u;
	}
	
	@Override
	public Group updateGroup(Group group) {
		CouchGroup cd = connector.get(CouchGroup.class, group.getId());
		
		//Set the latest revision for avoid conflict
		CouchGroup update = new CouchGroup(group);
		update.setRevision(cd.getRevision());

		connector.update(update);
		Group g = update.convert();
		
		return g;
	}
	
	@Override
	public void deleteUser(String principalId) {
		CouchUser cu = getUserByIdInternal(principalId);
		connector.delete(cu);		
	}


	@Override
	public void deleteGroup(String principalId) {
		CouchGroup cg = getGroupByIdInternal(principalId);
		connector.delete(cg);
	}


	@Override
	public List<User> getUsers() {
		List<User> users = new ArrayList<User>();
		
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("usersById");
		List<CouchUser> l = connector.queryView(query, CouchUser.class);
		
		for(CouchUser c : l){
			User u = c.convert();
			users.add(u);
		}
		
		return users;
	}
	
	@Override
	public User getUserById(String userId) {
		CouchUser cu = getUserByIdInternal(userId);
		if(cu == null){
			return null;
		}else{
			User u = cu.convert();
			return u;
		}
	}
	
	private CouchUser getUserByIdInternal(String userId){
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("usersById").key(userId);
		List<CouchUser> l = connector.queryView(query, CouchUser.class);
		
		if(CollectionUtils.isEmpty(l)) return null;
		return l.get(0);
	}

	@Override
	public User getUserByName(String userName) {
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("usersByName").key(userName);
		List<CouchUser> l = connector.queryView(query, CouchUser.class);
		
		if(CollectionUtils.isEmpty(l)) return null;
			return l.get(0).convert();
	}
	
	@Override
	public Group getGroupById(String groupId) {
		CouchGroup cg = getGroupByIdInternal(groupId);
		if(cg == null){
			return null;
		}else{
			Group g = cg.convert();
			return g;
		}
	}
	
	private CouchGroup getGroupByIdInternal(String groupId){
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("groupsById").key(groupId);
		List<CouchGroup> l = connector.queryView(query, CouchGroup.class);
		
		if(CollectionUtils.isEmpty(l)) return null;
		return l.get(0);
	}

	@Override
	public Group getGroupByName(String groupName) {
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("groupsByName").key(groupName);
		List<CouchGroup> l = connector.queryView(query, CouchGroup.class);
		
		if(CollectionUtils.isEmpty(l)) return null;
			return l.get(0).convert();
	}

	@Override
	public List<Group> getGroups() {
		List<Group> groups = new ArrayList<Group>();
		
		
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("groupsById");
		
		List<CouchGroup>l = connector.queryView(query, CouchGroup.class);
		
		for(CouchGroup c : l){
			Group g = c.convert();
			groups.add(g);
		}
		
		return groups;
	}
	
	public void setConnector(CouchConnector connector) {
		this.connector = connector.getConnection();
	}
}
