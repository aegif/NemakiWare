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
		return contentDaoService.getLatestChanges(repositoryId, changeLogToken.getValue(), maxItems.intValue());
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
