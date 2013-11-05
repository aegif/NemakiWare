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
import java.util.Map;

import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.couch.CouchGroup;
import jp.aegif.nemaki.model.couch.CouchUser;
import jp.aegif.nemaki.service.dao.PrincipalDaoService;
import jp.aegif.nemaki.service.db.CouchConnector;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.CouchDbConnector;
import org.ektorp.ViewQuery;
import org.springframework.stereotype.Component;

import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

/**
 * Dao Service for Principal(User/Group)
 * Implementation for CouchDB
 * @author linzhixing
 */
@Component
public class PrincipalDaoServiceImpl implements PrincipalDaoService {

	private CouchDbConnector connector;
	private static final Log log = LogFactory.getLog(PrincipalDaoServiceImpl.class);
	
	private CacheManager cacheManager;
	
	/**
	 * 
	 */
	
	public PrincipalDaoServiceImpl() {
		cacheManager = CacheManager.newInstance();
		Cache userCache = new Cache("userCache", 10000, false, false, 60 * 60 , 60 * 60);
		Cache usersCache = new Cache("usersCache", 1, false, false, 60 * 60 , 60 * 60);
		Cache groupCache = new Cache("groupCache", 10000, false, false, 60 * 60 , 60 * 60);
		Cache groupsCache = new Cache("groupsCache", 1, false, false, 60 * 60 , 60 * 60);
		cacheManager.addCache(userCache);
		cacheManager.addCache(usersCache);
		cacheManager.addCache(groupCache);
		cacheManager.addCache(groupsCache);
	}
	
	@Override
	public User createUser(User user) {
		CouchUser cu = new CouchUser(user);
		connector.create(cu);
		User created = cu.convert();
		Cache userCache = cacheManager.getCache("userCache");
		userCache.put(new Element(created.getId(), created));
		Cache usersCache = cacheManager.getCache("usersCache");
		usersCache.removeAll();
		return created;
	}

	@Override
	public Group createGroup(Group group) {
		CouchGroup cg = new CouchGroup(group);
		connector.create(cg);
		Group created = cg.convert();
		Cache groupCache = cacheManager.getCache("groupCache");
		groupCache.put(new Element(created.getId(), created));
		Cache groupsCache = cacheManager.getCache("groupsCache");
		groupsCache.removeAll();		
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
		Cache userCache = cacheManager.getCache("userCache");
		userCache.put(new Element(u.getId(), u));
		Cache usersCache = cacheManager.getCache("usersCache");
		usersCache.removeAll();
		
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
		Cache groupCache = cacheManager.getCache("groupCache");
		groupCache.put(new Element(g.getId(), g));
		
		Cache groupsCache = cacheManager.getCache("groupsCache");
		groupsCache.removeAll();
		
		return g;
	}
	
	@Override
	public void delete(Class<?> clazz, String principalId) {
		if(clazz.equals(User.class)){
			CouchUser cu = getUserByIdInternal(principalId);
			connector.delete(cu);
			Cache userCache = cacheManager.getCache("userCache");
			userCache.remove(principalId);
			Cache usersCache = cacheManager.getCache("usersCache");
			usersCache.removeAll();
		}else if(clazz.equals(Group.class)){
			CouchGroup cg = getGroupByIdInternal(principalId);
			connector.delete(cg);
			Cache groupCache = cacheManager.getCache("groupCache");
			groupCache.remove(principalId);
			Cache groupsCache = cacheManager.getCache("groupsCache");
			groupsCache.removeAll();
		}else{
			log.error("Cannot delete other than user or group");
		}
	}

	/**
	 * 
	 */
	@Override
	public List<User> getUsers() {
		Cache usersCache = cacheManager.getCache("usersCache");
		Element userEl = usersCache.get("users");
		
		if ( userEl != null ) {
			return (List<User>)userEl.getObjectValue();
		}
		
		List<User> users = new ArrayList<User>();
		
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("usersById");
		List<CouchUser> l = connector.queryView(query, CouchUser.class);
		
		for(CouchUser c : l){
			User u = c.convert();
			users.add(u);
		}
		usersCache.put(new Element("users", users));
		
		return users;

	}
	
	/**
	 * 
	 */
	@Override
	public User getUserById(String userId) {
		
		Cache userCache = cacheManager.getCache("userCache");
		Element e = userCache.get(userId);
		
		if ( e != null ) {
			return (User)e.getObjectValue();
		}
		
		CouchUser cu = getUserByIdInternal(userId);
		if(cu == null){
			return null;
		}else{
			User u = cu.convert();
			userCache.put(new Element(userId, u));
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

	/**
	 * 
	 */
	@Override
	public User getUserByName(String userName) {
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("usersByName").key(userName);
		List<CouchUser> l = connector.queryView(query, CouchUser.class);
		
		if(CollectionUtils.isEmpty(l)) return null;
			return l.get(0).convert();
	}
	
	/**
	 * 
	 */
	@Override
	public Group getGroupById(String groupId) {
		Cache groupCache = cacheManager.getCache("groupCache");
		Element groupEl = groupCache.get(groupId);
		
		if ( groupEl != null ) {
			return (Group)groupEl.getObjectValue();
		}
		
		CouchGroup cg = getGroupByIdInternal(groupId);
		if(cg == null){
			return null;
		}else{
			Group g = cg.convert();
			groupCache.put(new Element(groupId, g));
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

	/**
	 * 
	 */
	@Override
	public List<Group> getGroups() {
	    

		Cache groupsCache = cacheManager.getCache("groupsCache");
		Element groupEl = groupsCache.get("groups");
		
		if ( groupEl != null ) {
			return (List<Group>)groupEl.getObjectValue();
		}
		
		List<Group> groups = new ArrayList<Group>();
		
		
		ViewQuery query = new ViewQuery().designDocId("_design/_repo")
				.viewName("groupsById");
		
		List<CouchGroup>l = connector.queryView(query, CouchGroup.class);
		
		for(CouchGroup c : l){
			Group g = c.convert();
			groups.add(g);
		}
		
		groupsCache.put(new Element("groups", groups));
		
		return groups;
	}
	
	/**
	 * 
	 * @param objectId
	 * @param msg
	 * @return
	 */
	private String buildLogMsg(String id, String msg){
		return "[ID:" + id + "]" + msg;
	}
	
	public void setConnector(CouchConnector connector) {
		this.connector = connector.getConnection();
	}
}
