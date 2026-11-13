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

	/**
	 * Refuses, before anything is deleted, a delete of every version of {@code document} that the CMIS delete would refuse
	 * (R326, 3.4.2): {@code CAN_DELETE_OBJECT} of the document and of every version such a delete removes (R323), and a
	 * delete that would leave another user's working copy without its version series (R325) — by the methods
	 * {@link #deleteObjectInternal} asks them with. For a caller that deletes the document itself, by
	 * {@code ContentService.deleteDocument}: the ZIP import's replace asks this before it creates or deletes anything.
	 * Returns normally when the delete may be made; a refusal is thrown as the CMIS delete throws it (403, 409), and a
	 * question that cannot be asked is thrown as it is.
	 */
	public abstract void refuseADeleteOfEveryVersion(@LogParam("callContext")CallContext callContext,
			@LogParam("repositoryId")String repositoryId, @LogParam("document")jp.aegif.nemaki.model.Document document);
}
