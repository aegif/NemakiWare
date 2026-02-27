package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Delegate for change event query operations.
 * Extracted from ContentServiceImpl as part of class decomposition.
 */
public class ChangeEventServiceDelegate {

	private final ContentDaoService contentDaoService;

	public ChangeEventServiceDelegate(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		return contentDaoService.getChangeEvent(repositoryId, changeTokenId);
	}

	public List<Change> getLatestChanges(String repositoryId, CallContext context, Holder<String> changeLogToken,
			Boolean includeProperties, String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems,
			ExtensionsData extension) {
		String startToken = changeLogToken.getValue();
		int limit = maxItems.intValue();

		// CouchDB startkey is inclusive, so when resuming from a previous token
		// we fetch one extra and drop the first row to avoid returning the
		// event that was already delivered as the last item of the prior batch.
		boolean skipFirst = (startToken != null);
		int fetchLimit = skipFirst ? limit + 1 : limit;

		List<Change> changes = contentDaoService.getLatestChanges(repositoryId, startToken, fetchLimit);

		if (skipFirst && changes != null && !changes.isEmpty()) {
			changes.remove(0);
		}

		// Advance the changeLogToken to the last entry's token so the next
		// poll starts after the current result set (CMIS spec §2.2.6.1).
		if (changes != null && !changes.isEmpty()) {
			Change last = changes.get(changes.size() - 1);
			String nextToken = last.getToken();
			if (nextToken == null) {
				nextToken = last.getId();
			}
			changeLogToken.setValue(nextToken);
		}

		return changes;
	}

	public String getLatestChangeToken(String repositoryId) {
		Change latest = contentDaoService.getLatestChange(repositoryId);
		if (latest == null) {
			// Per CMIS spec: null is acceptable when there are no changes in the repository
			return null;
		} else {
			return String.valueOf(latest.getId());
		}
	}
}
