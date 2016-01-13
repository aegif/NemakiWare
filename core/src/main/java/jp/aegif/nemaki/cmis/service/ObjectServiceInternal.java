package jp.aegif.nemaki.cmis.service;

import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface ObjectServiceInternal {
	/**
	 * Delete each object together with deleteWithParent flag archived
	 * @param callContext
	 * @param repositoryId
	 * @param objectId
	 * @param allVersions
	 * @param deleteWithParent
	 */
	public abstract void deleteObjectInternal(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions, Boolean deleteWithParent);
}
