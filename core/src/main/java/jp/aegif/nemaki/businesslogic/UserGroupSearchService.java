package jp.aegif.nemaki.businesslogic;

import java.util.List;

/**
 * Service for searching users and groups via Solr index.
 * Falls back to CouchDB when Solr is unavailable or disabled.
 */
public interface UserGroupSearchService {

	class SearchResult {
		private List<String> objectIds;
		private long totalCount;
		private String errorMessage;
		private boolean error;

		public SearchResult() {
			this.objectIds = java.util.Collections.emptyList();
		}

		public SearchResult(List<String> objectIds, long totalCount) {
			this.objectIds = objectIds != null ? objectIds : java.util.Collections.emptyList();
			this.totalCount = totalCount;
		}

		public static SearchResult error(String message) {
			SearchResult result = new SearchResult();
			result.error = true;
			result.errorMessage = message != null ? message : "Unknown error";
			return result;
		}

		public List<String> getObjectIds() {
			return objectIds;
		}

		public void setObjectIds(List<String> objectIds) {
			this.objectIds = objectIds != null ? objectIds : java.util.Collections.emptyList();
		}

		public long getTotalCount() {
			return totalCount;
		}

		public void setTotalCount(long totalCount) {
			this.totalCount = totalCount;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public void setErrorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
		}

		public boolean hasError() {
			return error;
		}
	}

	boolean isSolrSearchEnabled();

	/**
	 * Returns true if Solr user search is both enabled AND the required dynamic fields
	 * are confirmed to exist with sufficient coverage against CouchDB.
	 * Probed independently from group search to avoid cross-type masking.
	 */
	boolean isSolrUserSearchEffective(String repositoryId);

	/**
	 * Returns true if Solr group search is both enabled AND the required dynamic fields
	 * are confirmed to exist with sufficient coverage against CouchDB.
	 * Probed independently from user search to avoid cross-type masking.
	 */
	boolean isSolrGroupSearchEffective(String repositoryId);

	/** Case-insensitive substring search for /list?query= */
	SearchResult searchUsers(String repositoryId, String query, int offset, int limit);
	SearchResult searchGroups(String repositoryId, String query, int offset, int limit);

	/** Case-sensitive substring search for /search */
	SearchResult searchUsersCaseSensitive(String repositoryId, String query, int limit);
	SearchResult searchGroupsCaseSensitive(String repositoryId, String query, int limit);
}
