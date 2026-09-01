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
		// intValue() TRUNCATES: 2^31 becomes negative, 2^32 becomes 0 — and a non-positive
		// limit reads as "no limit" one layer down, so an out-of-range maxItems bought an
		// unbounded query over the whole change log. Compare first, truncate never.
		int limit = maxItems.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) >= 0
				? Integer.MAX_VALUE
				: maxItems.intValue();
		// A non-positive ask means "one page" here, BEFORE the resume adjustment below.
		// Normalising only at the DAO left maxItems=0 + a resume token computing
		// fetchLimit = 0 + 1 = 1: the single fetched row is the already-delivered resume
		// row, skipFirst removes it, the token never advances, and a polling client loops
		// on the same empty page for ever while hasMoreItems stays true (round-33 review).
		if (limit <= 0) {
			limit = Integer.MAX_VALUE;
		}

		// CouchDB startkey is inclusive, so when resuming from a previous token
		// we fetch one extra and drop the first row to avoid returning the
		// event that was already delivered as the last item of the prior batch.
		boolean skipFirst = (startToken != null);
		// limit + 1 overflows for Integer.MAX_VALUE (the "give me everything" request) and a
		// negative limit reads as "no limit" one layer down — an accidental unbounded query.
		int fetchLimit = (skipFirst && limit < Integer.MAX_VALUE) ? limit + 1 : limit;

		List<Change> changes = contentDaoService.getLatestChanges(repositoryId, startToken, fetchLimit);

		// A change row that would not decode is a change that HAPPENED, at a token between the
		// ones that did. compileChangeDataList advances the CLIENT's changeLogToken over the
		// consecutive range it receives — it can only guard against compile failures INSIDE the
		// list, not against rows the DAO already dropped, which look perfectly consecutive from
		// here. Serving [100, 102] advances the client to 102 and the change at 101 is never
		// delivered to that client again; if it was a DELETE, the client keeps the object for
		// ever. The Purview sync got this guard first and this — the CMIS path every external
		// subscriber uses — did not.
		if (contentDaoService.lastUnreadableChangeCount() > 0) {
			throw new IllegalStateException("the change log lost "
					+ contentDaoService.lastUnreadableChangeCount() + " row(s) to decode"
					+ " failures after token '" + (startToken == null ? "" : startToken)
					+ "'; serving the remainder would advance the client's changeLogToken past"
					+ " changes it never received");
		}

		if (skipFirst && changes != null && !changes.isEmpty()) {
			// Only the row that WAS already delivered. The unconditional remove(0) assumed the
			// first row is always the startToken's own event — but if that row failed to
			// decode (or was purged), position 0 holds the NEXT real change, and dropping it
			// silently swallowed one event per resume. Purview's normalizeChanges has checked
			// the token for as long as it has existed; this sibling did not.
			Change first = changes.get(0);
			// EQUALITY only, like Purview's normalizeChanges: a null-token first row is NOT
			// the already-delivered event — the first version of this check also removed
			// null-token rows, which "delivered-and-dropped" a change nobody had seen.
			if (first != null && startToken.equals(first.getToken())) {
				changes.remove(0);
			}
		}

		// NOTE: Token advancement is intentionally NOT done here.
		// compileChangeDataList is responsible for advancing the token
		// based on the consecutive range of successfully compiled events,
		// preventing permanent loss of events that fail to compile.

		return changes;
	}

	public String getLatestChangeToken(String repositoryId) {
		Change latest = contentDaoService.getLatestChange(repositoryId);
		if (latest == null) {
			// Per CMIS spec: null is acceptable when there are no changes in the repository
			return null;
		} else {
			// Must return the change-log TOKEN (the value clients pass back to
			// getContentChanges and that compileChangeDataList advances), NOT the
			// CouchDB document _id. Returning the _id put RepositoryInfo
			// .latestChangeLogToken in a different value-space than the per-event
			// token, so hasMoreItems could never reach equality (endless drain)
			// and the published cursor was not parseable by getContentChanges.
			return latest.getToken();
		}
	}
}
