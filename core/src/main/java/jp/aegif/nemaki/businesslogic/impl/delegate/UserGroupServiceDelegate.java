package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delegate for user/group query operations.
 * Extracted from ContentServiceImpl as part of class decomposition.
 */
public class UserGroupServiceDelegate {

	private static final Logger log = LoggerFactory.getLogger(UserGroupServiceDelegate.class);

	private final ContentDaoService contentDaoService;
	private final RepositoryInfoMap repositoryInfoMap;

	public UserGroupServiceDelegate(ContentDaoService contentDaoService, RepositoryInfoMap repositoryInfoMap) {
		this.contentDaoService = contentDaoService;
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public UserItem getUserItem(String repositoryId, String objectId) {
		return contentDaoService.getUserItem(repositoryId, objectId);
	}

	public UserItem getUserItemById(String repositoryId, String userId) {
		return contentDaoService.getUserItemById(repositoryId, userId);
	}

	public List<UserItem> getUserItems(String repositoryId) {
		return contentDaoService.getUserItems(repositoryId);
	}

	public List<UserItem> getUserItems(String repositoryId, int skip, int limit) {
		return contentDaoService.getUserItems(repositoryId, skip, limit);
	}

	public int getUserItemCount(String repositoryId) {
		return contentDaoService.getUserItemCount(repositoryId);
	}

	public GroupItem getGroupItem(String repositoryId, String objectId) {
		return contentDaoService.getGroupItem(repositoryId, objectId);
	}

	public GroupItem getGroupItemById(String repositoryId, String groupId) {
		return contentDaoService.getGroupItemById(repositoryId, groupId);
	}

	public GroupItem getGroupItemByIdFresh(String repositoryId, String groupId) {
		return contentDaoService.getGroupItemByIdFresh(repositoryId, groupId);
	}

	public List<GroupItem> getGroupItems(String repositoryId) {
		return contentDaoService.getGroupItems(repositoryId);
	}

	public List<GroupItem> getGroupItems(String repositoryId, int skip, int limit) {
		return contentDaoService.getGroupItems(repositoryId, skip, limit);
	}

	public int getGroupItemCount(String repositoryId) {
		return contentDaoService.getGroupItemCount(repositoryId);
	}

	public Set<String> getGroupIdsContainingUser(String repositoryId, String userId) {
		String anonymous = getAnonymous(repositoryId);
		String anyone = getAnyone(repositoryId);

		Set<String> groupIds = new HashSet<String>();

		// Anonymous user doesn't belong to any group, even to Anyone.
		if (userId.equals(anonymous)) {
			return groupIds;
		}

		List<String> resultGroups = contentDaoService.getJoinedGroupByUserId(repositoryId, userId);
		groupIds.addAll(resultGroups);
		groupIds.add(anyone);

		return groupIds;
	}

	public boolean containsUserInGroup(String repositoryId, String userId, GroupItem group) {
		return containsUserInGroup(repositoryId, userId, group, new java.util.HashSet<String>());
	}

	/**
	 * Transitive membership test with a per-walk visited set so an indirect
	 * nested-group cycle (A -> B -> A) terminates instead of recursing until
	 * StackOverflow. Mirrors the guard in {@code PrincipalServiceImpl}.
	 */
	private boolean containsUserInGroup(String repositoryId, String userId, GroupItem group,
			java.util.Set<String> visited) {
		if (group == null || group.getGroupId() == null || !visited.add(group.getGroupId())) {
			return false;
		}
		log.debug("$$ group:" + group.getName());
		if (group.getUsers().contains(userId))
			return true;
		for (String groupId : group.getGroups()) {
			log.debug("$$ subgroup: " + groupId);
			GroupItem g = getGroupItemById(repositoryId, groupId);
			if (g == null) {
				log.debug("$$ group:" + groupId + "does not exist!");
				// A dangling subgroup reference is not a match; keep checking the
				// remaining siblings rather than aborting the whole walk.
				continue;
			}
			if (containsUserInGroup(repositoryId, userId, g, visited))
				return true;
		}
		return false;
	}

	public String getAnonymous(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnonymous();
	}

	public String getAnyone(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnyone();
	}

	public void validateUserItem(String repositoryId, UserItem userItem) {
		UserItem existingUser = contentDaoService.getUserItemById(repositoryId, userItem.getUserId());
		if (existingUser != null && userItem.getId() == null) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"userId=" + userItem.getUserId() + " already exists. Skip creating a new user.");
		}
	}

	public void validateGroupItem(String repositoryId, GroupItem groupItem) {
		UserItem existingUser = contentDaoService.getUserItemById(repositoryId, groupItem.getGroupId());
		if (existingUser != null && groupItem.getId() == null) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"userId=" + groupItem.getGroupId() + " already exists. Skip creating a new user.");
		}
	}
}
