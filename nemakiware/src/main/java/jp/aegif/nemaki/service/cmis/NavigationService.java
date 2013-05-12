package jp.aegif.nemaki.service.cmis;

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
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;

public interface NavigationService {

	/**
	 * Gets the list of child objects contained in the specified folder.
	 */
	public abstract ObjectInFolderList getChildren(CallContext callContext,
			String folderId, String filter, Boolean includeAllowableActions,
			Boolean includePathSegments, BigInteger maxItems,
			BigInteger skipCount);

	/**
	 * Gets the set of descendant objects contained in the specified folder or
	 * any of its child folders.
	 */
	public abstract List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String folderId, BigInteger depth,
			String filter, Boolean includeAllowableActions,
			Boolean includePathSegment, boolean foldersOnly);

	/**
	 * Gets the parent folder object for the specified folder object.
	 */
	public abstract ObjectData getFolderParent(CallContext callContext,
			String folderId, String filter);

	/**
	 * Gets the parent folder(s) for the specified non-folder, fileable object.
	 */
	public abstract List<ObjectParentData> getObjectParents(
			CallContext callContext, String objectId, String filter,
			Boolean includeAllowableActions,
			Boolean includeRelativePathSegment);

	public abstract ObjectList getCheckedOutDocs(CallContext callContext,
			String folderId, String filter, String orderBy,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

}
