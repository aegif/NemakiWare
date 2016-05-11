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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.DomainType;

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
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;

public class NavigationServiceImpl implements NavigationService {

	private ContentService contentService;
	private ExceptionService exceptionService;
	private CompileService compileService;
	private PermissionService permissionService;

	@Override
	public ObjectInFolderList getChildren(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments,
			BigInteger maxItems, BigInteger skipCount,
			ExtensionsData extension, Holder<ObjectData> parentObjectData) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		Folder folder = contentService.getFolder(repositoryId, folderId);
		exceptionService.invalidArgumentFolderId(folder, folderId);
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

		// Build ObjectList
		List<Content> contents = contentService.getChildren(repositoryId, folderId);

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
	}
	
	/*public ObjectList pageingObjectDataList(ObjectList objectList,BigInteger maxItems,
			BigInteger skipCount) {
		// Convert skip and max to integer
		int skip = (skipCount == null ? 0 : skipCount.intValue());
		if (skip < 0) {
			skip = 0;
		}
		int max = (maxItems == null ? Integer.MAX_VALUE : maxItems.intValue());
		if (max < 0) {
			max = Integer.MAX_VALUE;
		}
		int end = (skip + max <= objectList.getObjects().size()) ? skip + max : objectList.getObjects().size();
		
		List<ObjectData> list = objectList.getObjects();
		ObjectListImpl impl = new ObjectListImpl();
		impl.setObjects(new ArrayList<ObjectData>(list.subList(skip, end)));
		impl.setNumItems(BigInteger.valueOf(end - skip));
		impl.setHasMoreItems(skip + max > end);
		
		return impl;
	}*/

	@Override
	public List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String repositoryId, String folderId,
			BigInteger depth, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegment,
			boolean foldersOnly, ExtensionsData extension, Holder<ObjectData> anscestorObjectData) {

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
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
			String folderId, String filter) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		Folder folder = (Folder) contentService.getContent(repositoryId, folderId);
		exceptionService.objectNotFound(DomainType.OBJECT, folder, folderId);
		exceptionService.permissionDenied(callContext,
				repositoryId, PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT, folder);

		// //////////////////
		// Specific Exception
		// //////////////////
		Folder parent = contentService.getParent(repositoryId, folderId);
		exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parent);
		exceptionService.invalidArgumentRootFolder(repositoryId, folder);

		// //////////////////
		// Body of the method
		// //////////////////
		return compileService.compileObjectData(callContext, repositoryId,
				parent, filter, true, IncludeRelationships.NONE, null, true);
	}

	@Override
	public List<ObjectParentData> getObjectParents(CallContext callContext,
			String repositoryId, String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeRelativePathSegment, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(repositoryId, objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				repositoryId, PermissionMapping.CAN_GET_PARENTS_FOLDER, content);

		// //////////////////
		// Specific Exception
		// //////////////////
		Folder parent = contentService.getParent(repositoryId, objectId);
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

		ObjectList list = compileService.compileObjectDataList(
				callContext, repositoryId, checkedOuts, filter,
				includeAllowableActions, includeRelationships, renditionFilter, false,
				maxItems, skipCount, false, orderBy);

		return list;
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
}
