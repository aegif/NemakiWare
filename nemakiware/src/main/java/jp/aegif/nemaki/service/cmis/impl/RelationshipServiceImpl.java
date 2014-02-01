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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.RelationshipService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class RelationshipServiceImpl implements RelationshipService{
	private TypeManager typeManager;
	private ContentService contentService;
	private CompileObjectService compileObjectService;
	private ExceptionService exceptionService;
	
	@Override
	public ObjectList getObjectRelationships(CallContext callContext, String objectId,
			Boolean includeSubRelationshipTypes, RelationshipDirection relationshipDirection,
			String typeId, String filter,
			Boolean includeAllowableActions, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, content);
		
		// //////////////////
		// Body of the method
		// //////////////////
		//Set default
		relationshipDirection = (relationshipDirection == null) ? RelationshipDirection.SOURCE : relationshipDirection;
		
		List<Relationship> rels = contentService.getRelationsipsOfObject(objectId, relationshipDirection);
		
		//Filtering results
		List<Relationship> extracted = new ArrayList<Relationship>(); 
		if(typeId != null){
			Set<String> typeIds = new HashSet<String>();
			typeIds.add(typeId);
			
			if(includeSubRelationshipTypes){
				List<TypeDefinitionContainer> descendants = typeManager.getTypesDescendants(typeId, BigInteger.valueOf(-1), false);
				for(TypeDefinitionContainer tdc : descendants){
					typeIds.add(tdc.getTypeDefinition().getId());
				}
			}
			
			for(Relationship rel : rels){
				if(typeIds.contains(rel.getId())){
					extracted.add(rel);
				}
			}
		}else{
			extracted = rels;
		}
		
		//Compile to ObjectData
		return compileObjectService.compileObjectDataList(callContext, extracted, filter, includeAllowableActions, false, maxItems, skipCount);
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public CompileObjectService getCompileObjectService() {
		return compileObjectService;
	}

	public void setCompileObjectService(CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}
	
}
