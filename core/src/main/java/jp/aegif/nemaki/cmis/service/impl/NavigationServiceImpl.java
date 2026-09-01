/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.service.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;

public class NavigationServiceImpl implements NavigationService {
	private static final Log log = LogFactory.getLog(NavigationServiceImpl.class);

	private ContentService contentService;
	private ExceptionService exceptionService;
	private CompileService compileService;
	private PermissionService permissionService;
	private ThreadLockService threadLockService;
	private PropertyManager propertyManager;

	@Override
	public ObjectInFolderList getChildren(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments,
			BigInteger maxItems, BigInteger skipCount,
			Holder<ObjectData> parentObjectData, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		
		// The folder's lock is held for the folder's OWN compile and released before the children
		// are listed. It used to stay held across the whole listing, which put it outside the
		// ordered set the children are taken as — and a lock held outside a set cannot be ordered
		// against it. The live detector caught precisely that: two listings of different folders,
		// each holding its own folder and reaching for a child of the other, in opposite orders.
		// getChildrenInternal now takes the folder together with its children as one ordered set,
		// so the folder is still locked while the listing is built.
		Lock parentLock = threadLockService.getReadLock(repositoryId, folderId);

		try{
			parentLock.lock();
			
			// //////////////////
			// General Exception
			// //////////////////
			Folder folder = contentService.getFolder(repositoryId, folderId);
			exceptionService.invalidArgumentFolderId(folder, folderId);
			if (log.isDebugEnabled()) {
				log.debug("NavigationService.getChildren called - Repository: " + repositoryId + 
					", Folder ID: " + folderId + ", User: " + callContext.getUsername());
			}
			
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_CHILDREN_FOLDER, folder);

			// //////////////////
			// Body of the method
			// //////////////////
			// Set ObjectData of parent folder for ObjectInfo
			ObjectData _parent = compileService.compileObjectData(
					callContext, repositoryId, folder, filter,
					includeAllowableActions, includeRelationships, renditionFilter, false);
			parentObjectData.setValue(_parent);

		}finally{
			parentLock.unlock();
		}

		return getChildrenInternal(callContext, repositoryId, folderId, filter,
				orderBy, includeAllowableActions, includeRelationships,
				renditionFilter, includePathSegments, maxItems, skipCount, false);
	}

	/** The verified lock set for an object and its CURRENT parent, plus that parent (may be null). */
	record ParentLockHold(List<Lock> locks, Folder parent) {
	}

	/** Re-previews after a mismatch this many times before giving up with a conflict. */
	private static final int PARENT_LOCK_RETRIES = 2;

	/**
	 * Lock an object together with its current parent as one ordered set, retrying while a
	 * concurrent move keeps changing which parent that is.
	 *
	 * <p>The parent is resolved once unlocked (to decide what to lock) and once locked (to decide
	 * what to answer). Those normally agree, but a request that arrives while a move holds the
	 * child's lock previews the OLD parent and only gets its locks after the move commits — at
	 * which point the re-read shows the new one. Failing that with a conflict outright, as the
	 * first version of this method did, turned "a read that waited politely for a move" into a
	 * deterministic 409, when the correct answer was one re-preview away; the pre-restructure
	 * nested-lock code answered it, at the price of the lock-order inversion that code was
	 * removed for. So: drop the set, preview again, relock. Answering WITHOUT the retry is not an
	 * option either way — the changed parent's lock was never taken, and compiling an object
	 * nothing holds still is the defect the ordered set exists to remove.
	 *
	 * <p>The retry is bounded; an object being moved in a sustained loop eventually gets the
	 * conflict, which by then is describing reality.
	 */
	ParentLockHold lockObjectAndCurrentParent(String repositoryId, String objectId) {
		for (int attempt = 0;; attempt++) {
			Folder preview = contentService.getParent(repositoryId, objectId);
			List<Lock> locks = threadLockService.orderedLocks(repositoryId,
					preview == null ? java.util.List.of(objectId)
							: java.util.List.of(objectId, preview.getId()),
					false);
			threadLockService.bulkLock(locks);
			boolean sameParent;
			Folder actual;
			try {
				// The guard spans everything between acquiring and the decision — the re-read
				// hits CouchDB and can fail like any read, and even the comparison can throw on
				// malformed data (a folder with a null id). Without the release, one transient
				// error here would leave the child's and parent's READ holds attached to a thread
				// that has already unwound — permanently, which is indefinite write failure on
				// those stripes. Exactly the unbounded outcome this whole area exists to remove,
				// arriving through its own helper.
				actual = contentService.getParent(repositoryId, objectId);
				sameParent = (preview == null && actual == null)
						|| (preview != null && actual != null && preview.getId() != null
								&& preview.getId().equals(actual.getId()));
			} catch (RuntimeException | Error e) {
				threadLockService.bulkUnlock(locks);
				throw e;
			}
			if (sameParent) {
				return new ParentLockHold(locks, actual);
			}
			threadLockService.bulkUnlock(locks);
			if (attempt >= PARENT_LOCK_RETRIES) {
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException(
						"Object " + objectId + " kept being moved while its parent was being"
								+ " read; the answer would not match any locked state. Retry.");
			}
		}
	}

	/**
	 * Fail if the folder disappeared while its lock was not held.
	 *
	 * <p>{@code getChildren} verifies and compiles the folder under its own lock, releases it, and
	 * only then takes the folder together with its children as one ordered set. That release is
	 * what makes the ordering possible — a lock held outside a set is not ordered against it — but
	 * it opens a window, and a listing built after the folder was deleted in that window would be
	 * an answer about something that no longer exists.
	 */
	private void requireFolderStillPresent(String repositoryId, String folderId) {
		Content folder = contentService.getContent(repositoryId, folderId);
		if (folder == null) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException(
					"Folder " + folderId + " was deleted while its children were being listed");
		}
	}

	/**
	 * The folder's id followed by its children's, as one list to be ordered together.
	 *
	 * <p>The folder is included deliberately. Locking it separately, before or around the set,
	 * leaves its position in the global acquisition order undefined — which is exactly how two
	 * listings of different folders ended up waiting on each other.
	 */
	private static List<String> withFolder(String folderId, List<Content> contents) {
		List<String> ids = new ArrayList<>((contents == null ? 0 : contents.size()) + 1);
		ids.add(folderId);
		if (contents != null) {
			for (Content content : contents) {
				ids.add(content.getId());
			}
		}
		return ids;
	}

	private ObjectInFolderList getChildrenInternal(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments,
			BigInteger maxItems, BigInteger skipCount, boolean folderOnly) {

		// Prepare
		ObjectInFolderListImpl result = new ObjectInFolderListImpl();
		result.setObjects(new ArrayList<ObjectInFolderData>());
		result.setHasMoreItems(false);

		// Threshold for switching to oversampling pagination
		final int FULL_FETCH_THRESHOLD = 500;
		// Default maxItems when not specified by client (prevents overflow with oversampleFactor)
		final int DEFAULT_MAX_ITEMS = 100;

		long totalCount = contentService.getChildrenCount(repositoryId, folderId);
		// intValue() TRUNCATES: 2^31 arrives as a negative number and 2^32 as zero, and both
		// then flow into the paging arithmetic below (dbLimit = _maxItems * oversampleFactor
		// overflows a second time). The change feed learned this live — an unbounded ask
		// became first a CouchDB 400 and then a heap OOM — so the same shape is bounded here:
		// compare before converting, and cap the page. A client asking for more receives a
		// page plus hasMoreItems, which is what CMIS paging is for.
		int _maxItems = (maxItems != null) ? clampToPage(maxItems) : DEFAULT_MAX_ITEMS;
		int _skipCount = (skipCount != null) ? clampSkip(skipCount) : 0;

		// Whether totalCount is an accurate DB-level count (from _count reduce)
		boolean totalCountAccurate = (totalCount > 0);

		// Fallback: if getChildrenCount returns 0, the _count reduce may not be applied yet.
		// Use a lightweight probe to decide whether to use the legacy or pagination path.
		if (totalCount == 0) {
			List<Content> probe = contentService.getChildrenPaged(repositoryId, folderId, 0, FULL_FETCH_THRESHOLD + 1);
			// An empty probe whose rows ALL failed to decode is not an empty folder — it is a
			// folder whose children are invisible, and answering "no children" here hides them
			// from every listing until the rows are repaired, with nothing saying so.
			if (probe.isEmpty() && contentService.lastUnreadableChildCount() > 0) {
				throw new CmisRuntimeException("the folder's children could not be decoded ("
						+ contentService.lastUnreadableChildCount() + " row(s)); this is NOT a "
						+ "finding that the folder is empty");
			}
			if (probe.isEmpty()) {
				// "No children" and "no folder" look identical from here, and this is the branch a
				// deleted folder actually takes: deleteTree removes the children before the folder,
				// so by the time the folder is gone its child count is zero. Without this check a
				// folder deleted in getChildren's unlocked window would be answered with an empty
				// 200 listing instead of the 404 the pre-restructure code gave.
				requireFolderStillPresent(repositoryId, folderId);
				ObjectInFolderListImpl emptyResult = new ObjectInFolderListImpl();
				emptyResult.setObjects(new ArrayList<ObjectInFolderData>());
				emptyResult.setNumItems(BigInteger.ZERO);
				emptyResult.setHasMoreItems(false);
				return emptyResult;
			}
			if (probe.size() <= FULL_FETCH_THRESHOLD) {
				// Small folder: use the probe result as total count
				totalCount = probe.size();
			} else {
				// Large folder with reduce not applied: exact count unknown.
				// Set to Long.MAX_VALUE so the oversampling loop relies on batch.isEmpty() to terminate.
				totalCount = Long.MAX_VALUE;
			}
		}

		if (totalCount <= FULL_FETCH_THRESHOLD || orderBy != null) {
			// Legacy path: fetch all, filter, sort, subList
			// Used for small folders (accurate global sort) or when client specifies explicit orderBy
			List<Content> contents = contentService.getChildren(repositoryId, folderId);

			// The folder itself belongs in this set, not outside it: an ordering is a property of
			// a set, and a lock taken before it is not ordered against anything in it.
			List<Lock> locks = threadLockService.orderedLocks(repositoryId,
					withFolder(folderId, contents), false);

			try {
				threadLockService.bulkLock(locks);

				// The folder's lock was released between its own compile and here, so confirm it
				// still exists before listing what is supposedly inside it. Without this, a folder
				// deleted in that window is listed as though it were still there.
				requireFolderStillPresent(repositoryId, folderId);

				contents = permissionService.getFiltered(callContext, repositoryId, contents);

				// The CLAMPED values, not the raw ones: this branch used to hand the
				// originals straight through, so a maxItems the oversampling branch bounded
				// still truncated here (a live probe returned an empty page with
				// hasMoreItems=true for maxItems=2^32). One notion of the page per method.
				ObjectList ol = compileService.compileObjectDataList(callContext,
						repositoryId, contents, filter,
						includeAllowableActions, includeRelationships, renditionFilter, false,
						BigInteger.valueOf(_maxItems), BigInteger.valueOf(_skipCount),
						folderOnly, orderBy);

				// Build ObjectInFolderList
				for (ObjectData od : ol.getObjects()) {
					ObjectInFolderDataImpl objectInFolder = new ObjectInFolderDataImpl();
					objectInFolder.setObject(od);
					if (includePathSegments) {
						String name = DataUtil.getStringProperty(od.getProperties(),
								PropertyIds.NAME);
						objectInFolder.setPathSegment(name);
					}
					result.getObjects().add(objectInFolder);
				}
				result.setNumItems(ol.getNumItems());
				result.setHasMoreItems(ol.hasMoreItems());

				return result;
			} finally {
				threadLockService.bulkUnlock(locks);
			}
		} else {
			// Oversampling pagination path for large folders
			String userName = callContext.getUsername();
			UserItem userItem = contentService.getUserItemById(repositoryId, userName);
			boolean isAdmin = (userItem != null && Boolean.TRUE.equals(userItem.isAdmin()));
			Set<String> userGroups = isAdmin ? null : contentService.getGroupIdsContainingUser(repositoryId, userName);

			int oversampleFactor = isAdmin ? 1 : 3;
			int dbLimit = _maxItems * oversampleFactor;
			int dbSkip = 0;
			int filteredSkipped = 0;

			List<Content> pageContents = new ArrayList<>();
			long scanned = 0;
			boolean pageFilled = false;

			while (!pageFilled) {
				List<Content> batch = contentService.getChildrenPaged(repositoryId, folderId, dbSkip, dbLimit);
				// Read once per page: skip/limit address RAW view rows, and rows the store
				// cannot decode are consumed on the wire but absent from `batch` — so both
				// "is this the last page?" and "where does the next page start?" need the raw
				// count, not the decoded size.
				int unreadableInBatch = contentService.lastUnreadableChildCount();
				if (batch.isEmpty()) {
					if (unreadableInBatch > 0) {
						// Every row of this page failed to decode. The page is spent; the
						// folder is not — later offsets can still hold readable children,
						// and breaking here made one bad page hide all of them.
						dbSkip += dbLimit;
						continue;
					}
					break;
				}

				int processedInBatch = 0;
				for (Content c : batch) {
					processedInBatch++;
					if (!isAdmin) {
						Acl acl = contentService.calculateAcl(repositoryId, c);
						Boolean allowed = permissionService.checkPermissionWithGivenList(
								callContext, repositoryId,
								PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
								acl, c.getType(), c, userName, userGroups);
						if (!Boolean.TRUE.equals(allowed)) continue;
					}

					if (filteredSkipped < _skipCount) {
						filteredSkipped++;
						continue;
					}

					pageContents.add(c);
					if (pageContents.size() >= _maxItems) {
						pageFilled = true;
						break;
					}
				}

				// Only count actually processed rows (not the full batch on early break)
				scanned += processedInBatch;
				dbSkip += processedInBatch;

				if (batch.size() < dbLimit) {
					if (unreadableInBatch > 0 && !pageFilled) {
						// Not the end: the shortfall is decode loss, not exhaustion. Advance
						// the raw cursor over the rows the wire consumed but decode dropped,
						// or the next iteration re-reads the same broken rows for ever.
						dbSkip += (dbLimit - batch.size());
						continue;
					}
					// A genuinely short page IS the end.
					break;
				}
			}

			// hasMore: true if page was filled AND there are unscanned rows,
			// or if totalCount is accurate and there are unscanned rows.
			boolean hasMore;
			if (pageFilled) {
				// Page is full — check if we've exhausted all rows
				if (totalCountAccurate && scanned >= totalCount) {
					// We know the exact count and have scanned everything: this is the last page
					hasMore = false;
				} else {
					// Unknown total or unscanned rows remain
					hasMore = true;
				}
			} else if (totalCountAccurate) {
				hasMore = scanned < totalCount;
			} else {
				// reduce not applied, page not filled, batch exhausted → no more
				hasMore = false;
			}

			// Same as the legacy branch: the folder is part of the set, not held around it.
			List<Lock> locks = threadLockService.orderedLocks(repositoryId,
					withFolder(folderId, pageContents), false);

			try {
				threadLockService.bulkLock(locks);

				requireFolderStillPresent(repositoryId, folderId);

				// Compile with skipCount=0 (already handled), maxItems=size, orderBy=null
				// Passing null lets SortUtil apply the configured default orderBy within the page.
				// This is a best-effort intra-page sort — globally perfect ordering requires the
				// large-folder performance. Use "NONE" to skip sorting entirely — intra-page
				// sorting would reorder items differently per page, causing boundary instability.
				ObjectList ol = compileService.compileObjectDataList(callContext, repositoryId,
						pageContents, filter, includeAllowableActions, includeRelationships,
						renditionFilter, false,
						BigInteger.valueOf(pageContents.size()), BigInteger.ZERO, folderOnly, "NONE");

				for (ObjectData od : ol.getObjects()) {
					ObjectInFolderDataImpl objectInFolder = new ObjectInFolderDataImpl();
					objectInFolder.setObject(od);
					if (includePathSegments) {
						String name = DataUtil.getStringProperty(od.getProperties(),
								PropertyIds.NAME);
						objectInFolder.setPathSegment(name);
					}
					result.getObjects().add(objectInFolder);
				}

				// numItems: admin gets accurate totalCount; non-admin omits (null) per CMIS spec
				if (isAdmin && totalCountAccurate) {
					result.setNumItems(BigInteger.valueOf(totalCount));
				}
				// Non-admin or inaccurate count: do not call setNumItems — leaves it null (unknown),
				// which is CMIS-compliant and avoids -1 breaking AtomPub client calculations
				result.setHasMoreItems(hasMore);

				return result;
			} finally {
				threadLockService.bulkUnlock(locks);
			}
		}
	}
	
	@Override
	public List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String repositoryId, String folderId,
			BigInteger depth, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegment,
			boolean foldersOnly, Holder<ObjectData> anscestorObjectData, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		
		// The folder's lock covers the folder's OWN validation and compile, and is released before
		// the tree walk — the same narrowing getChildren needed, and for the same reason: held
		// across the walk it sits OUTSIDE every ordered set the walk takes, and a lock outside a
		// set is not ordered against it. The live detector attributed 161 of 162 remaining
		// inversions to this method for exactly that reason. Each level of the walk still locks
		// its folder together with its children (getChildrenInternal takes {folder} ∪ {children}).
		Lock parentLock = threadLockService.getReadLock(repositoryId, folderId);
		ObjectData _folder;
		int d;
		boolean iaa;
		boolean ips;

		try{
			parentLock.lock();
			
			// //////////////////
			// General Exception
			// //////////////////
			Folder folder = contentService.getFolder(repositoryId, folderId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_DESCENDENTS_FOLDER, folder);

			// //////////////////
			// Specific Exception
			// //////////////////
			exceptionService.invalidArgumentFolderId(folder, folderId);
			exceptionService.invalidArgumentDepth(depth);

			// //////////////////
			// Body of the method
			// //////////////////
			// check depth
			// Same truncation trap: 2^32 would arrive as 0, which invalidArgumentDepth above
			// has just rejected as an explicit value — so a client asking for an enormous
			// depth would silently get the "no descendants" answer instead. -1 (unlimited)
			// is preserved as CMIS defines it.
			d = (depth == null ? 2 : clampDepth(depth));

			// set defaults if values not set
			iaa = (includeAllowableActions == null ? false
					: includeAllowableActions.booleanValue());
			ips = (includePathSegment == null ? false : includePathSegment
					.booleanValue());

			// Set ObjectData of the starting folder for ObjectInfo
			_folder = compileService.compileObjectData(
					callContext, repositoryId, folder, filter,
					includeAllowableActions, includeRelationships, renditionFilter, false);
			anscestorObjectData.setValue(_folder);

		}finally{
			parentLock.unlock();
		}

		// get the tree.
		return getDescendantsInternal(callContext, repositoryId, _folder, filter, iaa,
				false, includeRelationships, null, ips, 0, d, foldersOnly);
	}

	private List<ObjectInFolderContainer> getDescendantsInternal(
			CallContext callContext, String repositoryId, ObjectData node,
			String filter, Boolean includeAllowableActions,
			Boolean includeAcl, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments, int level,
			int maxLevels, boolean folderOnly) {

		List<ObjectInFolderContainer> childrenOfFolder = new ArrayList<ObjectInFolderContainer>();
		// Check specified folderId is folder(if not, it's a leaf node)
		if (node.getBaseTypeId() != BaseTypeId.CMIS_FOLDER) {
			return childrenOfFolder;
		}

		String folderId = node.getId();
		if (maxLevels == -1 || level < maxLevels) {
			ObjectInFolderList children = getChildrenInternal(callContext,
					repositoryId, folderId, filter, null,
					includeAllowableActions, includeRelationships, renditionFilter,
					includePathSegments,
					BigInteger.valueOf(Integer.MAX_VALUE), BigInteger.valueOf(0), folderOnly);

			childrenOfFolder = new ArrayList<ObjectInFolderContainer>();
			if (null != children
					&& CollectionUtils.isNotEmpty(children.getObjects())) {
				for (ObjectInFolderData child : children.getObjects()) {
					ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
					List<ObjectInFolderContainer> subChildren = getDescendantsInternal(
							callContext, repositoryId, child.getObject(),
							filter, includeAllowableActions,
							includeAcl, includeRelationships,
							renditionFilter, includePathSegments, level + 1,
							maxLevels, folderOnly);

					oifc.setObject(child);
					if (CollectionUtils.isNotEmpty(subChildren))
						oifc.setChildren(subChildren);
					childrenOfFolder.add(oifc);
				}
			}
		}
		return childrenOfFolder;
	}

	@Override
	public ObjectData getFolderParent(CallContext callContext, String repositoryId,
			String folderId, String filter, ExtensionsData extension) {
		
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		
		// The child and its CURRENT parent are locked as one ordered set. Nesting them — child
		// first, then parent — is what the live detector caught here: another request holding
		// those two stripes the other way round waits on this one and neither ever finishes.
		// Ordering is only a property of a whole set; a lock taken outside the set cannot be
		// ordered against it. The helper retries while a concurrent move changes the parent, so
		// the answer always describes a placement whose locks are actually held.
		ParentLockHold hold = lockObjectAndCurrentParent(repositoryId, folderId);
		try{
			// //////////////////
			// General Exception
			// //////////////////
			Folder folder = (Folder) contentService.getContent(repositoryId, folderId);
			exceptionService.objectNotFound(DomainType.OBJECT, folder, folderId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT, folder);

			// //////////////////
			// Specific Exception
			// //////////////////
			// CMIS 1.1 §2.2.3.3: Root folder has no parent — must reject before lock
			exceptionService.invalidArgumentRootFolder(repositoryId, folder);

			Folder parent = hold.parent();
			exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parent);

			// //////////////////
			// Body of the method
			// //////////////////
			return compileService.compileObjectData(callContext, repositoryId,
					parent, filter, true, IncludeRelationships.NONE, null, true);
		}finally{
			threadLockService.bulkUnlock(hold.locks());
		}
	}

	@Override
	public List<ObjectParentData> getObjectParents(CallContext callContext,
			String repositoryId, String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeRelativePathSegment, ExtensionsData extension) {

		exceptionService.invalidArgumentRequired("objectId", objectId);
		
		// Same shape as getFolderParent: the object and its CURRENT parent as one ordered set,
		// re-previewed while a concurrent move keeps changing which parent that is. Taking the
		// child's lock and then the parent's leaves the order to whichever object each request
		// happens to start from, and the detector caught exactly that pair inverted here.
		ParentLockHold hold = lockObjectAndCurrentParent(repositoryId, objectId);
		List<Lock> locks = hold.locks();
		try{
			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_PARENTS_FOLDER, content);


			Folder parent = hold.parent();
			if (parent == null) {
				// Root folder or orphaned object - no parent exists. The helper verified this
				// under the object's lock, so it is the locked truth, not a stale preview.
				return new ArrayList<ObjectParentData>();
			}

			{

				// //////////////////
				// Specific Exception
				// //////////////////
				exceptionService.objectNotFoundParentFolder(repositoryId, objectId, parent);
				exceptionService.invalidArgumentRootFolder(repositoryId, content);

				// //////////////////
				// Body of the method
				// //////////////////
				ObjectParentDataImpl result = new ObjectParentDataImpl();
				ObjectData o = compileService.compileObjectData(callContext,
						repositoryId, parent, filter, includeAllowableActions,
						includeRelationships, null, true);
				result.setObject(o);
				boolean irps = (includeRelativePathSegment == null ? false
						: includeRelativePathSegment.booleanValue());
				if (irps) {
					result.setRelativePathSegment(content.getName());
				}

				return Collections.singletonList((ObjectParentData) result);
			}
		}finally{
			threadLockService.bulkUnlock(locks);
		}
	}

	@Override
	public ObjectList getCheckedOutDocs(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// NONE

		// //////////////////
		// Specific Exception
		// //////////////////
		//Folder ID can be null, which means all PWCs are returned.
		if(StringUtils.isNotBlank(folderId)){
			Folder folder = contentService.getFolder(repositoryId, folderId);
			exceptionService.objectNotFoundParentFolder(repositoryId, folderId, folder);
		}
		exceptionService.invalidArgumentOrderBy(repositoryId, orderBy);

		// //////////////////
		// Body of the method
		// //////////////////
		//Folder ID can be null, which means all PWCs are returned.
		List<Document> checkedOuts = contentService.getCheckedOutDocs(repositoryId,
				folderId, orderBy, extension);
		
		List<Lock> locks = threadLockService.readLocks(repositoryId, checkedOuts);
		
		try{
			threadLockService.bulkLock(locks);
			
			ObjectList list = compileService.compileObjectDataList(
					callContext, repositoryId, checkedOuts, filter,
					includeAllowableActions, includeRelationships, renditionFilter, false,
					maxItems, skipCount, false, orderBy);

			return list;
			
		}finally{
			threadLockService.bulkUnlock(locks);
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
	/** The largest page this server serves for a children/descendants request. */
	private static final int MAX_PAGE = 10_000;

	/**
	 * Converts a client's maxItems without truncating it into a negative or zero.
	 *
	 * <p>{@code BigInteger.intValue()} keeps only the low 32 bits, so 2^31 becomes
	 * {@code Integer.MIN_VALUE} and 2^32 becomes 0 — a "give me everything" ask silently
	 * turned into "give me a negative page" or "give me nothing", and the oversampling
	 * multiplication below overflowed again on top of it.
	 */
	private static int clampToPage(java.math.BigInteger maxItems) {
		if (maxItems.signum() <= 0) {
			return DEFAULT_PAGE_FOR_NON_POSITIVE;
		}
		return maxItems.compareTo(java.math.BigInteger.valueOf(MAX_PAGE)) >= 0
				? MAX_PAGE
				: maxItems.intValue();
	}

	/** A skip count is a position, never negative — and never a truncated one. */
	private static int clampSkip(java.math.BigInteger skipCount) {
		if (skipCount.signum() <= 0) {
			return 0;
		}
		return skipCount.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) >= 0
				? Integer.MAX_VALUE
				: skipCount.intValue();
	}

	/** A non-positive maxItems is not "no limit"; it gets the ordinary default page. */
	private static final int DEFAULT_PAGE_FOR_NON_POSITIVE = 100;

	/** Converts a client's depth without truncating an enormous value into 0 or a negative. */
	private static int clampDepth(java.math.BigInteger depth) {
		if (depth.signum() < 0) {
			// -1 is CMIS's "unlimited"; invalidArgumentDepth has already rejected < -1.
			return -1;
		}
		return depth.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) >= 0
				? Integer.MAX_VALUE
				: depth.intValue();
	}

}
