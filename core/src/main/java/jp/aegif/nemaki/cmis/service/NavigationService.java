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
package jp.aegif.nemaki.cmis.service;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

public interface NavigationService {

	/**
	 * Gets the list of child objects contained in the specified folder.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param orderBy
	 *            TODO
	 * @param includeRelationships
	 *            TODO
	 * @param renditionFilter
	 *            TODO
	 * @param parentObjectData
	 *            TODO
	 * @param extension
	 *            TODO
	 */
	public abstract ObjectInFolderList getChildren(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("folderId") String folderId,
			@LogParam("filter") String filter, @LogParam("orderBy") String orderBy,
			@LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships,
			@LogParam("renditionFilter") String renditionFilter,
			@LogParam("includePathSegments") Boolean includePathSegments, @LogParam("maxItems") BigInteger maxItems,
			@LogParam("skipCount") BigInteger skipCount,
			@LogParam("parentObjectData") Holder<ObjectData> parentObjectData,
			@LogParam("extension") ExtensionsData extension);

	/**
	 * Gets the set of descendant objects contained in the specified folder or
	 * any of its child folders.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param includeRelationships
	 *            TODO
	 * @param renditionFilter
	 *            TODO
	 * @param extension
	 *            TODO
	 * @param anscestorObjectData
	 *            TODO
	 */
	public abstract List<ObjectInFolderContainer> getDescendants(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("folderId") String folderId, @LogParam("depth") BigInteger depth,
			@LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships, @LogParam("renditionFilter") String renditionFilter,
			@LogParam("includePathSegment") Boolean includePathSegment, @LogParam("foldersOnly") boolean foldersOnly,
			@LogParam("anscestorObjectData") Holder<ObjectData> anscestorObjectData, @LogParam("extension") ExtensionsData extension);

	/**
	 * Gets the parent folder object for the specified folder object.
	 * 
	 * @param repositoryId
	 *            TODO
	 */
	public abstract ObjectData getFolderParent(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("folderId") String folderId,
			@LogParam("filter") String filter);

	/**
	 * Gets the parent folder(s) for the specified non-folder, fileable object.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param includeRelationships
	 *            TODO
	 * @param renditionFilter
	 *            TODO
	 * @param extension
	 *            TODO
	 */
	public abstract List<ObjectParentData> getObjectParents(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("objectId") String objectId,
			@LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships,
			@LogParam("renditionFilter") String renditionFilter,
			@LogParam("includeRelativePathSegment") Boolean includeRelativePathSegment,
			@LogParam("extension") ExtensionsData extension);

	public abstract ObjectList getCheckedOutDocs(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("folderId") String folderId,
			@LogParam("filter") String filter, @LogParam("orderBy") String orderBy,
			@LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships,
			@LogParam("renditionFilter") String renditionFilter, @LogParam("maxItems") BigInteger maxItems,
			@LogParam("skipCount") BigInteger skipCount, @LogParam("extension") ExtensionsData extension);

}
