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

public interface NavigationService {

	/**
	 * Gets the list of child objects contained in the specified folder.
	 * @param orderBy TODO
	 * @param includeRelationships TODO
	 * @param renditionFilter TODO
	 * @param extension TODO
	 * @param parentObjectDa TODO
	 */
	public abstract ObjectInFolderList getChildren(CallContext callContext,
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension, Holder<ObjectData> parentObjectDa);

	/**
	 * Gets the set of descendant objects contained in the specified folder or
	 * any of its child folders.
	 * @param includeRelationships TODO
	 * @param renditionFilter TODO
	 * @param extension TODO
	 * @param anscestorObjectData TODO
	 */
	public abstract List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String folderId, BigInteger depth,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter, Boolean includePathSegment, boolean foldersOnly, ExtensionsData extension, Holder<ObjectData> anscestorObjectData);

	/**
	 * Gets the parent folder object for the specified folder object.
	 */
	public abstract ObjectData getFolderParent(CallContext callContext,
			String folderId, String filter);

	/**
	 * Gets the parent folder(s) for the specified non-folder, fileable object.
	 * @param includeRelationships TODO
	 * @param renditionFilter TODO
	 * @param extension TODO
	 */
	public abstract List<ObjectParentData> getObjectParents(
			CallContext callContext, String objectId, String filter,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter, Boolean includeRelativePathSegment, ExtensionsData extension);

	public abstract ObjectList getCheckedOutDocs(CallContext callContext,
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

}
