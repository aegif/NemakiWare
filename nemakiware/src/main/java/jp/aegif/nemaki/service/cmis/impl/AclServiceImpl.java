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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.service.cmis.impl;

import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.AclService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;

/**
 * Discovery Service implementation for CouchDB.
 * 
 */
public class AclServiceImpl implements AclService {

	private ContentService contentService;
	private ExceptionService exceptionService;
	private TypeManager typeManager;

	public Acl getAcl(CallContext callContext, String objectId,
			Boolean onlyBasicPermissions) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,PermissionMapping.CAN_GET_ACL_OBJECT, content);
		
		// //////////////////
		// Body of the method
		// //////////////////		
		return contentService.convertToCmisAcl(content, onlyBasicPermissions);
	}

	public Acl applyAcl(CallContext callContext, String objectId, Acl acl,
			AclPropagation aclPropagation) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,PermissionMapping.CAN_APPLY_ACL_OBJECT, content);
		
		// //////////////////
		// Specific Exception
		// //////////////////
		TypeDefinition td = typeManager.getTypeDefinition(content);
		if(!td.isControllableAcl()) exceptionService.constraint(objectId, "applyAcl cannot be performed on the object whose controllableAcl = false");
		exceptionService.constraintAclPropagationDoesNotMatch(aclPropagation);
		exceptionService.constraintPermissionDefined(acl, objectId);
		
		// //////////////////
		// Body of the method
		// //////////////////
		//Check ACL inheritance
		boolean inherited = true;	//Inheritance defaults to true if nothing input
		List<CmisExtensionElement> exts = acl.getExtensions();
		if(!CollectionUtils.isEmpty(exts)){
			for(CmisExtensionElement ext : exts){
				if(ext.getName().equals("inherited")){
					inherited = Boolean.valueOf(ext.getValue());
				}
			}
			if(!content.isAclInherited().equals(inherited)) content.setAclInherited(inherited);
		}
		
		jp.aegif.nemaki.model.Acl nemakiAcl = new jp.aegif.nemaki.model.Acl();
		//REPOSUTORYDETERMINED or PROPAGATE is considered as PROPAGATE
		boolean objectOnly = (aclPropagation == AclPropagation.OBJECTONLY)? true : false;
		for(Ace ace : acl.getAces()){
			if(ace.isDirect()){
				jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(ace.getPrincipalId(), ace.getPermissions(), objectOnly);
				nemakiAcl.getLocalAces().add(nemakiAce);
			}
		}
		
		content.setAcl(nemakiAcl);
		contentService.update(content);
		
		return getAcl(callContext, objectId, false);
	}
	
	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}	
}
