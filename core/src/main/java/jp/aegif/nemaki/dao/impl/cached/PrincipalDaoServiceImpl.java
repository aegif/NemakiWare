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
package jp.aegif.nemaki.dao.impl.cached;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.dao.PrincipalDaoService;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

/**
 * Dao Service for Principal(User/Group) Implementation for CouchDB
 *
 * @author linzhixing
 */
@Component
public class PrincipalDaoServiceImpl implements PrincipalDaoService {

	private static final Log log = LogFactory
			.getLog(PrincipalDaoServiceImpl.class);

	private PrincipalDaoService nonCachedPrincipalDaoService;
	private NemakiCachePool nemakiCachePool;

	public PrincipalDaoServiceImpl() {
	}

	@Override
	public User createUser(String repositoryId, User user) {
		User created = nonCachedPrincipalDaoService.createUser(repositoryId, user);

		Cache userCache = nemakiCachePool.get(repositoryId).getUserCache();
		userCache.put(new Element(created.getUserId(), created));
		Cache usersCache = nemakiCachePool.get(repositoryId).getUsersCache();
		usersCache.removeAll();
		return created;
	}

	@Override
	public Group createGroup(String repositoryId, Group group) {
		Group created = nonCachedPrincipalDaoService.createGroup(repositoryId, group);

		Cache groupCache = nemakiCachePool.get(repositoryId).getGroupCache();
		groupCache.put(new Element(created.getGroupId(), created));
		Cache groupsCache = nemakiCachePool.get(repositoryId).getGroupsCache();
		groupsCache.removeAll();
		return created;
	}

	@Override
	public User updateUser(String repositoryId, User user) {
		User u = nonCachedPrincipalDaoService.updateUser(repositoryId, user);

		Cache userCache = nemakiCachePool.get(repositoryId).getUserCache();
		userCache.put(new Element(u.getUserId(), u));
		Cache usersCache = nemakiCachePool.get(repositoryId).getUsersCache();
		usersCache.removeAll();

		return u;
	}

	@Override
	public Group updateGroup(String repositoryId, Group group) {
		Group g = nonCachedPrincipalDaoService.updateGroup(repositoryId, group);

		Cache groupCache = nemakiCachePool.get(repositoryId).getGroupCache();
		groupCache.put(new Element(g.getGroupId(), g));

		Cache groupsCache = nemakiCachePool.get(repositoryId).getGroupsCache();
		groupsCache.removeAll();

		return g;
	}

	@Override
	public void delete(String repositoryId, Class<?> clazz, String nodeId) {
		if (clazz.equals(User.class)) {
			User exising = getUser(repositoryId, nodeId);

			nonCachedPrincipalDaoService.delete(repositoryId, null, nodeId);

			Cache userCache = nemakiCachePool.get(repositoryId).getUserCache();
			userCache.remove(exising.getUserId());
			Cache usersCache = nemakiCachePool.get(repositoryId).getUsersCache();
			usersCache.removeAll();
		} else if(clazz.equals(Group.class)){
			Group exising = getGroup(repositoryId, nodeId);

			nonCachedPrincipalDaoService.delete(repositoryId, null, nodeId);

			Cache groupCache = nemakiCachePool.get(repositoryId).getGroupCache();
			groupCache.remove(exising.getGroupId());
			Cache groupsCache = nemakiCachePool.get(repositoryId).getGroupsCache();
			groupsCache.removeAll();
		}
	}

	@Override
	public User getUser(String repositoryId, String nodeId) {
		Cache userCache = nemakiCachePool.get(repositoryId).getUserCache();

		User user = nonCachedPrincipalDaoService.getUser(repositoryId, nodeId);
		if (user != null) {
			userCache.put(new Element(user.getUserId(), user));
		}

		return user;
	}

	@Override
	public List<User> getAdmins(String repositoryId) {
		List<User> admin = nonCachedPrincipalDaoService.getAdmins(repositoryId);
		return admin;
	}

	/**
	 *
	 */
	@Override
	public User getUserById(String repositoryId, String userId) {

		Cache userCache = nemakiCachePool.get(repositoryId).getUserCache();
		Element e = userCache.get(userId);

		if (e != null) {
			return (User) e.getObjectValue();
		}

		User user = nonCachedPrincipalDaoService.getUserById(repositoryId, userId);
		if (user != null) {
			userCache.put(new Element(userId, user));
		}

		return user;
	}

	/**
	 *
	 */
	@Override
	public List<User> getUsers(String repositoryId) {
		Cache usersCache = nemakiCachePool.get(repositoryId).getUsersCache();
		Element userEl = usersCache.get("users");

		if (userEl != null) {
			return (List<User>) userEl.getObjectValue();
		}

		List<User> users = nonCachedPrincipalDaoService.getUsers(repositoryId);

		usersCache.put(new Element("users", users));

		return users;

	}

	@Override
	public Group getGroup(String repositoryId, String nodeId) {
		Cache groupCache = nemakiCachePool.get(repositoryId).getGroupCache();

		Group group = nonCachedPrincipalDaoService.getGroup(repositoryId, nodeId);
		if (group != null) {
			groupCache.put(new Element(group.getGroupId(), group));
		}

		return group;
	}

	/**
	 *
	 */
	@Override
	public Group getGroupById(String repositoryId, String groupId) {
		Cache groupCache = nemakiCachePool.get(repositoryId).getGroupCache();
		Element groupEl = groupCache.get(groupId);

		if (groupEl != null) {
			return (Group) groupEl.getObjectValue();
		}

		Group group = nonCachedPrincipalDaoService.getGroupById(repositoryId, groupId);
		if (group != null) {
			groupCache.put(new Element(groupId, group));
		}

		return group;
	}

	/**
	 *
	 */
	@Override
	public List<Group> getGroups(String repositoryId) {
		Cache groupsCache = nemakiCachePool.get(repositoryId).getGroupsCache();
		Element groupEl = groupsCache.get("groups");

		if (groupEl != null) {
			return (List<Group>) groupEl.getObjectValue();
		}

		List<Group> groups = nonCachedPrincipalDaoService.getGroups(repositoryId);

		groupsCache.put(new Element("groups", groups));

		return groups;
	}

	public void setNonCachedPrincipalDaoService(
			PrincipalDaoService nonCachedPrincipalDaoService) {
		this.nonCachedPrincipalDaoService = nonCachedPrincipalDaoService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}
}