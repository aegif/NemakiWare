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

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface RepositoryService {

	/**
	 * Checks whether this repository has the given identifier or not.
	 */
	public abstract boolean hasThisRepositoryId(String repositoryId);

	/**
	 * Returns information about the CMIS repository, the optional capabilities
	 * it supports and its access control information if applicable.
	 * @param repositoryId TODO
	 */
	public abstract RepositoryInfo getRepositoryInfo(String repositoryId);

	public abstract List<org.apache.chemistry.opencmis.commons.data.RepositoryInfo> getRepositoryInfos();
	
	/**
	 * Gets the CMIS types.
	 * @param repositoryId TODO
	 */
	public abstract TypeDefinitionList getTypeChildren(CallContext callContext,
			String repositoryId, String typeId,
			Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount);

	/**
	 * Returns the set of descendant object type defined for the repository
	 * under the specified type.
	 * @param repositoryId TODO
	 */
	public abstract List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String repositoryId, String typeId,
			BigInteger depth, Boolean includePropertyDefinitions);

	/**
	 * Gets the definition of the specified object type.
	 * @param repositoryId TODO
	 */
	public abstract TypeDefinition getTypeDefinition(CallContext callContext,
			String repositoryId, String typeId);

	/**
	 * Create a type
	 * @param callContext TODO
	 * @param repositoryId TODO
	 * @param type
	 * @param extension
	 * @return
	 */
	public TypeDefinition createType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension);

	/**
	 * Delete a type
	 * @param callContext TODO
	 * @param repositoryId TODO
	 * @param typeId
	 * @param extension
	 */
	public void deleteType(CallContext callContext, String repositoryId, String typeId, ExtensionsData extension);

	/**
	 * Update a type
	 * @param callContext TODO
	 * @param repositoryId TODO
	 * @param type
	 * @param extension
	 * @return
	 */
	public TypeDefinition updateType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension);
}
