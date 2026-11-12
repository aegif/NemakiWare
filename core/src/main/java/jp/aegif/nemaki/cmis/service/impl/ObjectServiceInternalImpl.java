package jp.aegif.nemaki.cmis.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
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
	/** Thread-local depth counter for cascade to determine lifecycle of visited set. */
	private static final ThreadLocal<Integer> CASCADE_DEPTH = new ThreadLocal<>();

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
		if (objectId == null) {
			log.warn("deleteObjectInternal: content.getId() is null, skipping deletion");
			return;
		}
		Set<String> visited = getOrCreateCascadeVisited();
		if (visited != null && visited.contains(objectId)) {
			log.debug("deleteObjectInternal: Skip cascade loop for objectId=" + objectId);
			return;
		}
		// Increment depth AFTER loop check so early returns don't leak CASCADE_DEPTH
		incrementCascadeDepth();
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

			// A user's item is deleted as a user (R115). Whether it may be is decided once, here,
			// before the parentChild cascade below, and the delete further down carries the decision
			// out without asking again: a refusal that came after the cascade would leave the
			// children deleted and the user in place.
			final ContentService.UserItemDeletion userItemDeletion =
					content instanceof jp.aegif.nemaki.model.UserItem user
							? refusedAsCmis(() -> contentService.checkUserItemDeletion(callContext, repositoryId, user))
							: null;

			// Every version a delete removes is asked first (R323, 3.4.2). Unless allVersions is false, the whole version
			// series goes — deleteDocument removes each version getAllVersions answers this caller — and only the named
			// object used to be asked: a caller who may delete the latest version removed versions it may neither delete
			// nor read, and was then answered their content from the archive. Each of them is asked here, before the
			// parentChild cascade below and before anything is deleted, so one refusal deletes nothing. With allVersions
			// false only the named version is deleted, and only it is asked, as before.
			if (content.isDocument() && !Boolean.FALSE.equals(allVersions)) {
				askEveryVersionItDeletes(callContext, repositoryId, (jp.aegif.nemaki.model.Document) content);
			}

			// A delete that would leave a private working copy without its version series is not made (R325, R329 — 3.4.2):
			// the series went and the working copy stayed, without it, and its holder's reads and every non-administrator's
			// checked-out listing answered 500. Refused here, before the parentChild cascade and before anything is deleted,
			// so the document, the series and the working copy all stay.
			if (content.isDocument()) {
				refuseADeleteThatLeavesAWorkingCopyWithoutItsSeries(callContext, repositoryId,
						(jp.aegif.nemaki.model.Document) content, allVersions);
			}

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
					// A parentChild child that is not a CMIS object — a version series, an attachment, a passkey — is not
					// deleted with its parent (R290, 3.4.2): deleteObject answers such an id as one that does not exist
					// (R281), and the cascade handed it to the generic delete below, which removed the row and left the
					// document that points at it. The parent's own delete is not refused for it.
					if (!ObjectServiceImpl.isCmisObject(childContent)) {
						log.warn("deleteObjectInternal: Skipping a parentChild child that is not a CMIS object. childId=" + childId);
						continue;
					}
					log.debug("deleteObjectInternal: Cascading to parentChild child objectId=" + childId);
					try {
						deleteObjectInternal(callContext, repositoryId, childContent, allVersions, true);
					} catch (CmisPermissionDeniedException | CmisConstraintException | CmisObjectNotFoundException
							| org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException e) {
						// A child whose delete would leave a working copy without its series (R325, R329) is skipped as a
						// refused child is: it stays.
						log.warn("deleteObjectInternal: Skipping child delete due to non-fatal error. childId=" +
								childId + ", error=" + e.getMessage());
						continue;
					}
				}
			}

			// //////////////////
			// Body of the method
			// //////////////////
			if (content.isDocument()) {
				// Version deletion validation (CMIS 1.1 spec):
				// Individual version deletion only allowed for latest version.
				// Version series deletion (allVersions=true) can be done from any version.
				jp.aegif.nemaki.model.Document doc = (jp.aegif.nemaki.model.Document) content;

				if (doc.getVersionSeriesId() != null && !doc.isLatestVersion()) {
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
				// A folder whose only children are rows the repository cannot decode LOOKS
				// empty here, slips past the constraint check below, and is deleted over them
				// — the same orphaning deleteTreeDFS guards against, reachable through plain
				// deleteObject. Refused for both arms: as a "leaf" delete and as a cascade.
				if (contentService.lastUnreadableChildCount() > 0) {
					exceptionService.constraint(objectId,
							"this folder's listing is incomplete ("
									+ contentService.lastUnreadableChildCount()
									+ " child row(s) could not be read), so whether it is empty"
									+ " is unknown; deleting it could orphan the unreadable"
									+ " children.");
				}
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
			} else if (userItemDeletion != null) {
				// A user's item is deleted as a user: it strips the user's group memberships and
				// refuses the built-in accounts and the acting user (R115). The generic delete below
				// did neither. CMIS deleteObject — the browser binding, AtomPub, OData and the Jersey
				// /items/{id} and /objects/{id} — took it: an administrator deleted a user's item with
				// one browser-binding request on a 3.4.0 build (measured 2026-10-09, with a test user),
				// nothing on that path singles out the built-in administrator's item (read), and the
				// deleted id stayed in every group that listed it. The caller's context is kept, so
				// the change event, the webhook and the archive still name the acting user.
				contentService.deleteUserItem(userItemDeletion, deleteWithParent);
			} else{
				contentService.delete(callContext, repositoryId, objectId, deleteWithParent);
			}

			nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());

		} finally {
			lock.unlock();
			releaseCascadeVisited(objectId, visited);
		}
	}

	/**
	 * Asks {@code CAN_DELETE_OBJECT} of every version a delete of the whole version series removes (R323, 3.4.2): the
	 * versions {@code ContentService.getAllVersions} answers this caller, which is the list
	 * {@code ContentService.deleteDocument} deletes. The named document has been asked already. A listing that cannot be
	 * read is thrown as it is, and a listing that answers nothing or holds a row it cannot name is refused — nothing has
	 * been deleted yet.
	 */
	private void askEveryVersionItDeletes(CallContext callContext, String repositoryId,
			jp.aegif.nemaki.model.Document named) {
		List<jp.aegif.nemaki.model.Document> versions =
				contentService.getAllVersions(callContext, repositoryId, named.getVersionSeriesId());
		if (versions == null) {
			throw new IllegalStateException("the versions of '" + named.getId() + "' were answered as nothing, so"
					+ " whether the caller may delete each of them cannot be asked; nothing was deleted");
		}
		for (jp.aegif.nemaki.model.Document version : versions) {
			if (version == null || version.getId() == null) {
				throw new IllegalStateException("the versions of '" + named.getId() + "' hold a row without an id, so"
						+ " whether the caller may delete it cannot be asked; nothing was deleted");
			}
			if (!version.getId().equals(named.getId())) {
				exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_DELETE_OBJECT, version);
			}
		}
	}

	/**
	 * Refuses, as a versioning conflict (409), a delete that would leave a private working copy without its version
	 * series — before anything is deleted. A versioning exception, not a constraint: the Browser binding answers every
	 * constraint 400, and this refusal is answered 409 in the CMIS bindings and API v1. Whether the series is checked out,
	 * and by whom, is read from the version series, as the permission service reads a working copy's holder.
	 *
	 * <p>Another user's (R325): a delete of a document whose series is checked out by a user other than the caller — an
	 * administrator included, with any {@code allVersions}, since a delete of the only version removes the series too.
	 * A series checked out with no holder named is refused too. So is the other user's working copy named itself:
	 * deleteDocument deletes the versions this caller is listed and then the series, and the list leaves that working copy
	 * out — an administrator cancels another user's check-out by cancelCheckOut instead.
	 *
	 * <p>The caller's own (R329): a delete by the holder, with {@code allVersions} false, of a version whose series would
	 * keep no checked-in version without it — deleteDocument then deletes the series, and the holder's working copy, which
	 * it does not delete, stays without it. Refused only while that working copy is there; one the series names that is
	 * not there is not kept. A series checked out with no working copy named is taken to have one. Where a checked-in
	 * version is left, the delete goes on as before (another version becomes the latest), and so do the holder's delete of
	 * every version (its working copy is deleted with them) and its own working copy named (with {@code allVersions} false
	 * that is refused below as a version that is not the latest; Browser and AtomPub cancel the check-out instead).
	 *
	 * <p>A series, a version listing or a working copy that cannot be read is thrown as it is; a document without a
	 * series, or whose series is not there, has no working copy to leave behind.
	 */
	private void refuseADeleteThatLeavesAWorkingCopyWithoutItsSeries(CallContext callContext, String repositoryId,
			jp.aegif.nemaki.model.Document named, Boolean allVersions) {
		if (named.getVersionSeriesId() == null) {
			return;
		}
		jp.aegif.nemaki.model.VersionSeries series = contentService.getVersionSeries(repositoryId, named);
		if (series == null || !Boolean.TRUE.equals(series.isVersionSeriesCheckedOut())) {
			return;
		}
		String holder = series.getVersionSeriesCheckedOutBy();
		if (holder == null || !holder.equals(callContext.getUsername())) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException("The version series is"
					+ " checked out by another user. Deleting the document would leave that user's private working copy"
					+ " without its version series, so nothing was deleted; the check-out has to be cancelled or checked in"
					+ " first. [objectId=" + named.getId() + "]", java.math.BigInteger.valueOf(409));
		}
		if (Boolean.FALSE.equals(allVersions)
				&& contentService.committedVersionsLeftAfterDeleting(repositoryId, named).isEmpty()
				&& workingCopyIsThere(repositoryId, series)) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException("The version series is"
					+ " checked out by the caller, and this is its only checked-in version. Deleting it alone would delete the"
					+ " version series and leave the caller's private working copy without it, so nothing was deleted; cancel"
					+ " the check-out or check it in first, or delete every version. [objectId=" + named.getId() + "]",
					java.math.BigInteger.valueOf(409));
		}
	}

	/** Whether the working copy {@code series} names is there — taken to be, when the series names none. */
	private boolean workingCopyIsThere(String repositoryId, jp.aegif.nemaki.model.VersionSeries series) {
		String workingCopyId = series.getVersionSeriesCheckedOutId();
		return workingCopyId == null || contentService.getContent(repositoryId, workingCopyId) != null;
	}

	/** A protected user's refusal (R115), answered as CMIS answers a permission it does not grant. */
	private static <T> T refusedAsCmis(java.util.function.Supplier<T> decision) {
		try {
			return decision.get();
		} catch (jp.aegif.nemaki.businesslogic.ProtectedPrincipals.RefusedException refused) {
			throw new CmisPermissionDeniedException(refused.getMessage());
		}
	}

	/**
	 * Get or create the thread-local set used for cascade loop detection.
	 * Does NOT modify CASCADE_DEPTH; callers must call incrementCascadeDepth()
	 * after confirming the deletion should proceed (i.e. after loop detection check).
	 */
	private static Set<String> getOrCreateCascadeVisited() {
		Set<String> set = CASCADE_VISITED.get();
		if (set == null) {
			set = new HashSet<>();
			CASCADE_VISITED.set(set);
		}
		return set;
	}


	/**
	 * Increment the cascade depth counter. Must be called after loop detection
	 * confirms the deletion should proceed.
	 */
	private static void incrementCascadeDepth() {
		Integer depth = CASCADE_DEPTH.get();
		CASCADE_DEPTH.set(depth == null ? 1 : depth + 1);
	}

	/**
	 * Remove current object from visited set or clear thread-local when leaving top-level delete.
	 */
	private static void releaseCascadeVisited(String objectId, Set<String> visited) {
		if (visited == null) {
			return;
		}
		visited.remove(objectId);
		Integer depth = CASCADE_DEPTH.get();
		if (depth == null || depth <= 1) {
			CASCADE_DEPTH.remove();
			CASCADE_VISITED.remove();
			return;
		}
		CASCADE_DEPTH.set(depth - 1);
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
