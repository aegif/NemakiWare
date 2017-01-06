package jp.aegif.nemaki.rest;

import java.util.List;


import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.cmis.service.RelationshipService;

@Path("/repo/{repositoryId}/bulkCheckIn")
public class BulkCheckInResource extends ResourceBase {
	private static final Log log = LogFactory.getLog(BulkCheckInResource.class);

	private ContentService contentService;
	private VersioningService versioningService;
	private RelationshipService relationshipService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
	public void setVersioningService(VersioningService versioningService) {
		this.versioningService = versioningService;
	}
	public void setRelationshipService(RelationshipService relationshipService) {
		this.relationshipService = relationshipService;
	}


	@SuppressWarnings("unchecked")
	@POST
	@Path("/execute")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)

	public String execute(MultivaluedMap<String,String> form){
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		String repositoryId = form.get("repositoryId").get(0);
		String comment = form.get("comment").get(0);
		Boolean force = form.get("force").get(0).equals("true");
		List<String> propertyIds = form.get("propertyId");
		List<String> propertyValues = form.get("propertyValue");
		List<String> objectIds = form.get("objectId");
		List<String> changeTokens = form.get("changeToken");

		return result.toJSONString();
	}
}
