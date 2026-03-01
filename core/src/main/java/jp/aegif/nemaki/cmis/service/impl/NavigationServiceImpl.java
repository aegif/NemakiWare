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

			return getChildrenInternal(callContext, repositoryId, folderId, filter,
					orderBy, includeAllowableActions, includeRelationships,
					renditionFilter, includePathSegments, maxItems, skipCount, false);
		}finally{
			parentLock.unlock();
		}
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
		int _maxItems = (maxItems != null) ? maxItems.intValue() : DEFAULT_MAX_ITEMS;
		int _skipCount = (skipCount != null) ? skipCount.intValue() : 0;

		// Whether totalCount is an accurate DB-level count (from _count reduce)
		boolean totalCountAccurate = (totalCount > 0);

		// Fallback: if getChildrenCount returns 0, the _count reduce may not be applied yet.
		// Use a lightweight probe to decide whether to use the legacy or pagination path.
		if (totalCount == 0) {
			List<Content> probe = contentService.getChildrenPaged(repositoryId, folderId, 0, FULL_FETCH_THRESHOLD + 1);
			if (probe.isEmpty()) {
				// Folder is genuinely empty
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

			List<Lock> locks = threadLockService.readLocks(repositoryId, contents);

			try {
				threadLockService.bulkLock(locks);

				contents = permissionService.getFiltered(callContext, repositoryId, contents);

				ObjectList ol = compileService.compileObjectDataList(callContext,
						repositoryId, contents, filter,
						includeAllowableActions, includeRelationships, renditionFilter, false,
						maxItems, skipCount, folderOnly, orderBy);

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
				if (batch.isEmpty()) {
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

				// Safety: if batch returned fewer than requested, we've reached the end
				if (batch.size() < dbLimit) {
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

			List<Lock> locks = threadLockService.readLocks(repositoryId, pageContents);

			try {
				threadLockService.bulkLock(locks);

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
		
		Lock parentLock = threadLockService.getReadLock(repositoryId, folderId);
		
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
			int d = (depth == null ? 2 : depth.intValue());

			// set defaults if values not set
			boolean iaa = (includeAllowableActions == null ? false
					: includeAllowableActions.booleanValue());
			boolean ips = (includePathSegment == null ? false : includePathSegment
					.booleanValue());

			// Set ObjectData of the starting folder for ObjectInfo
			ObjectData _folder = compileService.compileObjectData(
					callContext, repositoryId, folder, filter,
					includeAllowableActions, includeRelationships, renditionFilter, false);
			anscestorObjectData.setValue(_folder);

			// get the tree.
			return getDescendantsInternal(callContext, repositoryId, _folder, filter, iaa,
					false, includeRelationships, null, ips, 0, d, foldersOnly);
			
		}finally{
			parentLock.unlock();
		}
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
		
		Lock childLock = threadLockService.getReadLock(repositoryId, folderId);
		
		try{
			childLock.lock();

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

			Folder parent = contentService.getParent(repositoryId, folderId);
			exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parent);
			
			Lock parentLock = threadLockService.getReadLock(repositoryId, parent.getId());
			try{
				parentLock.lock();

				// //////////////////
				// Body of the method
				// //////////////////
				return compileService.compileObjectData(callContext, repositoryId,
						parent, filter, true, IncludeRelationships.NONE, null, true);
				
			}finally{
				parentLock.unlock();
			}
		}finally{
			childLock.unlock();
		}
	}

	@Override
	public List<ObjectParentData> getObjectParents(CallContext callContext,
			String repositoryId, String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeRelativePathSegment, ExtensionsData extension) {

		exceptionService.invalidArgumentRequired("objectId", objectId);
		
		Lock childLock = threadLockService.getReadLock(repositoryId, objectId);
		
		try{
			childLock.lock();
			
			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_PARENTS_FOLDER, content);


			//Get parent
			Folder parent = contentService.getParent(repositoryId, objectId);
			if (parent == null) {
				// Root folder or orphaned object - no parent exists
				return new ArrayList<ObjectParentData>();
			}
			Lock parentLock = threadLockService.getReadLock(repositoryId, parent.getId());

			try{
				parentLock.lock();
				
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
				
			}finally{
				parentLock.unlock();
			}
			
		}finally{
			childLock.unlock();
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
}
