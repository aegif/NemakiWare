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
import java.util.List;

import jp.aegif.nemaki.dao.PrincipalDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.ConnectorPool;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.couch.CouchGroup;
import jp.aegif.nemaki.model.couch.CouchNodeBase;
import jp.aegif.nemaki.model.couch.CouchUser;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.ViewQuery;
import org.springframework.stereotype.Component;

/**
 * Dao Service for Principal(User/Group) Implementation for CouchDB
 *
 * @author linzhixing
 */
@Component
public class PrincipalDaoServiceImpl implements
		PrincipalDaoService {

	private ConnectorPool connectorPool;
	private static final Log logger = LogFactory
			.getLog(PrincipalDaoServiceImpl.class);
	private static final String DESIGN_DOCUMENT = "_design/_repo";

	public PrincipalDaoServiceImpl() {

	}


	@Override
	public User getUser(String repositoryId, String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("users").key(nodeId);
		List<CouchUser> l = connectorPool.get(repositoryId).queryView(query, CouchUser.class);

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
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("usersById").key(userId);
		List<CouchUser> l = connectorPool.get(repositoryId).queryView(query, CouchUser.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<User> getUsers(String repositoryId) {
		List<User> users = new ArrayList<User>();

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("usersById");
		List<CouchUser> l = connectorPool.get(repositoryId).queryView(query, CouchUser.class);

		for (CouchUser c : l) {
			User u = c.convert();
			users.add(u);
		}

		return users;
	}

	@Override
	public User getAdmin(String repositoryId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("admin");
		List<CouchUser> l = connectorPool.get(repositoryId).queryView(query, CouchUser.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}


	@Override
	public Group getGroup(String repositoryId, String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("groups").key(nodeId);
		List<CouchGroup> l = connectorPool.get(repositoryId).queryView(query, CouchGroup.class);

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
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("groupsById").key(groupId);
		List<CouchGroup> l = connectorPool.get(repositoryId).queryView(query, CouchGroup.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0);
	}

	@Override
	public List<Group> getGroups(String repositoryId) {
		List<Group> groups = new ArrayList<Group>();

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("groupsById");

		List<CouchGroup> l = connectorPool.get(repositoryId).queryView(query, CouchGroup.class);

		for (CouchGroup c : l) {
			Group g = c.convert();
			groups.add(g);
		}

		return groups;
	}

	@Override
	public User createUser(String repositoryId, User user) {
		CouchUser cu = new CouchUser(user);
		connectorPool.get(repositoryId).create(cu);
		User created = cu.convert();
		return created;
	}

	@Override
	public Group createGroup(String repositoryId, Group group) {
		CouchGroup cg = new CouchGroup(group);
		connectorPool.get(repositoryId).create(cg);
		Group created = cg.convert();
		return created;
	}

	@Override
	public User updateUser(String repositoryId, User user) {
		CouchUser cd = connectorPool.get(repositoryId).get(CouchUser.class, user.getId());

		// Set the latest revision for avoid conflict
		CouchUser update = new CouchUser(user);
		update.setRevision(cd.getRevision());

		connectorPool.get(repositoryId).update(update);
		User u = update.convert();

		return u;
	}

	@Override
	public Group updateGroup(String repositoryId, Group group) {
		CouchGroup cd = connectorPool.get(repositoryId).get(CouchGroup.class, group.getId());

		// Set the latest revision for avoid conflict
		CouchGroup update = new CouchGroup(group);
		update.setRevision(cd.getRevision());

		connectorPool.get(repositoryId).update(update);
		Group g = update.convert();

		return g;
	}

	@Override
	public void delete(String repositoryId, Class<?> clazz, String principalId){
		CouchNodeBase cnb = connectorPool.get(repositoryId).get(CouchNodeBase.class, principalId);
		connectorPool.get(repositoryId).delete(cnb);
	}

	public void setConnectorPool(ConnectorPool connectorPool) {
		this.connectorPool = connectorPool;
	}
}
