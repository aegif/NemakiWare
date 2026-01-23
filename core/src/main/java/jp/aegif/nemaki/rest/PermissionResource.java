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
package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@Path("/repo/{repositoryId}/node/{objectId}/acl")
public class PermissionResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(PermissionResource.class);

	private ContentService contentService;
	private AclService aclService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setAclService(AclService aclService) {
		this.aclService = aclService;
	}

	private ContentService getContentService() {
		if (contentService != null) {
			return contentService;
		}
		// Fallback to manual Spring context lookup
		try {
			ContentService service = SpringContext.getApplicationContext()
					.getBean("ContentService", ContentService.class);
			if (service != null) {
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get ContentService from SpringContext: " + e.getMessage(), e);
		}
		
		log.error("ContentService is null and SpringContext fallback failed");
		return null;
	}

	private AclService getAclService() {
		if (aclService != null) {
			return aclService;
		}
		// Fallback to manual Spring context lookup
		try {
			AclService service = SpringContext.getApplicationContext()
					.getBean("AclService", AclService.class);
			if (service != null) {
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get AclService from SpringContext: " + e.getMessage(), e);
		}
		
		log.error("AclService is null and SpringContext fallback failed");
		return null;
	}

	private ContentService getContentServiceSafe() {
		ContentService service = getContentService();
		if (service == null) {
			throw new RuntimeException("ContentService not available - dependency injection failed");
		}
		return service;
	}

	private AclService getAclServiceSafe() {
		AclService service = getAclService();
		if (service == null) {
			throw new RuntimeException("AclService not available - dependency injection failed");
		}
		return service;
	}

	@SuppressWarnings("unchecked")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public String getACL(@PathParam("repositoryId") String repositoryId, @PathParam("objectId") String objectId) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			Content content = getContentServiceSafe().getContent(repositoryId, objectId);
			if (content == null) {
				status = false;
				addErrMsg(errMsg, "object", ErrorCode.ERR_NOTFOUND);
			} else {
				boolean aclInherited = getContentServiceSafe().getAclInheritedWithDefault(repositoryId, content);

				// Use calculateAcl to get both local and inherited ACLs
				jp.aegif.nemaki.model.Acl acl = getContentServiceSafe().calculateAcl(repositoryId, content);
				JSONObject aclJson;
				if (acl != null) {
					aclJson = convertAclToJson(acl);
				} else {
					aclJson = new JSONObject();
					aclJson.put("permissions", new JSONArray());
				}
				
				aclJson.put("aclInherited", aclInherited);
				result.put("acl", aclJson);
			}
		} catch (Exception e) {
			log.error("Error getting ACL for object " + objectId + ": " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "acl", "Failed to get ACL");
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings("unchecked")
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String setACL(@PathParam("repositoryId") String repositoryId, @PathParam("objectId") String objectId,
			String jsonInput, @Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			// Parse JSON input
			JSONParser parser = new JSONParser();
			JSONObject inputJson = (JSONObject) parser.parse(jsonInput);
			
			Boolean breakInheritance = (Boolean) inputJson.get("breakInheritance");
			if (breakInheritance == null) {
				breakInheritance = false;
			}
			
			String aclPropagationStr = (String) inputJson.get("aclPropagation");
			AclPropagation aclPropagation = AclPropagation.REPOSITORYDETERMINED;
			if (aclPropagationStr != null) {
				try {
					aclPropagation = AclPropagation.valueOf(aclPropagationStr);
				} catch (IllegalArgumentException e) {
					log.warn("Invalid aclPropagation value: " + aclPropagationStr + ", using REPOSITORYDETERMINED");
				}
			}
			
			if (breakInheritance) {
				aclPropagation = AclPropagation.OBJECTONLY;
			}
			
			// Convert JSON to CMIS ACL
			org.apache.chemistry.opencmis.commons.data.Acl cmisAcl = convertJsonToCmisAcl(inputJson, breakInheritance);
			
			// Apply ACL to the object using AclService
			getAclServiceSafe().applyAcl(new SystemCallContext(repositoryId), repositoryId, objectId, cmisAcl, aclPropagation);
			
			result.put("status", "success");
		} catch (ParseException e) {
			log.error("JSON parsing error: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "json", "Invalid JSON format");
		} catch (Exception e) {
			log.error("Error setting ACL for object " + objectId + ": " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "acl", "Failed to set ACL");
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings("unchecked")
	private JSONObject convertAclToJson(jp.aegif.nemaki.model.Acl acl) {
		JSONObject aclJson = new JSONObject();
		JSONArray permissions = new JSONArray();

		if (acl != null) {
			// Add local (direct) ACEs
			if (acl.getLocalAces() != null) {
				for (jp.aegif.nemaki.model.Ace ace : acl.getLocalAces()) {
					JSONObject permission = new JSONObject();
					permission.put("principalId", ace.getPrincipalId());

					JSONArray perms = new JSONArray();
					if (ace.getPermissions() != null) {
						perms.addAll(ace.getPermissions());
					}
					permission.put("permissions", perms);
					permission.put("direct", true);  // Local ACEs are direct

					permissions.add(permission);
				}
			}

			// Add inherited ACEs
			if (acl.getInheritedAces() != null) {
				for (jp.aegif.nemaki.model.Ace ace : acl.getInheritedAces()) {
					JSONObject permission = new JSONObject();
					permission.put("principalId", ace.getPrincipalId());

					JSONArray perms = new JSONArray();
					if (ace.getPermissions() != null) {
						perms.addAll(ace.getPermissions());
					}
					permission.put("permissions", perms);
					permission.put("direct", false);  // Inherited ACEs are not direct

					permissions.add(permission);
				}
			}
		}

		aclJson.put("permissions", permissions);
		return aclJson;
	}

	@SuppressWarnings("unchecked")
	private org.apache.chemistry.opencmis.commons.data.Acl convertJsonToCmisAcl(JSONObject inputJson, boolean breakInheritance) {
		List<Ace> aces = new ArrayList<>();

		JSONObject aclJson = (JSONObject) inputJson.get("acl");
		if (aclJson == null) {
			aclJson = inputJson;
		}

		JSONArray permissions = (JSONArray) aclJson.get("permissions");
		if (permissions != null) {
			for (Object permObj : permissions) {
				JSONObject permission = (JSONObject) permObj;
				
				String principalId = (String) permission.get("principalId");
				JSONArray perms = (JSONArray) permission.get("permissions");
				Boolean direct = (Boolean) permission.get("direct");
				
				if (principalId != null && perms != null) {
					List<String> permissionList = new ArrayList<>();
					for (Object perm : perms) {
						permissionList.add((String) perm);
					}
					
					AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(principalId);
					AccessControlEntryImpl ace = new AccessControlEntryImpl(principal, permissionList);
					ace.setDirect(direct != null ? direct : true);
					
					aces.add(ace);
				}
			}
		}

		AccessControlListImpl aclImpl = new AccessControlListImpl(aces);
		
		if (breakInheritance) {
			List<CmisExtensionElement> extensions = new ArrayList<>();
			CmisExtensionElementImpl inheritedExt = new CmisExtensionElementImpl(
				null, "inherited", null, "false");
			extensions.add(inheritedExt);
			aclImpl.setExtensions(extensions);
		}

		return aclImpl;
	}
}
