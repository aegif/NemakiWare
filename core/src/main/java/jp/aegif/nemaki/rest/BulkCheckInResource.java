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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jp.aegif.nemaki.businesslogic.ContentService;

@Path("/bulkCheckIn/{repositoryId}")
public class BulkCheckInResource extends ResourceBase {
	private static final Log log = LogFactory.getLog(BulkCheckInResource.class);

	private ContentService contentService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}


	@SuppressWarnings("unchecked")
	@POST
	@Path("/execute")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String execute( @PathParam("repositoryId") String repositoryId,
			@FormParam("propertyId[]") List<String> propertyIds, @FormParam("propertyValue[]") List<String> propertyValues,
			@FormParam("objectId[]") List<String> objectIds, @FormParam("changeToken[]") List<String> changeTokens,
			@FormParam("comment") String comment, @FormParam("force") Boolean force,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();


		return result.toJSONString();
	}
}
