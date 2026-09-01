package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.couch.CouchChange;

/**
 * Delegate for Change Event DAO operations.
 * Extracted from ContentDaoServiceImpl as part of class decomposition.
 */
public class ChangeEventDaoDelegate {

	/**
	 * The largest page one change query will serve. Two ceilings sit above this, both hit in
	 * a live TCK run before the cap existed: CouchDB rejects a limit above 2^28 with a 400
	 * (which the old fail-open catch converted into an EMPTY feed), and anything that merely
	 * clears that wire cap still loads the whole change log into one response — a repository
	 * with a few hundred thousand change rows took the JVM down with heap OOM. 10,000 follows
	 * the OpenCMIS server convention; clients asking for more get hasMoreItems=true and a
	 * changeLogToken that continues exactly where the page ended (compileChangeDataList
	 * advances the token per served event, so a clamped page never strands a client).
	 */
	private static final int MAX_CHANGE_PAGE = 10_000;

	private static final Log log = LogFactory.getLog(ChangeEventDaoDelegate.class);

	private final CloudantClientPool connectorPool;
	private final DaoHelper daoHelper;

	public ChangeEventDaoDelegate(CloudantClientPool connectorPool, DaoHelper daoHelper) {
		this.connectorPool = connectorPool;
		this.daoHelper = daoHelper;
	}

	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			CouchChange cc = client.get(CouchChange.class, changeTokenId);

			if (cc != null) {
				return cc.convert();
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting change event: " + changeTokenId + " in repository: " + repositoryId, e);
			return null;
		}
	}

	public Change getLatestChange(String repositoryId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			queryParams.put("descending", true);
			queryParams.put("limit", 1);

			ViewResult result = client.queryView("_repo", "changesByToken", queryParams);

			if (result.getRows() == null) {
				// "Answered without rows" is not "no changes": the FULL sync seeds its cursor
				// from this answer, and RepositoryInfo publishes it as latestChangeLogToken.
				throw new IllegalStateException("the changesByToken view answered without rows"
						+ " for repository '" + repositoryId + "'; that is not the same as"
						+ " there being no changes");
			}
			if (result.getRows() != null) {
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() == null) {
						throw new IllegalStateException("the latest change row carries no"
								+ " document for '" + repositoryId + "'; this is NOT a finding"
								+ " that there are no changes");
					}
					if (row.getDoc() != null) {
						try {
							com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
							Map<String, Object> docMap = doc.getProperties();
							if (docMap == null) {
								// The FOURTH null door, found after the other three were
								// closed one review round apart: a latest row whose document
								// carries no properties is a change that exists and cannot be
								// read — not an empty repository.
								throw new IllegalStateException("the latest change row carries"
										+ " no properties for '" + repositoryId + "'; this is"
										+ " NOT a finding that there are no changes");
							}
							if (docMap != null) {
								if (!docMap.containsKey("_id") && doc.getId() != null) {
									docMap.put("_id", doc.getId());
								}
								if (!docMap.containsKey("_rev") && doc.getRev() != null) {
									docMap.put("_rev", doc.getRev());
								}
								String jsonString = mapper.writeValueAsString(docMap);
								CouchChange cc = mapper.readValue(jsonString, CouchChange.class);
								if (cc == null) {
									// The third null door: readValue answering null is the
									// latest change existing and not being readable, not the
									// repository having no changes.
									throw new IllegalStateException("the latest change row"
											+ " could not be decoded for '" + repositoryId
											+ "'; this is NOT a finding that there are no"
											+ " changes");
								}
								if (cc.getId() == null && doc.getId() != null) {
									cc.setId(doc.getId());
								}
								return cc.convert();
							}
						} catch (Exception e) {
							// The LATEST change exists and cannot be read. Returning null here
							// said "no changes": the FULL sync seeded an empty cursor over it,
							// and RepositoryInfo's latestChangeLogToken disagreed with the
							// per-event tokens so hasMoreItems never reached equality.
							throw new IllegalStateException("the latest change row could not be"
									+ " decoded for '" + repositoryId + "'; this is NOT a"
									+ " finding that there are no changes", e);
						}
					}
				}
			}

			log.debug("No changes found in repository: " + repositoryId + ", returning null");
			return null;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// The empty-repository nulls all live ABOVE this catch (a startup-phase missing
			// view answers null from the wrapper; a deployed view with no rows falls through
			// to the ordinary null return) — what lands here is a FAILURE. Returning null
			// for it told the FULL sync "no changes exist": it seeded an EMPTY checkpoint
			// and reported COMPLETED, so the next incremental replayed from the beginning
			// of time or missed the head, depending on which side of the lie it landed.
			log.error("Error getting latest change in repository: " + repositoryId, e);
			throw new IllegalStateException("the latest change could not be read for '"
					+ repositoryId + "'; this is NOT a finding that there are no changes", e);
		}
	}

	/** Rows the most recent getLatestChanges on THIS thread could not decode. */
	private final ThreadLocal<Integer> lastUnreadableChanges = ThreadLocal.withInitial(() -> 0);

	public int lastUnreadableChangeCount() {
		return lastUnreadableChanges.get();
	}

	public List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems) {
		lastUnreadableChanges.set(0);
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			// ALWAYS bounded. maxItems <= 0 used to mean "no limit param", which is an
			// unbounded include_docs query over the whole change log — reachable from
			// /api/v1 changes?maxItems=0, from OData, and from any BigInteger that
			// truncates to <= 0. No caller uses "<= 0 as unlimited" deliberately (RSS and
			// CMIS both pass positive numbers), so a non-positive ask gets one page.
			queryParams.put("limit", maxItems > 0 ? Math.min(maxItems, MAX_CHANGE_PAGE)
					: MAX_CHANGE_PAGE);
			if (startToken != null) {
				try {
					queryParams.put("startkey", Long.parseLong(startToken));
				} catch (NumberFormatException e) {
					queryParams.put("startkey", startToken);
				}
			}

			ViewResult result = client.queryView("_repo", "changesByToken", queryParams);
			List<Change> changes = new ArrayList<Change>();

			if (result.getRows() == null) {
				// "Answered without rows" is not "there are no changes" — the caller advances
				// a cursor on this answer.
				throw new IllegalStateException("the changesByToken view answered without rows"
						+ " for repository '" + repositoryId + "'; that is not the same as"
						+ " there being no changes");
			}
			if (result.getRows() != null) {
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				for (ViewResultRow row : result.getRows()) {
					if (row.getDoc() == null) {
						lastUnreadableChanges.set(lastUnreadableChanges.get() + 1);
						continue;
					}
					if (row.getDoc() != null) {
						try {
							com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
							Map<String, Object> docMap = doc.getProperties();
							if (docMap != null) {
								if (!docMap.containsKey("_id") && doc.getId() != null) {
									docMap.put("_id", doc.getId());
								}
								if (!docMap.containsKey("_rev") && doc.getRev() != null) {
									docMap.put("_rev", doc.getRev());
								}
								String jsonString = mapper.writeValueAsString(docMap);
								CouchChange cc = mapper.readValue(jsonString, CouchChange.class);
								if (cc != null) {
									if (cc.getId() == null && doc.getId() != null) {
										cc.setId(doc.getId());
									}
									changes.add(cc.convert());
								} else {
									// readValue answering null is the third way a row drops,
									// and the counter missed it — half a counter reads as a
									// whole one.
									lastUnreadableChanges.set(lastUnreadableChanges.get() + 1);
								}
							} else {
								lastUnreadableChanges.set(lastUnreadableChanges.get() + 1);
							}
						} catch (Exception e) {
							// COUNTED. A change row that will not decode is a change that HAPPENED; dropping
							// it silently lets the incremental sync advance its cursor past it, and a change
							// the cursor has passed is never visited again — if it was a DELETE, the external
							// catalog keeps the entity until a full sync. The exact token-overrun the FULL
							// sync was just corrected for, arriving through the change log instead.
							lastUnreadableChanges.set(lastUnreadableChanges.get() + 1);
							log.warn("Failed to convert change document (counted as unreadable, not as"
									+ " absent): " + e.getMessage());
						}
					}
				}
			}

			return changes;
		} catch (Exception e) {
			// NOT an empty list: "the change log could not be read" and "there are no new
			// changes" are different facts, and a caller that advances a cursor on the answer
			// must be able to tell them apart.
			log.error("Error getting latest changes in repository: " + repositoryId, e);
			throw new IllegalStateException("the change log could not be read for '"
					+ repositoryId + "'; this is NOT a finding that there are no changes", e);
		}
	}

	public List<Change> getObjectChanges(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			List<CouchChange> couchChanges = client.queryView("_repo", "changesByObjectId", objectId, CouchChange.class);

			List<Change> changes = new ArrayList<Change>();
			for (CouchChange couchChange : couchChanges) {
				changes.add(couchChange.convert());
			}

			return changes;
		} catch (Exception e) {
			log.error("Error getting object changes for: " + objectId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the object changes could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public Change create(String repositoryId, Change change) {
		CouchChange cc = new CouchChange(change);
		connectorPool.getClient(repositoryId).create(cc);
		return cc.convert();
	}
}
