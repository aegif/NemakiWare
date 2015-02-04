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

import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileObjectService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.PolicyService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.util.cache.NemakiCache;
import jp.aegif.nemaki.util.constant.DomainType;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;

public class PolicyServiceImpl implements PolicyService {

	private ContentService contentService;
	private CompileObjectService compileObjectService;
	private ExceptionService exceptionService;
	private TypeManager typeManager;
	private NemakiCache nemakiCache;

	@Override
	public void applyPolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		exceptionService.invalidArgumentRequiredString("policyId", policyId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_ADD_POLICY_OBJECT, content);
		Policy policy = contentService.getPolicy(policyId);
		exceptionService.objectNotFound(DomainType.OBJECT, policy, policyId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_ADD_POLICY_POLICY, policy);

		// //////////////////
		// Specific Exception
		// //////////////////
		TypeDefinition td = typeManager.getTypeDefinition(content);
		if (!td.isControllablePolicy())
			exceptionService
					.constraint(objectId,
							"appyPolicy cannot be performed on the object whose controllablePolicy = false");

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.applyPolicy(callContext, policyId, objectId, extension);
		
		nemakiCache.removeCmisCache(objectId);
	}

	@Override
	public void removePolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		exceptionService.invalidArgumentRequiredString("policyId", policyId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_REMOVE_POLICY_OBJECT, content);
		Policy policy = contentService.getPolicy(policyId);
		exceptionService.objectNotFound(DomainType.OBJECT, policy, policyId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_REMOVE_POLICY_POLICY, policy);

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.removePolicy(callContext, policyId, objectId, extension);
	
		nemakiCache.removeCmisCache(objectId);
	}

	@Override
	public List<ObjectData> getAppliedPolicies(CallContext callContext,
			String objectId, String filter, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		List<Policy> policies = contentService.getAppliedPolicies(objectId,
				extension);
		List<ObjectData> objects = new ArrayList<ObjectData>();
		if (!CollectionUtils.isEmpty(policies)) {
			for (Policy policy : policies) {
				objects.add(compileObjectService.compileObjectData(callContext,
						policy, filter, true, IncludeRelationships.NONE, null,
						true));
			}
		}
		return objects;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setCompileObjectService(
			CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public ExceptionService getExceptionService() {
		return exceptionService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setNemakiCache(NemakiCache nemakiCache) {
		this.nemakiCache = nemakiCache;
	}
}
