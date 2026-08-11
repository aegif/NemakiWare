package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.couch.CouchGroupItem;
import jp.aegif.nemaki.model.couch.CouchUserItem;

/**
 * Delegate for User/Group DAO operations.
 * Extracted from ContentDaoServiceImpl as part of class decomposition.
 */
public class UserGroupDaoDelegate {

	private static final Log log = LogFactory.getLog(UserGroupDaoDelegate.class);

	private final CloudantClientPool connectorPool;
	private final DaoHelper daoHelper;

	public UserGroupDaoDelegate(CloudantClientPool connectorPool, DaoHelper daoHelper) {
		this.connectorPool = connectorPool;
		this.daoHelper = daoHelper;
	}

	public UserItem getUserItem(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchUserItem cui = client.get(CouchUserItem.class, objectId);

			if (cui != null) {
				return cui.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting user item: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	public UserItem getUserItemById(String repositoryId, String userId) {
		try {
			log.info("=== getUserItemById for userId: " + userId + " in repository: " + repositoryId + " ===");

			// Use CloudantClientWrapper from connectorPool
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "userItemsById", userId);

			if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
				log.info("Found " + result.getRows().size() + " matching user documents");

				// Iterate through all rows to find the actual user document (nemaki:user),
				// skipping other document types like nemaki:webauthnCredential that also
				// have userId field and match the view.
				for (ViewResultRow row : result.getRows()) {
					Object rawDoc = row.getValue(); // Use getValue() not getDoc()

					if (!(rawDoc instanceof Map)) {
						log.error("Raw document is not a Map: " + rawDoc.getClass().getName());
						continue;
					}

					@SuppressWarnings("unchecked")
					Map<String, Object> docMap = (Map<String, Object>) rawDoc;

					// Skip non-user documents (e.g., WebAuthn credentials)
					String objectType = (String) docMap.get("objectType");
					if (!"nemaki:user".equals(objectType)) {
						if (log.isDebugEnabled()) {
							log.debug("Skipping non-user document with objectType: " + objectType);
						}
						continue;
					}

					log.info("Document contains userId: " + docMap.get("userId") + ", admin: " + docMap.get("admin"));

					// SECURITY FIX: Validate that returned user actually matches requested userId
					String returnedUserId = (String) docMap.get("userId");
					if (!userId.equals(returnedUserId)) {
						log.warn("SECURITY WARNING: Requested userId '" + userId + "' but got userId '" + returnedUserId + "' - returning null");
						return null;
					}

					// Use the Map-based constructor we created
					CouchUserItem cui = new CouchUserItem(docMap);

					log.info("CouchUserItem created - userId: " + cui.getUserId() + ", admin: " + cui.isAdmin() +
						", id: " + cui.getId() + ", type: " + cui.getType());

					// Validate required fields
					if (cui.getUserId() != null && cui.getId() != null && cui.getType() != null) {
						return cui.convert();
					} else {
						log.error("Missing required fields - userId: " + cui.getUserId() +
							", id: " + cui.getId() + ", type: " + cui.getType());
						return null;
					}
				}

				log.warn("No nemaki:user document found among " + result.getRows().size() + " results for userId: " + userId);
			} else {
				log.warn("No user found with userId: " + userId + " in repository: " + repositoryId);
			}

			return null;

		} catch (Exception e) {
			log.error("Error in getUserItemById for userId '" + userId + "' in repository '" + repositoryId + "'", e);
			return null;
		}
	}

	public List<UserItem> getUserItems(String repositoryId){
		log.info("=== getUserItems: Starting for repository: " + repositoryId + " ===");
		try {
			// Query userItemsById view to get all user items
			log.info("getUserItems: Getting client for repository: " + repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			log.info("getUserItems: Client obtained, querying userItemsById view");

			// FIXED: Use getValue() like getUserItemById to properly convert subTypeProperties
			ViewResult result = client.queryView("_repo", "userItemsById");

			List<UserItem> userItems = new ArrayList<UserItem>();
			if (result != null && result.getRows() != null) {
				log.info("getUserItems: Retrieved " + result.getRows().size() + " users from CouchDB");

				for (ViewResultRow row : result.getRows()) {
					try {
						Object rawDoc = row.getValue(); // Use getValue() not getDoc()

						if (rawDoc instanceof Map) {
							@SuppressWarnings("unchecked")
							Map<String, Object> docMap = (Map<String, Object>) rawDoc;

							// Use Map-based constructor to ensure proper subTypeProperties conversion
							CouchUserItem cui = new CouchUserItem(docMap);

							if (cui.getUserId() != null && cui.getId() != null && cui.getType() != null) {
								UserItem converted = cui.convert();
								if (converted != null) {
									userItems.add(converted);
									log.debug("getUserItems: Successfully converted user: " + converted.getUserId());
								}
							} else {
								log.warn("getUserItems: Missing required fields for user: " + cui.getUserId());
							}
						} else {
							log.warn("getUserItems: Raw document is not a Map: " + (rawDoc != null ? rawDoc.getClass().getName() : "null"));
						}
					} catch (Exception convertException) {
						log.error("getUserItems: Exception during conversion", convertException);
						// Skip failed conversions
					}
				}
			}

			log.info("getUserItems: Returning " + userItems.size() + " converted users");
			return userItems;
		} catch (Exception e) {
			log.error("=== getUserItems: Exception occurred ===");
			log.error("Exception type: " + e.getClass().getName());
			log.error("Exception message: " + e.getMessage());
			if (e.getCause() != null) {
				log.error("Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
			}
			log.error("Full stack trace:", e);
			log.error("=== End getUserItems Exception ===");

			throw new RuntimeException("Failed to retrieve user items from repository: " + repositoryId, e);
		}
	}

	public List<UserItem> getUserItems(String repositoryId, int skip, int limit) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("skip", skip);
			queryParams.put("limit", limit);
			ViewResult result = client.queryView("_repo", "userItemsById", queryParams);

			List<UserItem> userItems = new ArrayList<UserItem>();
			if (result != null && result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					try {
						Object rawDoc = row.getValue();
						if (rawDoc instanceof Map) {
							@SuppressWarnings("unchecked")
							Map<String, Object> docMap = (Map<String, Object>) rawDoc;
							CouchUserItem cui = new CouchUserItem(docMap);
							if (cui.getUserId() != null && cui.getId() != null && cui.getType() != null) {
								UserItem converted = cui.convert();
								if (converted != null) {
									userItems.add(converted);
								}
							}
						}
					} catch (Exception convertException) {
						log.error("getUserItems(paginated): Exception during conversion", convertException);
					}
				}
			}
			return userItems;
		} catch (Exception e) {
			throw new RuntimeException("Failed to retrieve user items from repository: " + repositoryId, e);
		}
	}

	public int getUserItemCount(String repositoryId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "userItemsById");
			if (result != null && result.getRows() != null) {
				return result.getRows().size();
			}
			return 0;
		} catch (Exception e) {
			throw new RuntimeException("Failed to count user items for repository: " + repositoryId, e);
		}
	}

	public GroupItem getGroupItem(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchGroupItem cgi = client.get(CouchGroupItem.class, objectId);

			if (cgi != null) {
				return cgi.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting group item: " + objectId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	public GroupItem getGroupItemById(String repositoryId, String groupId) {
		return getGroupItemByIdInternal(repositoryId, groupId, false);
	}

	public GroupItem getGroupItemByIdFresh(String repositoryId, String groupId) {
		// CRITICAL FIX: Force CouchDB view index update to get absolutely fresh data
		// This bypasses view caching and lazy indexing to prevent stale revision issues
		return getGroupItemByIdInternal(repositoryId, groupId, true);
	}

	/**
	 * Internal method to get group item by ID with optional forceUpdate parameter
	 * @param forceUpdate If true, forces CouchDB view index to update before querying (bypasses cache)
	 */
	private GroupItem getGroupItemByIdInternal(String repositoryId, String groupId, boolean forceUpdate) {
		try {
			if (forceUpdate) {
				log.info("=== getGroupItemById FRESH (force view update) for groupId: " + groupId + " ===");
			} else {
				log.info("=== getGroupItemById for groupId: " + groupId + " in repository: " + repositoryId + " ===");
			}

			// Use CloudantClientWrapper from connectorPool
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "groupItemsById", groupId, forceUpdate);

			if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
				log.info("Found " + result.getRows().size() + " matching group documents");

				ViewResultRow firstRow = result.getRows().get(0);
				// CRITICAL FIX (2025-10-13): CouchDB includeDocs returns STALE documents from view index
				// Even with update=true, the documents are from the view snapshot, not current DB state
				// Solution: Get document ID from view, then fetch DIRECTLY from database
				String documentId = firstRow.getId();


				// Fetch the absolute latest revision directly from CouchDB (NOT from view result)
				com.ibm.cloud.cloudant.v1.model.Document freshDoc = client.get(documentId);

				if (freshDoc == null) {
					return null;
				}


				// Convert Cloudant Document to Map for processing
				Map<String, Object> docMap = new HashMap<>();
				docMap.put("_id", freshDoc.getId());
				docMap.put("_rev", freshDoc.getRev());
				if (freshDoc.getProperties() != null) {
					docMap.putAll(freshDoc.getProperties());
				}

				log.info("Raw document class: " + docMap.getClass().getName());

				if (docMap != null) {
					log.info("Document contains groupId: " + docMap.get("groupId") + ", _id: " + docMap.get("_id") + ", _rev: " + docMap.get("_rev"));

					// SECURITY FIX: Validate that returned group actually matches requested groupId
					String returnedGroupId = (String) docMap.get("groupId");
					if (!groupId.equals(returnedGroupId)) {
						log.warn("SECURITY WARNING: Requested groupId '" + groupId + "' but got groupId '" + returnedGroupId + "' - returning null");
						return null;
					}

					// Use the Map-based constructor we created
					CouchGroupItem cgi = new CouchGroupItem(docMap);

					log.info("CouchGroupItem created - groupId: " + cgi.getGroupId() +
						", id: " + cgi.getId() + ", revision: " + cgi.getRevision() + ", type: " + cgi.getType());

					// Validate required fields
					if (cgi.getGroupId() != null && cgi.getId() != null && cgi.getRevision() != null && cgi.getType() != null) {
						GroupItem result_groupItem = cgi.convert();
						log.info("GroupItem converted - id: " + result_groupItem.getId() + ", revision: " + result_groupItem.getRevision());
						return result_groupItem;
					} else {
						log.error("Missing required fields - groupId: " + cgi.getGroupId() +
							", id: " + cgi.getId() + ", revision: " + cgi.getRevision() + ", type: " + cgi.getType());
						return null;
					}
				}
			} else {
				log.warn("No group found with groupId: " + groupId + " in repository: " + repositoryId);
			}

			return null;

		} catch (Exception e) {
			log.error("Error in getGroupItemByIdInternal for groupId '" + groupId + "' in repository '" + repositoryId + "'", e);
			return null;
		}
	}

	/**
	 * Reads the parent group ids out of a reverse-lookup view result.
	 *
	 * <p>Shared by the two callers below so the failure policy is stated once. A row that
	 * cannot be parsed FAILS THE WHOLE CALL rather than being logged and skipped: the callers
	 * use this to strip a deleted principal from everyone who references it, so a partial list
	 * means a delete that reports success and leaves a dangling reference — the same outcome
	 * this method exists to prevent, arrived at more quietly. It is the identical argument that
	 * makes a view failure an exception rather than an empty list.
	 *
	 * @param subject the id being looked up, for the message only
	 */
	private List<String> parentGroupIdsFrom(ViewResult result, String subject) {
		List<String> parents = new ArrayList<String>();
		if (result == null || result.getRows() == null) {
			return parents;
		}
		Set<String> seen = new HashSet<String>();
		int rows = 0;
		for (ViewResultRow row : result.getRows()) {
			rows++;
			Object value = row.getValue();
			// A null value is treated as a malformed row, not skipped. Skipping meant a result
			// set of all-null rows read as "no parents" and succeeded — the same
			// indistinguishability between "nothing references it" and "could not tell" that
			// every other branch here refuses.
			if (value == null) {
				throw new CmisRuntimeException(
						"A reverse-lookup row for " + subject + " has a null value");
			}
			if (!(value instanceof Map)) {
				throw new CmisRuntimeException("Unexpected row shape in the reverse-lookup view"
						+ " for " + subject + ": " + value.getClass().getName());
			}
			@SuppressWarnings("unchecked")
			Object parentId = ((Map<String, Object>) value).get("groupId");
			if (!(parentId instanceof String) || ((String) parentId).isEmpty()) {
				throw new CmisRuntimeException("A reverse-lookup row for " + subject
						+ " carries no usable groupId");
			}
			if (seen.add((String) parentId)) {
				parents.add((String) parentId);
			}
		}
		if (rows > 0 && parents.isEmpty()) {
			// Belt and braces: if the shape ever changes such that every row parses to nothing
			// without throwing, "no parents" would again be indistinguishable from "could not
			// tell", which is exactly the confusion being designed out here.
			throw new CmisRuntimeException("The reverse-lookup view returned " + rows
					+ " rows for " + subject + " but none yielded a group id");
		}
		return parents;
	}

	/**
	 * The ids of the groups that directly contain {@code groupId}.
	 *
	 * <p>Answers "who nests this group" from the reverse-lookup view instead of enumerating
	 * every group in the repository. Deleting a group has to strip it from its parents, and the
	 * only way that was being found was to fetch ALL groups WITH their documents and scan each
	 * one's nested list — 2,978 groups fetched to find the handful that matter, measured at
	 * 6.2 s per deletion on the dev stack. The view already existed and was already used by the
	 * read path.
	 *
	 * <p>The composite key handling matches {@code checkIndirectGroup}: the view emits array
	 * keys {@code [groupId, n]} for n in 0..19 — the same parent document twenty times per edge
	 * — so the range is pinned to depth 0 and the keys must be passed as {@code List} or the
	 * SDK sends a JSON string that matches no array key and the query silently returns nothing.
	 * That duplicate emit is F4 in the release ledger; this pinning is what makes it harmless
	 * here, so if the map function is ever rewritten to emit once, drop the range.
	 */
	public List<String> getGroupIdsDirectlyContainingGroup(String repositoryId, String groupId) {
		if (groupId == null || groupId.isEmpty()) {
			return new ArrayList<String>();
		}
		Map<String, Object> queryParams = new HashMap<String, Object>();
		queryParams.put("startkey", Arrays.asList(groupId, 0));
		queryParams.put("endkey", Arrays.asList(groupId, 0));
		try {
			return parentGroupIdsFrom(connectorPool.getClient(repositoryId)
					.queryView("_repo", "joinedDirectGroupsByGroupId", queryParams), groupId);
		} catch (CmisRuntimeException e) {
			throw e;
		} catch (Exception e) {
			// Deliberately NOT swallowed into "no parents": that would let a view failure look
			// like "nothing references this group" and leave dangling references behind.
			throw new CmisRuntimeException(
					"Could not resolve the groups containing " + groupId + ": " + e.getMessage(), e);
		}
	}

	/**
	 * The ids of the groups that directly list {@code userId} as a member.
	 *
	 * <p>The user-side twin of {@link #getGroupIdsDirectlyContainingGroup}: deleting a user has
	 * to strip it from every group that lists it, and that was also being found by fetching ALL
	 * groups with their documents. The view exists ({@code joinedDirectGroupsByUserId}) and the
	 * membership read path already uses it — with a scalar {@code key}, unlike the group view's
	 * composite array keys.
	 */
	public List<String> getGroupIdsDirectlyContainingUser(String repositoryId, String userId) {
		if (userId == null || userId.isEmpty()) {
			return new ArrayList<String>();
		}
		Map<String, Object> queryParams = new HashMap<String, Object>();
		queryParams.put("key", userId);
		try {
			return parentGroupIdsFrom(connectorPool.getClient(repositoryId)
					.queryView("_repo", "joinedDirectGroupsByUserId", queryParams), userId);
		} catch (CmisRuntimeException e) {
			throw e;
		} catch (Exception e) {
			// Same reason as the group twin: a view failure must not read as "no memberships".
			throw new CmisRuntimeException(
					"Could not resolve the groups containing user " + userId + ": " + e.getMessage(), e);
		}
	}

	public List<GroupItem> getGroupItems(String repositoryId) {
		try {
			// Use ViewQuery to get all group items from groupItemsById view
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", true);  // CRITICAL: Include full documents
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItemsById", queryParams);

			List<GroupItem> groupItems = new ArrayList<GroupItem>();

			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();

							// CRITICAL FIX: Use Document.getProperties() to get Map<String, Object>
							// Cloudant SDK Document needs to be converted to Map before passing to ObjectMapper
							com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
							Map<String, Object> docProperties = doc.getProperties();

							CouchGroupItem cgi = mapper.convertValue(docProperties, CouchGroupItem.class);
							if (cgi != null) {
								GroupItem gi = cgi.convert();
								groupItems.add(gi);
							}
						} catch (Exception e) {
							log.error("Failed to convert group item document: " + e.getMessage(), e);
						}
					}
				}
			}

			return groupItems;
		} catch (Exception e) {
			log.error("Error getting group items for repository: " + repositoryId + ", error: " + e.getMessage(), e);
			return new ArrayList<GroupItem>();
		}
	}

	public List<GroupItem> getGroupItems(String repositoryId, int skip, int limit) {
		try {
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", true);
			queryParams.put("skip", skip);
			queryParams.put("limit", limit);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItemsById", queryParams);

			List<GroupItem> groupItems = new ArrayList<GroupItem>();
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						try {
							ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
							com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
							Map<String, Object> docProperties = doc.getProperties();
							CouchGroupItem cgi = mapper.convertValue(docProperties, CouchGroupItem.class);
							if (cgi != null) {
								GroupItem gi = cgi.convert();
								groupItems.add(gi);
							}
						} catch (Exception e) {
							log.error("Failed to convert group item document: " + e.getMessage(), e);
						}
					}
				}
			}
			return groupItems;
		} catch (Exception e) {
			log.error("Error getting group items (paginated) for repository: " + repositoryId + ", error: " + e.getMessage(), e);
			return new ArrayList<GroupItem>();
		}
	}

	public int getGroupItemCount(String repositoryId) {
		try {
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", false);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItemsById", queryParams);
			if (result != null && result.getRows() != null) {
				return result.getRows().size();
			}
			return 0;
		} catch (Exception e) {
			log.error("Error counting group items for repository: " + repositoryId, e);
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	public List<String> getJoinedGroupByUserId(String repositoryId, String userId) {
		try {
			//first get directory joined groups
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("key", userId);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "joinedDirectGroupsByUserId", queryParams);

			//get indirect joined group using above results
			List<String> groupIdsToCheck = new ArrayList<String>();
			List<String> resultGroupIds = new ArrayList<String>();

			// CRITICAL FIX: Add visited groups tracking to prevent infinite loops
			Set<String> visitedGroups = new HashSet<String>();

			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					if (row.getValue() != null) {
						// Extract groupId from the CouchGroupItem document
						try {
							Map<String, Object> doc = (Map<String, Object>) row.getValue();
							String groupId = (String) doc.get("groupId");
							if (groupId != null) {
								groupIdsToCheck.add(groupId);
								resultGroupIds.add(groupId);
								visitedGroups.add(groupId); // Track visited groups
							}
						} catch (Exception e) {
							log.warn("Error parsing group document for user " + userId + ": " + e.getMessage());
						}
					}
				}
			}

			// Bound on WORK, not a cycle guard — cycles are already impossible here because
			// visitedGroups never re-admits a group to the frontier. Each iteration climbs one
			// level, so this caps the resolvable nesting depth at maxIterations + 1.
			// Reaching it means the answer is INCOMPLETE: see TruncatedGroupResolution.
			int maxIterations = 50;
			int iterations = 0;
			boolean truncated = false;

			while(groupIdsToCheck.size() > 0 && iterations < maxIterations) {
				List<String> newGroupIds = checkIndirectGroup(repositoryId, groupIdsToCheck);

				// Filter out already visited groups to prevent cycles
				groupIdsToCheck.clear();
				for (String groupId : newGroupIds) {
					if (!visitedGroups.contains(groupId)) {
						groupIdsToCheck.add(groupId);
						resultGroupIds.add(groupId);
						visitedGroups.add(groupId);
					}
				}

				iterations++;

				// Still work left when the budget ran out => the membership below is a prefix
				// of the real one, and every ACE granted through an unreached ancestor group
				// will be silently ignored by the authorization gate.
				if (iterations >= maxIterations && !groupIdsToCheck.isEmpty()) {
					truncated = true;
					log.warn("Group hierarchy traversal INCOMPLETE for user " + userId
							+ ": stopped at " + maxIterations + " levels with "
							+ groupIdsToCheck.size() + " group(s) still unexplored. "
							+ "Permissions granted through ancestor groups above that depth will "
							+ "NOT take effect. Flatten the group nesting for this account.");
				}
			}

			//unique result
			if (truncated) {
				return new jp.aegif.nemaki.dao.TruncatedGroupResolution(resultGroupIds, maxIterations);
			}
			return resultGroupIds;
		} catch (Exception e) {
			log.error("Error getting joined groups for user: " + userId + ", error: " + e.getMessage());
			return new ArrayList<String>();
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> checkIndirectGroup(String repositoryId, List<String> groupIdsToCheck) {
		List<String> resultGroupIds = new ArrayList<String>();

		if (groupIdsToCheck == null || groupIdsToCheck.isEmpty()) {
			return resultGroupIds;
		}

		try {
			// For now, implement a simplified version that doesn't do recursive group checking
			// This prevents infinite loops and provides basic functionality
			// Full implementation would require complex recursive group hierarchy traversal

			for (String groupId : groupIdsToCheck) {
				// Check if this group belongs to other groups using joinedDirectGroupsByGroupId view.
				// The view emits composite array keys [groupId, n]; startkey/endkey must be passed
				// as List so the Cloudant SDK serializes them as JSON arrays. Passing a String
				// ("[\"id\",0]") sends a JSON string key which never matches an array key, so
				// nested-group expansion silently returned nothing.
				// Read depth 0 ONLY. The view emits the SAME parent document twenty times per
				// edge, under keys [groupId, 0] .. [groupId, 19]; asking for the whole range
				// transfers twenty copies of every parent and throws nineteen of them away in the
				// dedupe below. One key is enough because the twenty rows are identical.
				//
				// The duplicate emits are not fixed here on purpose: changing the map function
				// forces CouchDB to rebuild the whole view, which on a large repository is an
				// outage-shaped operation for a saving the consumer can take unilaterally. If the
				// view is ever rewritten to emit once, this stays correct — a scalar key would
				// need the range dropped, which the compile would catch.
				Map<String, Object> queryParams = new HashMap<String, Object>();
				queryParams.put("startkey", Arrays.asList(groupId, 0));
				queryParams.put("endkey", Arrays.asList(groupId, 0));

				try {
					ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "joinedDirectGroupsByGroupId", queryParams);
					if (result.getRows() != null) {
						for (ViewResultRow row : result.getRows()) {
							if (row.getValue() != null) {
								try {
									Map<String, Object> doc = (Map<String, Object>) row.getValue();
									String parentGroupId = (String) doc.get("groupId");
									if (parentGroupId != null && !resultGroupIds.contains(parentGroupId)) {
										resultGroupIds.add(parentGroupId);
									}
								} catch (Exception e) {
									log.warn("Error parsing group hierarchy for group " + groupId + ": " + e.getMessage());
								}
							}
						}
					}
				} catch (Exception e) {
					log.warn("Error checking indirect groups for " + groupId + ": " + e.getMessage());
				}
			}
		} catch (Exception e) {
			log.error("Error in checkIndirectGroup: " + e.getMessage());
		}

		return resultGroupIds;
	}
}
