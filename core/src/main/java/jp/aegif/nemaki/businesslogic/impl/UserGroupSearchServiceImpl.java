package jp.aegif.nemaki.businesslogic.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.util.ClientUtils;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.businesslogic.UserGroupSearchService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Solr-based user/group search implementation with CouchDB fallback.
 */
public class UserGroupSearchServiceImpl implements UserGroupSearchService {

	private static final Log log = LogFactory.getLog(UserGroupSearchServiceImpl.class);

	private static final int DEFAULT_MAX_ROWS = 1000;
	private static final String STABLE_SORT = "object_id asc";

	// MISSING probe results are retried after this interval (milliseconds)
	static final long MISSING_RETRY_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes
	// READY probe results are re-verified after this interval (milliseconds)
	// This handles partial backfill (P1) and Solr clear/reindex staleness (P2)
	static final long READY_RETRY_INTERVAL_MS = 30 * 60 * 1000L; // 30 minutes

	// Minimum coverage ratio (Solr indexed / CouchDB total) to consider Solr ready.
	// Below this threshold, Solr is treated as still backfilling and CouchDB fallback is used.
	static final double COVERAGE_THRESHOLD = 1.0; // 100%

	/** Immutable probe result with timestamp for TTL-based re-evaluation. */
	static final class ProbeState {
		final boolean ready;
		final long timestamp;

		ProbeState(boolean ready) {
			this.ready = ready;
			this.timestamp = System.currentTimeMillis();
		}

		/** For testing: create a ProbeState with an arbitrary timestamp. */
		ProbeState(boolean ready, long timestamp) {
			this.ready = ready;
			this.timestamp = timestamp;
		}

		boolean isExpired(long now, long missingTtlMs, long readyTtlMs) {
			long ttl = ready ? readyTtlMs : missingTtlMs;
			return (now - timestamp) >= ttl;
		}
	}

	// Separate probe queries per entity type to avoid cross-type coverage masking
	private static final String USER_PROBE_QUERY = "dynamic.usersearch.user_id:*";
	private static final String GROUP_PROBE_QUERY = "dynamic.usersearch.group_id:*";

	/** Per-repository probe result cache. */
	private final ConcurrentHashMap<String, ProbeState> solrFieldsReadyMap = new ConcurrentHashMap<>();
	/** Per-repository lock objects to avoid global serialization during probe. */
	private final ConcurrentHashMap<String, Object> repoLocks = new ConcurrentHashMap<>();

	private ContentService contentService;
	private SolrIndexMaintenanceService solrIndexMaintenanceService;
	private PropertyManager propertyManager;

	@Override
	public boolean isSolrSearchEnabled() {
		return propertyManager.readBoolean(PropertyKey.SEARCH_USERGROUP_SOLR_ENABLED);
	}

	@Override
	public boolean isSolrUserSearchEffective(String repositoryId) {
		if (!propertyManager.readBoolean(PropertyKey.SEARCH_USERGROUP_SOLR_ENABLED)) {
			return false;
		}
		return isEntityTypeReady(repositoryId, "user", USER_PROBE_QUERY);
	}

	@Override
	public boolean isSolrGroupSearchEffective(String repositoryId) {
		if (!propertyManager.readBoolean(PropertyKey.SEARCH_USERGROUP_SOLR_ENABLED)) {
			return false;
		}
		return isEntityTypeReady(repositoryId, "group", GROUP_PROBE_QUERY);
	}

	/**
	 * Per-entity-type probe with independent cache, coverage check, and TTL.
	 * Cache key = "repositoryId:entityType" (e.g. "bedroom:user", "bedroom:group").
	 */
	private boolean isEntityTypeReady(String repositoryId, String entityType, String probeQuery) {
		String cacheKey = repositoryId + ":" + entityType;
		ProbeState state = solrFieldsReadyMap.get(cacheKey);
		if (state != null && !state.isExpired(System.currentTimeMillis(), MISSING_RETRY_INTERVAL_MS, READY_RETRY_INTERVAL_MS)) {
			return state.ready;
		}
		Object lock = repoLocks.computeIfAbsent(cacheKey, k -> new Object());
		synchronized (lock) {
			state = solrFieldsReadyMap.get(cacheKey);
			if (state != null && !state.isExpired(System.currentTimeMillis(), MISSING_RETRY_INTERVAL_MS, READY_RETRY_INTERVAL_MS)) {
				return state.ready;
			}
			try {
				SolrQueryResult probeResult = solrIndexMaintenanceService.executeSolrQuery(
						repositoryId, probeQuery, 0, 1, null, "object_id");
				if (probeResult.getErrorMessage() != null) {
					log.warn("Solr " + entityType + " field probe failed for repository " + repositoryId
							+ ": " + probeResult.getErrorMessage()
							+ " — disabling Solr " + entityType + " search (retry in "
							+ (MISSING_RETRY_INTERVAL_MS / 1000) + "s)");
					solrFieldsReadyMap.put(cacheKey, new ProbeState(false));
					return false;
				}
				long solrCount = probeResult.getNumFound();
				if (solrCount == 0) {
					log.warn("Solr " + entityType + " dynamic fields not yet indexed for repository "
							+ repositoryId + " (numFound=0)"
							+ " — disabling Solr " + entityType + " search (retry in "
							+ (MISSING_RETRY_INTERVAL_MS / 1000) + "s)");
					solrFieldsReadyMap.put(cacheKey, new ProbeState(false));
					return false;
				}

				// Coverage check: compare Solr indexed count with CouchDB count for this entity type
				int couchDbCount = getCouchDbCount(repositoryId, entityType);
				if (couchDbCount > 0) {
					double coverage = (double) solrCount / couchDbCount;
					if (coverage < COVERAGE_THRESHOLD) {
						log.warn("Solr " + entityType + " index coverage too low for repository "
								+ repositoryId + " (solr=" + solrCount
								+ ", couchdb=" + couchDbCount
								+ ", coverage=" + String.format("%.1f%%", coverage * 100)
								+ ", threshold=" + String.format("%.0f%%", COVERAGE_THRESHOLD * 100)
								+ ") — disabling Solr " + entityType + " search (retry in "
								+ (MISSING_RETRY_INTERVAL_MS / 1000) + "s)");
						solrFieldsReadyMap.put(cacheKey, new ProbeState(false));
						return false;
					}
				}

				log.info("Solr " + entityType + " index ready for repository "
						+ repositoryId + " (solr=" + solrCount
						+ ", couchdb=" + couchDbCount
						+ ", coverage=" + (couchDbCount > 0
								? String.format("%.1f%%", (double) solrCount / couchDbCount * 100)
								: "N/A")
						+ ") — Solr " + entityType + " search enabled (re-verify in "
						+ (READY_RETRY_INTERVAL_MS / 1000) + "s)");
				solrFieldsReadyMap.put(cacheKey, new ProbeState(true));
				return true;
			} catch (Exception e) {
				log.warn("Solr " + entityType + " field probe threw exception for repository " + repositoryId
						+ " — disabling Solr " + entityType + " search (retry in "
						+ (MISSING_RETRY_INTERVAL_MS / 1000) + "s)", e);
				solrFieldsReadyMap.put(cacheKey, new ProbeState(false));
				return false;
			}
		}
	}

	/**
	 * Get CouchDB count for a specific entity type.
	 * Returns 0 if ContentService is not injected (graceful degradation).
	 */
	private int getCouchDbCount(String repositoryId, String entityType) {
		if (contentService == null) {
			return 0;
		}
		try {
			if ("user".equals(entityType)) {
				return contentService.getUserItemCount(repositoryId);
			} else if ("group".equals(entityType)) {
				return contentService.getGroupItemCount(repositoryId);
			}
			return 0;
		} catch (Exception e) {
			log.warn("Failed to get CouchDB " + entityType + " count for repository " + repositoryId
					+ " — skipping coverage check", e);
			return 0;
		}
	}

	/** Visible for testing: reset the cached probe state for all repositories. */
	void resetProbeState() {
		solrFieldsReadyMap.clear();
		repoLocks.clear();
	}

	/** Visible for testing: inject a ProbeState with custom timestamp. */
	void setProbeState(String repositoryId, ProbeState state) {
		solrFieldsReadyMap.put(repositoryId, state);
	}

	@Override
	public SearchResult searchUsers(String repositoryId, String query, int offset, int limit) {
		return executeSearch(repositoryId, query, offset, limit, "nemaki\\:user",
				"dynamic.usersearch.user_id_lc", "dynamic.usersearch.name_lc", true);
	}

	@Override
	public SearchResult searchGroups(String repositoryId, String query, int offset, int limit) {
		return executeSearch(repositoryId, query, offset, limit, "nemaki\\:group",
				"dynamic.usersearch.group_id_lc", "dynamic.usersearch.name_lc", true);
	}

	@Override
	public SearchResult searchUsersCaseSensitive(String repositoryId, String query, int limit) {
		return executeSearch(repositoryId, query, 0, limit, "nemaki\\:user",
				"dynamic.usersearch.user_id", "name", false);
	}

	@Override
	public SearchResult searchGroupsCaseSensitive(String repositoryId, String query, int limit) {
		return executeSearch(repositoryId, query, 0, limit, "nemaki\\:group",
				"dynamic.usersearch.group_id", "name", false);
	}

	private SearchResult executeSearch(String repositoryId, String query, int offset, int limit,
			String objectType, String idField, String nameField, boolean caseInsensitive) {
		try {
			String term = caseInsensitive ? query.toLowerCase(Locale.ROOT) : query;
			String escapedTerm = ClientUtils.escapeQueryChars(term);

			String solrQuery = "(" + idField + ":*" + escapedTerm + "* OR "
					+ nameField + ":*" + escapedTerm + "*) AND objecttype:" + objectType;

			if (limit > 0) {
				// Paginated mode: fetch exactly 'limit' results via batch loop if needed
				return fetchBatch(repositoryId, solrQuery, offset, limit);
			} else {
				// Non-paginated mode: fetch all results
				return fetchAll(repositoryId, solrQuery);
			}

		} catch (Exception e) {
			log.warn("Solr user/group search error for repository " + repositoryId, e);
			return SearchResult.error(e.getMessage());
		}
	}

	/**
	 * Fetch exactly 'limit' results starting at 'offset', using batch loop
	 * when limit exceeds DEFAULT_MAX_ROWS.
	 */
	private SearchResult fetchBatch(String repositoryId, String solrQuery, int offset, int limit) {
		List<String> allIds = new ArrayList<>();
		int currentStart = offset;
		int remaining = limit;
		long totalNumFound = 0;

		while (remaining > 0) {
			int batchRows = Math.min(remaining, DEFAULT_MAX_ROWS);

			SolrQueryResult r = solrIndexMaintenanceService.executeSolrQuery(
					repositoryId, solrQuery, currentStart, batchRows, STABLE_SORT, "object_id");

			if (r.getErrorMessage() != null) {
				log.warn("Solr user/group search failed for repository " + repositoryId
						+ ": " + r.getErrorMessage());
				return SearchResult.error(r.getErrorMessage());
			}

			totalNumFound = r.getNumFound();
			List<String> batchIds = extractObjectIds(r);
			allIds.addAll(batchIds);

			if (batchIds.size() < batchRows) {
				break; // last page
			}
			remaining -= batchIds.size();
			currentStart += batchIds.size();
		}
		return new SearchResult(allIds, totalNumFound);
	}

	/**
	 * Fetch all matching results (non-paginated) via batch loop.
	 */
	private SearchResult fetchAll(String repositoryId, String solrQuery) {
		List<String> allIds = new ArrayList<>();
		int currentStart = 0;
		long totalNumFound = 0;

		while (true) {
			SolrQueryResult r = solrIndexMaintenanceService.executeSolrQuery(
					repositoryId, solrQuery, currentStart, DEFAULT_MAX_ROWS, STABLE_SORT, "object_id");

			if (r.getErrorMessage() != null) {
				log.warn("Solr user/group search failed at batch offset " + currentStart
						+ " for repository " + repositoryId + ": " + r.getErrorMessage());
				return SearchResult.error(r.getErrorMessage());
			}

			totalNumFound = r.getNumFound();
			List<String> batchIds = extractObjectIds(r);
			allIds.addAll(batchIds);

			if (batchIds.size() < DEFAULT_MAX_ROWS) {
				break; // last page
			}
			currentStart += batchIds.size();
		}
		return new SearchResult(allIds, totalNumFound);
	}

	private List<String> extractObjectIds(SolrQueryResult solrResult) {
		List<String> objectIds = new ArrayList<>();
		if (solrResult.getDocs() != null) {
			for (Map<String, Object> doc : solrResult.getDocs()) {
				Object oid = doc.get("object_id");
				if (oid != null) {
					objectIds.add(oid.toString());
				}
			}
		}
		return objectIds;
	}

	// Spring DI setters
	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setSolrIndexMaintenanceService(SolrIndexMaintenanceService solrIndexMaintenanceService) {
		this.solrIndexMaintenanceService = solrIndexMaintenanceService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
