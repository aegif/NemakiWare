package jp.aegif.nemaki.cmis.service;

import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

public interface ObjectServiceInternal {
	/**
	 * wrap method
	 * @param callContext
	 * @param repositoryId
	 * @param content
	 * @param allVersions
	 * @param deleteWithParent
	 */
	public abstract void deleteObjectInternal(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("content")Content content, @LogParam("allVersions")Boolean allVersions, @LogParam("deleteWithParent")Boolean deleteWithParent);
	
	/**
	 * Delete each object together with deleteWithParent flag archived
	 * @param callContext
	 * @param repositoryId
	 * @param objectId
	 * @param allVersions
	 * @param deleteWithParent
	 */
	public abstract void deleteObjectInternal(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId,
			@LogParam("objectId")String objectId, @LogParam("allVersions")Boolean allVersions, @LogParam("deleteWithParent")Boolean deleteWithParent);
}
