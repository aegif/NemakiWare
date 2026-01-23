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
package jp.aegif.nemaki.cmis.aspect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Permission Service interface.
 */
public interface PermissionService {

	/**
	 * permissionDenied Exception check 
	 * @param repositoryId TODO
	 * @param content TODO
	 * @return
	 */
	public Boolean checkPermission(CallContext context, String repositoryId, String key, Acl acl, String baseObjectType, Content content);
	
	public boolean checkPermission(CallContext callContext, Action action, ObjectData objectData);
	
	public boolean checkPermissionAtTopLevel(CallContext context, String repositoryId, String key, Content content);
	
	/**
	 * 
	 * @param callContext
	 * @param repositoryId TODO
	 * @param contents
	 * @return
	 */
	public <T> List<T> getFiltered(CallContext callContext,String repositoryId, List<T>contents);

	Boolean checkPermissionWithGivenList(CallContext callContext, String repositoryId, String key, Acl acl,
			String baseType, Content content, String userName, Set<String> groups);

	/**
	 * Check permissions for multiple contents in a batch operation.
	 * User info and groups are fetched once and reused for all checks.
	 *
	 * @param callContext the call context containing user information
	 * @param repositoryId the repository identifier
	 * @param permissionKey the permission key to check (e.g., CAN_GET_PROPERTIES_OBJECT)
	 * @param acls map of objectId to Acl
	 * @param baseTypes map of objectId to base type string
	 * @param contents map of objectId to Content
	 * @return map of objectId to permission check result (true = allowed)
	 */
	Map<String, Boolean> checkPermissions(CallContext callContext, String repositoryId, String permissionKey,
			Map<String, Acl> acls, Map<String, String> baseTypes, Map<String, Content> contents);
}
