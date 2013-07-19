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
import java.util.List;
import jp.aegif.nemaki.repository.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.InitializingBean;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

	private NemakiRepositoryInfoImpl repositoryInfo;
	private ContentService contentService;
	private TypeManager typeManager;
	
	
	public TypeManager getTypeManager() {
		return typeManager;
	}

	public boolean hasThisRepositoryId(String repositoryId) {
		return (repositoryId.equals(repositoryInfo.getId()));
	}

	public NemakiRepositoryInfoImpl getRepositoryInfo() {
		repositoryInfo.setLatestChangeLogToken(contentService.getLatestChangeToken());
		return repositoryInfo;
	}

	//CMIS Service method
	public TypeDefinitionList getTypeChildren(CallContext callContext,
			String typeId, Boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount) {

		return typeManager.getTypesChildren(callContext, typeId,
				includePropertyDefinitions, maxItems, skipCount);
	}
	
	//CMIS Service method
	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String typeId, BigInteger depth,
			Boolean includePropertyDefinitions) {
		return typeManager.getTypesDescendants(typeId, depth, includePropertyDefinitions);
	}

	//CMIS Service method
	public TypeDefinition getTypeDefinition(CallContext callContext,
			String typeId) {
		return typeManager.getTypeDefinition(typeId);
	}

	/**
	 * Sets CMIS optional capabilities for Nemaki repository.
	 */
	public void afterPropertiesSet() throws Exception {
	}

	public void setRepositoryInfo(NemakiRepositoryInfoImpl repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
}
