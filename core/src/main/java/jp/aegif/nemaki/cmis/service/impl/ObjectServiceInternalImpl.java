package jp.aegif.nemaki.cmis.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * Cascade delete: each parentChild child is deleted via deleteObjectInternal so that
 * permission (CAN_DELETE_OBJECT), lock (ThreadLockService), and cache invalidation
 * are applied per child. Loop detection uses a thread-local visited set.
 */
public class ObjectServiceInternalImpl implements jp.aegif.nemaki.cmis.service.ObjectServiceInternal{
	private static final Log log = LogFactory
			.getLog(ObjectServiceInternalImpl.class);

	/** Thread-local set of object IDs currently being deleted in a cascade (for loop detection). */
	private static final ThreadLocal<Set<String>> CASCADE_VISITED = new ThreadLocal<>();

	private ContentService contentService;
	private ExceptionService exceptionService;
	private ThreadLockService threadLockService;
	private NemakiCachePool nemakiCachePool;

	@Override
	public void deleteObjectInternal(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions, Boolean deleteWithParent) {
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContent(repositoryId, objectId);

		deleteObjectInternal(callContext, repositoryId, content, allVersions, deleteWithParent);
	}

	@Override
	public void deleteObjectInternal(CallContext callContext, String repositoryId,
			Content content, Boolean allVersions, Boolean deleteWithParent) {

		if (content == null) {
			log.warn("deleteObjectInternal: content is null, skipping deletion");
			return;
		}
		String objectId = content.getId();
		Set<String> visited = getOrCreateCascadeVisited(deleteWithParent);
		if (visited != null && visited.contains(objectId)) {
			log.debug("deleteObjectInternal: Skip cascade loop for objectId=" + objectId);
			return;
		}
		if (visited != null) {
			visited.add(objectId);
		}

		Lock lock = threadLockService.getWriteLock(repositoryId, content.getId());

		try{
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_DELETE_OBJECT, content);
			exceptionService.constraintDeleteRootFolder(repositoryId, objectId);

			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);

			// //////////////////
			// ParentChild cascade: delete children via deleteObjectInternal so each
			// child gets permission check, lock, and cache invalidation.
			// //////////////////
			if (!content.isRelationship()) {
				List<String> childIds = contentService.getParentChildChildIds(repositoryId, objectId);
				for (String childId : childIds) {
					if (visited != null && visited.contains(childId)) {
						continue;
					}
					Content childContent = contentService.getContent(repositoryId, childId);
					if (childContent == null) {
						continue;
					}
					log.debug("deleteObjectInternal: Cascading to parentChild child objectId=" + childId);
					deleteObjectInternal(callContext, repositoryId, childContent, allVersions, true);
				}
			}

			// //////////////////
			// Body of the method
			// //////////////////
			if (content.isDocument()) {

		// CRITICAL TCK FIX (2025-11-01): Version deletion validation
		// CMIS 1.1 spec: Individual version deletion only allowed for latest version
		// Version series deletion (allVersions=true) can be done from any version
		jp.aegif.nemaki.model.Document doc = (jp.aegif.nemaki.model.Document) content;

		// Check if this is a versioned document and NOT the latest version
		if (doc.getVersionSeriesId() != null && !doc.isLatestVersion()) {
			// If allVersions is false or null, cannot delete non-latest version individually
			if (allVersions == null || !allVersions.booleanValue()) {
				if (log.isDebugEnabled()) {
					log.debug("Blocking deletion of non-latest version: document=" +
						doc.getId() + ", versionLabel=" + doc.getVersionLabel() +
						", isLatestVersion=" + doc.isLatestVersion() +
						", allVersions=" + allVersions);
				}
				exceptionService.constraint(doc.getId(),
					"Cannot delete non-latest version individually. " +
					"Only the latest version can be deleted, or use allVersions=true to delete the entire version series.");
			}
			// If allVersions=true, allow deletion (will delete entire version series)
		}

				contentService.deleteDocument(callContext, repositoryId,
						content.getId(), allVersions, deleteWithParent);
			} else if (content.isFolder()) {
				List<Content> children = contentService.getChildren(repositoryId, objectId);
				if (!CollectionUtils.isEmpty(children)) {
					// Allow deletion of folders with children during cascading operations (deleteWithParent=true)
					// or when the folder deletion is part of a larger operation
					if (deleteWithParent == null || !deleteWithParent) {
						exceptionService
								.constraint(objectId,
										"deleteObject method is invoked on a folder containing objects.");
					} else {
						// Recursively delete children first when deleteWithParent is true
						for (Content child : children) {
							deleteObjectInternal(callContext, repositoryId, child, allVersions, true);
						}
					}
				}
				contentService.delete(callContext, repositoryId, objectId, deleteWithParent);

			} else if(content.isRelationship()){
				//TODO: move to relationship service?
				Relationship rel = contentService.getRelationship(repositoryId, objectId);
				String targetObjectId = rel.getTargetId();
				String sourceObjectId = rel.getSourceId();
				contentService.delete(callContext, repositoryId, objectId, deleteWithParent);

				nemakiCachePool.get(repositoryId).removeCmisCache(targetObjectId);
				nemakiCachePool.get(repositoryId).removeCmisCache(sourceObjectId);
			} else{
				contentService.delete(callContext, repositoryId, objectId, deleteWithParent);
			}

			nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());

		} finally {
			lock.unlock();
			releaseCascadeVisited(deleteWithParent, objectId, visited);
		}
	}

	/**
	 * Get or create the thread-local set used for cascade loop detection.
	 * When deleteWithParent is false we are at the top level and create the set.
	 */
	private static Set<String> getOrCreateCascadeVisited(Boolean deleteWithParent) {
		if (deleteWithParent != null && deleteWithParent) {
			return CASCADE_VISITED.get();
		}
		Set<String> set = new HashSet<>();
		CASCADE_VISITED.set(set);
		return set;
	}

	/**
	 * Remove current object from visited set or clear thread-local when leaving top-level delete.
	 */
	private static void releaseCascadeVisited(Boolean deleteWithParent, String objectId, Set<String> visited) {
		if (visited == null) {
			return;
		}
		if (deleteWithParent != null && deleteWithParent) {
			visited.remove(objectId);
		} else {
			CASCADE_VISITED.remove();
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}
}
