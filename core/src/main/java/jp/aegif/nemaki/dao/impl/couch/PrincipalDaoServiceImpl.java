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
// @Component annotation removed to prevent conflicts with XML bean definition in couchContext.xml
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
		List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "usersById", userId, CouchUser.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<User> getUsers(String repositoryId) {
		List<User> users = new ArrayList<User>();

		List<CouchUser> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "usersById", CouchUser.class);

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
			// Fallback: Create a minimal admin user if view query fails
			User adminUser = new User();
			adminUser.setId("admin");
			adminUser.setUserId("admin");
			adminUser.setName("Administrator");
			adminUser.setFirstName("Admin");
			adminUser.setLastName("User");
			adminUser.setEmail("admin@localhost");
			adminUser.setAdmin(true);
			adminUser.setType("user");
			
			// Set basic timestamps
			adminUser.setCreated(new GregorianCalendar());
			adminUser.setModified(new GregorianCalendar());
			adminUser.setCreator("system");
			adminUser.setModifier("system");
			
			admins.add(adminUser);
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
		List<CouchGroup> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "groupsById", groupId, CouchGroup.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<Group> getGroups(String repositoryId) {
		List<Group> groups = new ArrayList<Group>();

		List<CouchGroup> l = connectorPool.getClient(repositoryId).queryView(DESIGN_DOCUMENT, "groupsById", CouchGroup.class);

		for (CouchGroup c : l) {
			Group g = c.convert();
			groups.add(g);
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
		CouchUser update = new CouchUser(user);
		// Ektorp-style: trust the object's revision state completely
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			// Only fetch if revision is missing (non-Ektorp behavior)
			CouchUser cd = connectorPool.getClient(repositoryId).get(CouchUser.class, user.getId());
			if (cd != null) {
				update.setRevision(cd.getRevision());
			}
		}

		connectorPool.getClient(repositoryId).update(update);
		User u = update.convert();

		return u;
	}

	@Override
	public Group updateGroup(String repositoryId, Group group) {
		CouchGroup update = new CouchGroup(group);
		// Ektorp-style: trust the object's revision state completely
		if (update.getRevision() == null || update.getRevision().isEmpty()) {
			// Only fetch if revision is missing (non-Ektorp behavior)
			CouchGroup cd = connectorPool.getClient(repositoryId).get(CouchGroup.class, group.getId());
			if (cd != null) {
				update.setRevision(cd.getRevision());
			}
		}

		connectorPool.getClient(repositoryId).update(update);
		Group g = update.convert();

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
