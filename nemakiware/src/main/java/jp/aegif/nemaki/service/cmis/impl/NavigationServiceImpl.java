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
package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.NavigationService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.SortUtil;

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

public class NavigationServiceImpl implements NavigationService {

	private ContentService contentService;
	private PermissionService permissionService;
	private ExceptionService exceptionService;
	private CompileObjectService compileObjectService;
	private SortUtil sortUtil;

	@Override
	public ObjectInFolderList getChildren(CallContext callContext,
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegments, BigInteger maxItems,
			BigInteger skipCount, ExtensionsData extension,
			Holder<ObjectData> parentObjectData) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		Folder folder = contentService.getFolder(folderId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_CHILDREN_FOLDER, folder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.invalidArgumentFolderId(folder, folderId);

		// //////////////////
		// Body of the method
		// //////////////////
		// Set ObjectData of parent folder for ObjectInfo
		ObjectData _parent = compileObjectService.compileObjectData(
				callContext, folder, filter, includeAllowableActions,
				includeRelationships, renditionFilter, false, null);
		parentObjectData.setValue(_parent);

		return getChildrenInternal(callContext, folderId, filter, orderBy,
				includeAllowableActions, includeRelationships, renditionFilter,
				includePathSegments, maxItems, skipCount, false);
	}

	private ObjectInFolderList getChildrenInternal(CallContext callContext,
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegments, BigInteger maxItems,
			BigInteger skipCount, boolean folderOnly) {

		// Prepare
		ObjectInFolderListImpl result = new ObjectInFolderListImpl();
		result.setObjects(new ArrayList<ObjectInFolderData>());
		result.setHasMoreItems(false);

		// Build ObjectList
		List<Content> contents = contentService.getChildren(folderId);

		ObjectList ol = compileObjectService.compileObjectDataList(callContext,
				contents, filter, includeAllowableActions,
				includeRelationships, renditionFilter, false, maxItems,
				skipCount, folderOnly, null);

		// Sort
		sortUtil.sort(ol.getObjects(), orderBy);

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

	@Override
	public List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String folderId, BigInteger depth,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegment, boolean foldersOnly,
			ExtensionsData extension, Holder<ObjectData> anscestorObjectData) {

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		Folder folder = contentService.getFolder(folderId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_DESCENDENTS_FOLDER, folder);

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
		ObjectData _folder = compileObjectService.compileObjectData(
				callContext, folder, filter, includeAllowableActions,
				includeRelationships, renditionFilter, false, null);
		anscestorObjectData.setValue(_folder);

		// get the tree.
		return getDescendantsInternal(callContext, _folder, filter, iaa, false,
				includeRelationships, null, ips, 0, d, foldersOnly);
	}

	private List<ObjectInFolderContainer> getDescendantsInternal(
			CallContext callContext, ObjectData node, String filter,
			Boolean includeAllowableActions, Boolean includeAcl,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegments, int level, int maxLevels,
			boolean folderOnly) {

		List<ObjectInFolderContainer> childrenOfFolder = new ArrayList<ObjectInFolderContainer>();
		// Check specified folderId is folder(if not, it's a leaf node)
		if (node.getBaseTypeId() != BaseTypeId.CMIS_FOLDER) {
			return childrenOfFolder;
		}

		String folderId = node.getId();
		if (maxLevels == -1 || level < maxLevels) {
			ObjectInFolderList children = getChildrenInternal(callContext,
					folderId, filter, null, includeAllowableActions,
					includeRelationships, renditionFilter, includePathSegments,
					BigInteger.valueOf(Integer.MAX_VALUE),
					BigInteger.valueOf(0), folderOnly);

			childrenOfFolder = new ArrayList<ObjectInFolderContainer>();
			if (null != children
					&& CollectionUtils.isNotEmpty(children.getObjects())) {
				for (ObjectInFolderData child : children.getObjects()) {
					ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
					List<ObjectInFolderContainer> subChildren = getDescendantsInternal(
							callContext, child.getObject(), filter,
							includeAllowableActions, includeAcl,
							includeRelationships, renditionFilter,
							includePathSegments, level + 1, maxLevels,
							folderOnly);

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
	public ObjectData getFolderParent(CallContext callContext, String folderId,
			String filter) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("folderId", folderId);
		Folder folder = (Folder) contentService.getContent(folderId);
		exceptionService.objectNotFound(DomainType.OBJECT, folder, folderId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT, folder);

		// //////////////////
		// Specific Exception
		// //////////////////
		Folder parent = contentService.getParent(folderId);
		exceptionService.objectNotFoundParentFolder(folderId, parent);
		exceptionService.invalidArgumentRootFolder(folder);

		// //////////////////
		// Body of the method
		// //////////////////
		return compileObjectService.compileObjectData(callContext, parent,
				filter, true, IncludeRelationships.NONE, null, true, null);
	}

	@Override
	public List<ObjectParentData> getObjectParents(CallContext callContext,
			String objectId, String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includeRelativePathSegment, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PARENTS_FOLDER, content);

		// //////////////////
		// Specific Exception
		// //////////////////
		Folder parent = contentService.getParent(objectId);
		exceptionService.objectNotFoundParentFolder(objectId, parent);
		exceptionService.invalidArgumentRootFolder(content);

		// //////////////////
		// Body of the method
		// //////////////////
		ObjectParentDataImpl result = new ObjectParentDataImpl();
		ObjectData o = compileObjectService.compileObjectData(callContext,
				parent, filter, includeAllowableActions, includeRelationships,
				null, true, null);
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
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// NONE

		// //////////////////
		// Specific Exception
		// //////////////////
		Folder folder = contentService.getFolder(folderId);
		exceptionService.objectNotFoundParentFolder(folderId, folder);
		exceptionService.invalidArgumentOrderBy(orderBy);

		// //////////////////
		// Body of the method
		// //////////////////
		List<Document> checkedOuts = contentService.getCheckedOutDocs(folderId,
				orderBy, extension);

		ObjectList list = compileObjectService.compileObjectDataList(
				callContext, checkedOuts, filter, includeAllowableActions,
				includeRelationships, renditionFilter, false, maxItems,
				skipCount, false, null);
		sortUtil.sort(list.getObjects(), orderBy);

		return list;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setCompileObjectService(
			CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public void setSortUtil(SortUtil sortUtil) {
		this.sortUtil = sortUtil;
	}
}
