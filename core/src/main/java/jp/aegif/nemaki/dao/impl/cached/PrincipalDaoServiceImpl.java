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
		// A principal write on the legacy path counts too: the readers projection memoises
		// principal KIND keyed on this generation, so a create/update/delete that does not
		// advance it leaves that memo valid until its TTL. Cheap, and it closes the window.
		jp.aegif.nemaki.util.cache.PrincipalGeneration.advance(repositoryId);
		return created;
	}

	@Override
	public Group createGroup(String repositoryId, Group group) {
		Group created = nonCachedPrincipalDaoService.createGroup(repositoryId, group);
		// A principal write on the legacy path counts too: the readers projection memoises
		// principal KIND keyed on this generation, so a create/update/delete that does not
		// advance it leaves that memo valid until its TTL. Cheap, and it closes the window.
		jp.aegif.nemaki.util.cache.PrincipalGeneration.advance(repositoryId);
		return created;
	}

	@Override
	public User updateUser(String repositoryId, User user) {
		User u = nonCachedPrincipalDaoService.updateUser(repositoryId, user);
		// A principal write on the legacy path counts too: the readers projection memoises
		// principal KIND keyed on this generation, so a create/update/delete that does not
		// advance it leaves that memo valid until its TTL. Cheap, and it closes the window.
		jp.aegif.nemaki.util.cache.PrincipalGeneration.advance(repositoryId);
		return u;
	}

	@Override
	public Group updateGroup(String repositoryId, Group group) {
		Group g = nonCachedPrincipalDaoService.updateGroup(repositoryId, group);
		// A principal write on the legacy path counts too: the readers projection memoises
		// principal KIND keyed on this generation, so a create/update/delete that does not
		// advance it leaves that memo valid until its TTL. Cheap, and it closes the window.
		jp.aegif.nemaki.util.cache.PrincipalGeneration.advance(repositoryId);
		return g;
	}

	@Override
	public void delete(String repositoryId, Class<?> clazz, String nodeId) {
		// A principal write on the legacy path counts too: the readers projection memoises
		// principal KIND keyed on this generation, so a create/update/delete that does not
		// advance it leaves that memo valid until its TTL. Cheap, and it closes the window.
		jp.aegif.nemaki.util.cache.PrincipalGeneration.advance(repositoryId);
		if (clazz.equals(User.class)) {
			getUser(repositoryId, nodeId);
			nonCachedPrincipalDaoService.delete(repositoryId, null, nodeId);
		} else if(clazz.equals(Group.class)){
			getGroup(repositoryId, nodeId);
			nonCachedPrincipalDaoService.delete(repositoryId, null, nodeId);
		}
	}

	@Override
	public User getUser(String repositoryId, String nodeId) {
		User user = nonCachedPrincipalDaoService.getUser(repositoryId, nodeId);
		return user;
	}

	@Override
	public List<User> getAdmins(String repositoryId) {
		List<User> admin = nonCachedPrincipalDaoService.getAdmins(repositoryId);
		return admin;
	}

	@Override
	public User getUserById(String repositoryId, String userId) {
		User user = nonCachedPrincipalDaoService.getUserById(repositoryId, userId);
		return user;
	}

	/**
	 * Increment 5T, now memoised for a few seconds — see {@link jp.aegif.nemaki.acl.PrincipalLookupCache}
	 * for why that does not reintroduce the staleness these by-id reads were kept out of the caches
	 * to avoid (UNAVAILABLE is never stored, and every principal write invalidates the memo).
	 *
	 * <p>This is the hot path of the readers projection: one lookup per ACE, per node, on a
	 * traversal that visits every inheriting descendant.
	 */
	@Override
	public jp.aegif.nemaki.acl.PrincipalLookup lookupUserById(String repositoryId, String userId) {
		return jp.aegif.nemaki.acl.PrincipalLookupCache.get(repositoryId, "user", userId,
				() -> nonCachedPrincipalDaoService.lookupUserById(repositoryId, userId));
	}

	@Override
	public List<User> getUsers(String repositoryId) {
		List<User> users = nonCachedPrincipalDaoService.getUsers(repositoryId);
		return users;

	}

	@Override
	public Group getGroup(String repositoryId, String nodeId) {
		Group group = nonCachedPrincipalDaoService.getGroup(repositoryId, nodeId);
		return group;
	}

	@Override
	public Group getGroupById(String repositoryId, String groupId) {
		Group group = nonCachedPrincipalDaoService.getGroupById(repositoryId, groupId);
		return group;
	}

	/** Increment 5T. See {@link #lookupUserById}. */
	@Override
	public jp.aegif.nemaki.acl.PrincipalLookup lookupGroupById(String repositoryId, String groupId) {
		return jp.aegif.nemaki.acl.PrincipalLookupCache.get(repositoryId, "group", groupId,
				() -> nonCachedPrincipalDaoService.lookupGroupById(repositoryId, groupId));
	}

	@Override
	public List<Group> getGroups(String repositoryId) {
		List<Group> groups = nonCachedPrincipalDaoService.getGroups(repositoryId);
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