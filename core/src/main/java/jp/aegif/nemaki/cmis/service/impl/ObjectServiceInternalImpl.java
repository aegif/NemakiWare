package jp.aegif.nemaki.cmis.service.impl;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;

public class ObjectServiceInternalImpl implements jp.aegif.nemaki.cmis.service.ObjectServiceInternal{
	private static final Log log = LogFactory
			.getLog(ObjectServiceInternalImpl.class);
	
	private ContentService contentService;
	private ExceptionService exceptionService;
	private NemakiCachePool nemakiCachePool;
	
	
	@Override
	public void deleteObjectInternal(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions, Boolean deleteWithParent) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContent(repositoryId, objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				repositoryId, PermissionMapping.CAN_DELETE_OBJECT, content);
		exceptionService.constraintDeleteRootFolder(repositoryId, objectId);

		// //////////////////
		// Body of the method
		// //////////////////
		if (content.isDocument()) {
			contentService.deleteDocument(callContext, repositoryId,
					content.getId(), allVersions, deleteWithParent);
		} else if (content.isFolder()) {
			List<Content> children = contentService.getChildren(repositoryId, objectId);
			if (!CollectionUtils.isEmpty(children)) {
				exceptionService
						.constraint(objectId,
								"deleteObject method is invoked on a folder containing objects.");
			}
			contentService.delete(callContext, repositoryId, objectId, deleteWithParent);

		} else {
			contentService.delete(callContext, repositoryId, objectId, deleteWithParent);
		}

		nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());
	}


	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}


	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}
	
	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}
}
