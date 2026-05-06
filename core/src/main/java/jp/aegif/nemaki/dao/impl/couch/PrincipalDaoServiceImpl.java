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
package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import jp.aegif.nemaki.dao.PrincipalDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.couch.CouchGroup;
import jp.aegif.nemaki.model.couch.CouchNodeBase;
import jp.aegif.nemaki.model.couch.CouchUser;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

/**
 * Dao Service for Principal(User/Group) Implementation for CouchDB
 *
 * @author linzhixing
 */
@Component
public class PrincipalDaoServiceImpl implements
		PrincipalDaoService {

	private CloudantClientPool connectorPool;
	private static final String DESIGN_DOCUMENT = "_design/_repo";

	public PrincipalDaoServiceImpl() {

	}


	@Override
	public User getUser(String repositoryId, String nodeId) {
		List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "users", nodeId, CouchUser.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public User getUserById(String repositoryId, String userId) {
		CouchUser cu = getUserByIdInternal(repositoryId, userId);
		if (cu == null) {
			return null;
		} else {
			User u = cu.convert();
			return u;
		}
	}

	private CouchUser getUserByIdInternal(String repositoryId, String userId) {
		List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "userItemsById", userId, CouchUser.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<User> getUsers(String repositoryId) {
		List<User> users = new ArrayList<User>();

		List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "userItemsById", CouchUser.class);

		for (CouchUser c : l) {
			User u = c.convert();
			users.add(u);
		}

		return users;
	}

	@Override
	public List<User> getAdmins(String repositoryId) {
		List<User> admins = new ArrayList<User>();

		try {
			// Use proper design document view query (restored from migration)
			List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "admin", CouchUser.class);

			for (CouchUser c : l) {
				User u = c.convert();
				admins.add(u);
			}
		} catch (Exception e) {
			// Fail closed: do NOT grant admin on CouchDB failure.
			// The previous fallback created a synthetic admin user, which meant
			// any transient DB outage would silently grant admin privileges.
			org.slf4j.LoggerFactory.getLogger(PrincipalDaoServiceImpl.class)
					.error("Failed to query admin users from CouchDB — returning empty list (fail-closed)", e);
		}

		return admins;
	}


	@Override
	public Group getGroup(String repositoryId, String nodeId) {
		List<CouchGroup> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "groups", nodeId, CouchGroup.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public Group getGroupById(String repositoryId, String groupId) {
		CouchGroup cg = getGroupByIdInternal(repositoryId, groupId);
		if (cg == null) {
			return null;
		} else {
			Group g = cg.convert();
			return g;
		}
	}

	private CouchGroup getGroupByIdInternal(String repositoryId, String groupId) {
		List<CouchGroup> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "groupItemsById", groupId, CouchGroup.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<Group> getGroups(String repositoryId) {
		List<Group> groups = new ArrayList<Group>();

		List<CouchGroup> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "groupItemsById", CouchGroup.class);

		if (l != null) {
			for (CouchGroup c : l) {
				Group g = c.convert();
				groups.add(g);
			}
		}

		return groups;
	}

	@Override
	public User createUser(String repositoryId, User user) {
		CouchUser cu = new CouchUser(user);
		// EKTORP-STYLE: CloudantClientWrapper create() will set ID and revision back to cu object
		connectorPool.getClient(repositoryId).create(cu);
		// After create(), cu now has the database-generated ID and revision
		User created = cu.convert();
		return created;
	}

	@Override
	public Group createGroup(String repositoryId, Group group) {
		CouchGroup cg = new CouchGroup(group);
		// EKTORP-STYLE: CloudantClientWrapper create() will set ID and revision back to cg object
		connectorPool.getClient(repositoryId).create(cg);
		// After create(), cg now has the database-generated ID and revision
		Group created = cg.convert();
		return created;
	}

	@Override
	public User updateUser(String repositoryId, User user) {
		// Read the existing document first to preserve all fields.
		// CouchNodeBase's @JsonAnySetter captures fields not modeled by CouchUser
		// (e.g., objectType, subTypeProperties, acl from UserItem documents)
		// into additionalProperties, and @JsonAnyGetter writes them back.
		// This prevents cross-contamination when a UserItem document is updated
		// through this principal DAO path.
		CouchUser existing = connectorPool.getClient(repositoryId).get(CouchUser.class, user.getId());
		if (existing == null) {
			return null;
		}

		// Merge only User-specific fields onto the existing document
		existing.setUserId(user.getUserId());
		existing.setName(user.getName());
		existing.setFirstName(user.getFirstName());
		existing.setLastName(user.getLastName());
		existing.setEmail(user.getEmail());
		if (user.getPasswordHash() != null) {
			existing.setPasswordHash(user.getPasswordHash());
		}
		existing.setAdmin(user.isAdmin());
		existing.setFavorites(user.getFavorites());

		connectorPool.getClient(repositoryId).update(existing);
		User u = existing.convert();

		return u;
	}

	@Override
	public Group updateGroup(String repositoryId, Group group) {
		// Same merge-update pattern as updateUser to preserve all existing fields.
		CouchGroup existing = connectorPool.getClient(repositoryId).get(CouchGroup.class, group.getId());
		if (existing == null) {
			return null;
		}

		// Merge only Group-specific fields onto the existing document
		existing.setGroupId(group.getGroupId());
		existing.setName(group.getName());
		existing.setUsers(group.getUsers());
		existing.setGroups(group.getGroups());

		connectorPool.getClient(repositoryId).update(existing);
		Group g = existing.convert();

		return g;
	}

	@Override
	public void delete(String repositoryId, Class<?> clazz, String principalId){
		CouchNodeBase cnb = connectorPool.getClient(repositoryId).get(CouchNodeBase.class, principalId);
		connectorPool.getClient(repositoryId).delete(cnb);
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		this.connectorPool = connectorPool;
	}
}
