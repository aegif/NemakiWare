package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
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
			List<CouchChange> couchChanges = client.queryView("_repo", "changesByToken", null, CouchChange.class);

			if (!couchChanges.isEmpty()) {
				return couchChanges.get(0).convert();
			}

			log.debug("No changes found in repository: " + repositoryId + ", returning null");
			return null;
		} catch (Exception e) {
			log.debug("Error getting latest change in repository: " + repositoryId + " (this is normal for empty repositories)", e);
			return null;
		}
	}

	public List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			if (maxItems > 0) {
				queryParams.put("limit", maxItems);
			}
			if (startToken != null) {
				try {
					queryParams.put("startkey", Long.parseLong(startToken));
				} catch (NumberFormatException e) {
					queryParams.put("startkey", startToken);
				}
			}

			ViewResult result = client.queryView("_repo", "changesByToken", queryParams);
			List<Change> changes = new ArrayList<Change>();

			if (result.getRows() != null) {
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				for (ViewResultRow row : result.getRows()) {
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
								}
							}
						} catch (Exception e) {
							log.warn("Failed to convert change document: " + e.getMessage());
						}
					}
				}
			}

			return changes;
		} catch (Exception e) {
			log.error("Error getting latest changes in repository: " + repositoryId, e);
			return new ArrayList<Change>();
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
			return new ArrayList<Change>();
		}
	}

	public Change create(String repositoryId, Change change) {
		CouchChange cc = new CouchChange(change);
		connectorPool.getClient(repositoryId).create(cc);
		return cc.convert();
	}
}
