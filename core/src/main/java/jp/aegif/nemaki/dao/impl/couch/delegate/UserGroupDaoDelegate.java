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
			// Identity data: null is "no such user", which the callers act on (a 404, or a
			// directory sync deciding the user is gone). A failed read is not that.
			log.error("Error getting user item: " + objectId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the user item '" + objectId + "' in '" + repositoryId
					+ "' could not be read; this is NOT a finding that it does not exist", e);
		}
	}

	public UserItem getUserItemById(String repositoryId, String userId) {
		try {
			log.info("=== getUserItemById for userId: " + userId + " in repository: " + repositoryId + " ===");

			// Use CloudantClientWrapper from connectorPool
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			ViewResult result = client.queryView("_repo", "userItemsById", userId);

			// null is "the view answered, and no row carries this userId" — GENUINE ABSENCE.
			//
			// This was briefly a refusal, on the reading that null meant "did not answer".
			// It does not: the keyed overload of queryView returns `rows == 0 ? null :
			// result` (CloudantClientWrapper), and the case it used to conflate with — a
			// design document that is not deployed — now throws there, outside the startup
			// window. So by the time we are here, null can only be absence.
			//
			// The refusal broke ordinary operation and a review caught it before it shipped:
			// a login with an unknown username became a 500 instead of a 401 (and never
			// reached the throttle), and the "does this group already exist?" pre-check made
			// creating any group impossible. The test that was supposed to protect this
			// stubbed an empty-rows ViewResult — a value the real wrapper never produces.
			if (result == null) {
				log.debug("No user with userId " + userId + " in " + repositoryId);
				return null;
			}
			if (result.getRows() == null) {
				throw new IllegalStateException("the userItemsById view returned a result with"
						+ " no rows object for '" + userId + "' in '" + repositoryId + "';"
						+ " the wrapper never produces that, so this is a wiring fault");
			}

			if (!result.getRows().isEmpty()) {
				log.info("Found " + result.getRows().size() + " matching user documents");

				// Iterate through all rows to find the actual user document (nemaki:user),
				// skipping other document types like nemaki:webauthnCredential that also
				// have userId field and match the view.
				for (ViewResultRow row : result.getRows()) {
					Object rawDoc = row.getValue(); // Use getValue() not getDoc()

					if (!(rawDoc instanceof Map)) {
						// The row the answer may hinge on. Skipping it silently narrows the
						// search for the user document to the rows that happened to decode.
						throw new IllegalStateException("a userItemsById row for '" + userId
								+ "' has an unreadable shape ("
								+ (rawDoc == null ? "null" : rawDoc.getClass().getName())
								+ "); refusing to decide the user's existence without it");
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

					// SECURITY FIX: Validate that returned user actually matches requested userId.
					// Handing back the wrong user is the danger this door was built for, and
					// that part stays. What must not stay is the ANSWER: the view keyed on
					// userId matched a row whose userId is a different string, so the index
					// and the document disagree and nothing here can say whether the
					// requested user exists. Null says it does not — and auto-provisioning
					// creates a second account on that answer, while the directory sync
					// deletes on it.
					// The non-user objectTypes (WebAuthn credentials and the like) are still
					// skipped above; this is a row that CLAIMS to be the user document.
					String returnedUserId = (String) docMap.get("userId");
					if (!userId.equals(returnedUserId)) {
						log.error("SECURITY WARNING: Requested userId '" + userId + "' but got userId '" + returnedUserId + "'");
						throw new IllegalStateException("the userItemsById view matched a"
								+ " nemaki:user row for '" + userId + "' in '" + repositoryId
								+ "' whose own userId is '" + returnedUserId + "'; the index"
								+ " and the document disagree, so whether the user exists"
								+ " cannot be established");
					}

					// Use the Map-based constructor we created
					CouchUserItem cui = new CouchUserItem(docMap);

					log.info("CouchUserItem created - userId: " + cui.getUserId() + ", admin: " + cui.isAdmin() +
						", id: " + cui.getId() + ", type: " + cui.getType());

					// Validate required fields
					if (cui.getUserId() != null && cui.getId() != null && cui.getType() != null) {
						return cui.convert();
					} else {
						// The document is THERE and unusable; null says the user does not
						// exist, which authentication and the directory sync act on.
						log.error("Missing required fields - userId: " + cui.getUserId() +
							", id: " + cui.getId() + ", type: " + cui.getType());
						throw new IllegalStateException("user '" + userId + "' exists but its"
								+ " document is missing required fields; this is NOT a finding"
								+ " that the user does not exist");
					}
				}

				log.warn("No nemaki:user document found among " + result.getRows().size() + " results for userId: " + userId);
			} else {
				// A view that ANSWERED with no rows: the user genuinely is not there.
				log.warn("No user found with userId: " + userId + " in repository: " + repositoryId);
			}

			return null;

		} catch (IllegalStateException e) {
			// The four refusal arms above each name what they found — an unanswered view, a
			// row with an unreadable shape, an index that disagrees with the document, a
			// document missing required fields. Letting the general catch below re-wrap them
			// replaced all four with one sentence about a read that failed, which is the
			// wrong sentence for three of them and unhelpful for the operator in all four.
			throw e;
		} catch (Exception e) {
			// The authentication and authorisation paths read this. Answering null makes a
			// CouchDB hiccup indistinguishable from "that user does not exist".
			log.error("Error in getUserItemById for userId '" + userId + "' in repository '" + repositoryId + "'", e);
			throw new IllegalStateException("the user '" + userId + "' could not be read in '"
					+ repositoryId + "'; this is NOT a finding that it does not exist", e);
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
			if (result == null || result.getRows() == null) {
				// Identity data fails CLOSED like the group side: "answered without rows" is
				// not "there are no users", and this list feeds admin screens and sync jobs
				// that act on absence.
				throw new IllegalStateException("the userItemsById view answered without rows"
						+ " in '" + repositoryId + "'; that is not the same as there being"
						+ " none");
			}
			{
				log.info("getUserItems: Retrieved " + result.getRows().size() + " users from CouchDB");

				for (ViewResultRow row : result.getRows()) {
					try {
						Object rawDoc = row.getValue(); // Use getValue() not getDoc()

						if (!(rawDoc instanceof Map)) {
							// The null RETURNS fail closed like the catch below: a row this
							// code walks past is a user that exists and is not in the answer.
							throw new IllegalStateException("a user item row is not a document"
									+ " in '" + repositoryId + "'; refusing to answer the user"
									+ " list short");
						}
						@SuppressWarnings("unchecked")
						Map<String, Object> docMap = (Map<String, Object>) rawDoc;

						// Use Map-based constructor to ensure proper subTypeProperties conversion
						CouchUserItem cui = new CouchUserItem(docMap);

						if (cui.getUserId() == null || cui.getId() == null || cui.getType() == null) {
							throw new IllegalStateException("a user item is missing required"
									+ " fields in '" + repositoryId + "'; refusing to answer"
									+ " the user list short");
						}
						UserItem converted = cui.convert();
						if (converted == null) {
							throw new IllegalStateException("a user item could not be converted"
									+ " in '" + repositoryId + "'; refusing to answer the user"
									+ " list short");
						}
						userItems.add(converted);
						log.debug("getUserItems: Successfully converted user: " + converted.getUserId());
					} catch (IllegalStateException e) {
						throw e;
					} catch (Exception convertException) {
						// A user that will not decode still EXISTS — a directory sync that
						// reads this list decides creations and removals from it.
						throw new IllegalStateException("a user item could not be decoded in '"
								+ repositoryId + "'; refusing to answer the user list short",
								convertException);
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
			if (result == null || result.getRows() == null) {
				// The unpaged overload got this one round earlier and this one did not — the
				// same one-arm-of-the-pair the group side had just been corrected for.
				throw new IllegalStateException("the userItemsById view answered without rows"
						+ " in '" + repositoryId + "'; that is not the same as there being"
						+ " none");
			}
			for (ViewResultRow row : result.getRows()) {
				try {
					Object rawDoc = row.getValue();
					if (!(rawDoc instanceof Map)) {
						throw new IllegalStateException("a user item row is not a document in '"
								+ repositoryId + "'; refusing to answer the user list short");
					}
					@SuppressWarnings("unchecked")
					Map<String, Object> docMap = (Map<String, Object>) rawDoc;
					CouchUserItem cui = new CouchUserItem(docMap);
					if (cui.getUserId() == null || cui.getId() == null || cui.getType() == null) {
						throw new IllegalStateException("a user item is missing required fields"
								+ " in '" + repositoryId + "'; refusing to answer the user list"
								+ " short");
					}
					UserItem converted = cui.convert();
					if (converted == null) {
						throw new IllegalStateException("a user item could not be converted in '"
								+ repositoryId + "'; refusing to answer the user list short");
					}
					userItems.add(converted);
				} catch (IllegalStateException e) {
					throw e;
				} catch (Exception convertException) {
					// A user that will not decode still EXISTS; a directory sync reading this
					// list decides creations and removals from it.
					throw new IllegalStateException("a user item could not be decoded in '"
							+ repositoryId + "'; refusing to answer the user list short",
							convertException);
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
			if (result == null || result.getRows() == null) {
				// 0 is "there are none", which an unanswered view does not establish — the
				// group-side count was corrected one round earlier and this sibling was not.
				throw new IllegalStateException("the userItemsById view answered without rows"
						+ " in '" + repositoryId + "'; a count cannot be taken from that");
			}
			return result.getRows().size();
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
			// Same rule as the user twin above.
			log.error("Error getting group item: " + objectId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the group item '" + objectId + "' in '" + repositoryId
					+ "' could not be read; this is NOT a finding that it does not exist", e);
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

			// Same correction as the user twin above: null is "no row carries this groupId",
			// which is what validateNewGroup and the directory sync are entitled to hear.
			// A missing view throws inside the wrapper now.
			if (result == null) {
				log.debug("No group with groupId " + groupId + " in " + repositoryId);
				return null;
			}
			if (result.getRows() == null) {
				throw new IllegalStateException("the groupItemsById view returned a result with"
						+ " no rows object for '" + groupId + "' in '" + repositoryId + "';"
						+ " the wrapper never produces that, so this is a wiring fault");
			}

			if (!result.getRows().isEmpty()) {
				log.info("Found " + result.getRows().size() + " matching group documents");

				ViewResultRow firstRow = result.getRows().get(0);
				// CRITICAL FIX (2025-10-13): CouchDB includeDocs returns STALE documents from view index
				// Even with update=true, the documents are from the view snapshot, not current DB state
				// Solution: Get document ID from view, then fetch DIRECTLY from database
				String documentId = firstRow.getId();


				// Fetch the absolute latest revision directly from CouchDB (NOT from view result)
				com.ibm.cloud.cloudant.v1.model.Document freshDoc = client.get(documentId);

				if (freshDoc == null) {
					// The view MATCHED a row for this groupId, so the document exists; the
					// read-back by id is what did not produce it. Null here says "no such
					// group", and the nested-membership walk then drops every permission
					// granted through it while the directory sync creates a second one.
					throw new IllegalStateException("the userItemsById view matched group '"
							+ groupId + "' in '" + repositoryId + "' but document " + documentId
							+ " could not be read back; this is NOT a finding that the group"
							+ " does not exist");
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
					// The twin of the userId check in getUserItemById, 280 lines above. That
					// one was changed to refuse and this one was left answering null — the
					// one-arm correction this batch keeps re-committing, found by a sibling
					// sweep. Handing back the wrong group is still refused; what changed is
					// the sentence said instead. Null means "no such group", and on it the
					// nested-membership expansion silently drops every permission granted
					// through this group, while the directory sync creates a duplicate.
					String returnedGroupId = (String) docMap.get("groupId");
					if (!groupId.equals(returnedGroupId)) {
						log.error("SECURITY WARNING: Requested groupId '" + groupId + "' but got groupId '" + returnedGroupId + "'");
						throw new IllegalStateException("the view matched a group row for '"
								+ groupId + "' in '" + repositoryId + "' whose own groupId is '"
								+ returnedGroupId + "'; the index and the document disagree, so"
								+ " whether the group exists cannot be established");
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
						// The group EXISTS (the fresh read returned its document); answering
						// null says "no such group" — the principal-delete walk would abort
						// on it (safe), but the membership-update path reads null as
						// deletable/skippable. An unusable existing group is a failure.
						log.error("Missing required fields - groupId: " + cgi.getGroupId() +
							", id: " + cgi.getId() + ", revision: " + cgi.getRevision() + ", type: " + cgi.getType());
						throw new IllegalStateException("group '" + groupId + "' exists but its"
								+ " document is missing required fields; this is NOT a finding"
								+ " that the group does not exist");
					}
				}
			} else {
				log.warn("No group found with groupId: " + groupId + " in repository: " + repositoryId);
			}

			return null;

		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// Null is "no such group"; a failed read is not that answer.
			log.error("Error in getGroupItemByIdInternal for groupId '" + groupId + "' in repository '" + repositoryId + "'", e);
			throw new IllegalStateException("group '" + groupId + "' could not be read in '"
					+ repositoryId + "'; this is NOT a finding that it does not exist", e);
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
			// The javadoc above already makes the argument; this arm just did not follow it.
			// An unanswered view is not "nobody references it" — the callers strip a deleted
			// principal from everyone who references it, and an empty answer here means the
			// delete succeeds with the dangling reference left in place.
			throw new CmisRuntimeException("the reverse-lookup view answered without rows for "
					+ subject + "; that is not the same as nothing referencing it");
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

			if (result.getRows() == null) {
				// Authorisation data fails CLOSED: "answered without rows" is not "no group
				// items", and a membership check over a short list answers questions about
				// who may see what.
				throw new IllegalStateException("the groupItemsById view answered without rows"
						+ " in '" + repositoryId + "'; that is not the same as there being"
						+ " none");
			}
			for (ViewResultRow row : result.getRows()) {
				if (row.getDoc() == null) {
					// Fail-closed applies to the null RETURNS too, not only the catch.
					throw new IllegalStateException("a group item row carries no document in '"
							+ repositoryId + "'; refusing to answer membership from a short"
							+ " list");
				}
				try {
					ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
					com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
					Map<String, Object> docProperties = doc.getProperties();
					CouchGroupItem cgi = mapper.convertValue(docProperties, CouchGroupItem.class);
					if (cgi == null) {
						throw new IllegalStateException("a group item could not be decoded in '"
								+ repositoryId + "'; refusing to answer membership from a"
								+ " short list");
					}
					GroupItem gi = cgi.convert();
					groupItems.add(gi);
				} catch (Exception e) {
					// Authorisation data fails CLOSED. A group item that will not
					// decode still GRANTS things; walking past it answers membership
					// questions from a list that is silently missing members.
					throw new IllegalStateException("a group item could not be decoded"
							+ " in '" + repositoryId + "'; refusing to answer"
							+ " membership from a short list", e);
				}
			}

			return groupItems;
		} catch (Exception e) {
			log.error("Error getting group items for repository: " + repositoryId + ", error: " + e.getMessage(), e);
			throw new IllegalStateException("the group items could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
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
			if (result.getRows() == null) {
				// The unpaged overload got this and the paged one did not — the exact
				// "one arm of the pair" this batch keeps finding in its own fixes.
				throw new IllegalStateException("the groupItemsById view answered without rows"
						+ " in '" + repositoryId + "'; that is not the same as there being"
						+ " none");
			}
			for (ViewResultRow row : result.getRows()) {
				if (row.getDoc() == null) {
					// Fail-closed applies to the null RETURNS too, not only the catch: a row
					// with no document is a group item that exists and cannot be read.
					throw new IllegalStateException("a group item row carries no document in '"
							+ repositoryId + "'; refusing to answer membership from a short"
							+ " list");
				}
				try {
					ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
					com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
					Map<String, Object> docProperties = doc.getProperties();
					CouchGroupItem cgi = mapper.convertValue(docProperties, CouchGroupItem.class);
					if (cgi == null) {
						throw new IllegalStateException("a group item could not be decoded in '"
								+ repositoryId + "'; refusing to answer membership from a"
								+ " short list");
					}
					GroupItem gi = cgi.convert();
					groupItems.add(gi);
				} catch (Exception e) {
					// Authorisation data fails CLOSED. A group item that will not
					// decode still GRANTS things; walking past it answers membership
					// questions from a list that is silently missing members.
					throw new IllegalStateException("a group item could not be decoded"
							+ " in '" + repositoryId + "'; refusing to answer"
							+ " membership from a short list", e);
				}
			}
			return groupItems;
		} catch (Exception e) {
			log.error("Error getting group items (paginated) for repository: " + repositoryId + ", error: " + e.getMessage(), e);
			throw new IllegalStateException("the group items could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public int getGroupItemCount(String repositoryId) {
		try {
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("include_docs", false);
			ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "groupItemsById", queryParams);
			if (result == null || result.getRows() == null) {
				throw new IllegalStateException("the groupItemsById view answered without rows"
						+ " in '" + repositoryId + "'; a count cannot be taken from that");
			}
			return result.getRows().size();
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// 0 is "there are none", which this failure does not establish.
			log.error("Error counting group items for repository: " + repositoryId, e);
			throw new IllegalStateException("the group items could not be counted in '"
					+ repositoryId + "'; this is NOT a finding that there are none", e);
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

			if (result.getRows() == null) {
				// A user's group memberships decide what they may see. "Answered without
				// rows" served as "belongs to nothing", which silently shrank the user's
				// permissions — safe in direction, wrong as a statement, and invisible to
				// the user who just lost access.
				throw new IllegalStateException("the joined-groups view answered without rows"
						+ " for user '" + userId + "'; that is not the same as belonging to"
						+ " no groups");
			}
			{
				for (ViewResultRow row : result.getRows()) {
					if (row.getValue() == null) {
						throw new IllegalStateException("a joined-group row for user '" + userId
								+ "' carries no value; refusing to answer the membership short");
					}
					{
						// Extract groupId from the CouchGroupItem document
						try {
							Map<String, Object> doc = (Map<String, Object>) row.getValue();
							String groupId = (String) doc.get("groupId");
							if (groupId == null || groupId.isEmpty()) {
								// A row that decodes but carries no usable groupId is still
								// a membership row — skipping it shrank the membership the
								// same way a decode failure did.
								throw new IllegalStateException("a joined-group row for user '"
										+ userId + "' carries no usable groupId; refusing to"
										+ " answer the membership short");
							}
							{
								groupIdsToCheck.add(groupId);
								resultGroupIds.add(groupId);
								visitedGroups.add(groupId); // Track visited groups
							}
						} catch (IllegalStateException e) {
							// Not re-wrapped: the groupId-missing arm above already says
							// exactly what happened, and "could not be read" would bury it.
							throw e;
						} catch (Exception e) {
							// Warn-and-skip made an unreadable membership row identical to "not
							// a member" — the user silently loses whatever this group granted.
							throw new IllegalStateException("a joined-group row for user '"
									+ userId + "' could not be read; refusing to answer the"
									+ " membership short", e);
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
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// An empty list is "belongs to no groups", which this failure does not establish.
			log.error("Error getting joined groups for user: " + userId + ", error: " + e.getMessage());
			throw new IllegalStateException("the joined groups of user '" + userId
					+ "' could not be resolved; this is NOT a finding that there are none", e);
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

				// This expansion is the NESTED half of the same membership answer the caller
				// throws for. A group skipped here (unanswered view, unreadable row, any
				// failure) removes every permission the user held through that nesting —
				// silently, per group. Same rule as the direct half: refuse, do not shorten.
				ViewResult result = connectorPool.getClient(repositoryId).queryView("_repo", "joinedDirectGroupsByGroupId", queryParams);
				if (result == null || result.getRows() == null) {
					throw new IllegalStateException("the group-hierarchy view answered without"
							+ " rows for group '" + groupId + "'; that is not the same as it"
							+ " belonging to no groups");
				}
				for (ViewResultRow row : result.getRows()) {
					if (row.getValue() == null) {
						throw new IllegalStateException("a group-hierarchy row for group '"
								+ groupId + "' carries no value; refusing to answer the"
								+ " membership short");
					}
					try {
						Map<String, Object> doc = (Map<String, Object>) row.getValue();
						String parentGroupId = (String) doc.get("groupId");
						if (parentGroupId == null || parentGroupId.isEmpty()) {
							// Same rule as the direct half: no usable groupId is not "not
							// a parent", it is "could not tell".
							throw new IllegalStateException("a group-hierarchy row for group '"
									+ groupId + "' carries no usable groupId; refusing to"
									+ " answer the membership short");
						}
						if (!resultGroupIds.contains(parentGroupId)) {
							resultGroupIds.add(parentGroupId);
						}
					} catch (IllegalStateException e) {
						// Same as the direct half: the specific arm's message survives.
						throw e;
					} catch (Exception e) {
						throw new IllegalStateException("a group-hierarchy row for group '"
								+ groupId + "' could not be read; refusing to answer the"
								+ " membership short", e);
					}
				}
			}
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// A partial result here is a shortened membership, same as above.
			log.error("Error in checkIndirectGroup: " + e.getMessage());
			throw new IllegalStateException(
					"nested group expansion failed; refusing to answer the membership short", e);
		}

		return resultGroupIds;
	}
}
