package jp.aegif.nemaki.cmis.service;

import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.model.Content;

public interface ObjectServiceInternal {
	/**
	 * wrap method
	 * @param callContext
	 * @param repositoryId
	 * @param content
	 * @param allVersions
	 * @param deleteWithParent
	 */
	public abstract void deleteObjectInternal(CallContext callContext, String repositoryId,
			Content content, Boolean allVersions, Boolean deleteWithParent);
	
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
