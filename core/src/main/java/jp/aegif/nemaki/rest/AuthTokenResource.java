package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/repo/{repositoryId}/authtoken/")
public class AuthTokenResource extends ResourceBase{

	private static final Logger logger = LoggerFactory.getLogger(AuthTokenResource.class);

	private TokenService tokenService;
	
	@GET
	@Path("/{userName}")
	@Produces(MediaType.APPLICATION_JSON)
	public String get(@PathParam("repositoryId") String repositoryId, @PathParam("userName") String userName, @QueryParam("app") String app){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if(StringUtils.isBlank(app)){
			app = "";
		}
		
		Token token = tokenService.getToken(app, repositoryId, userName);

		if(token == null){
			status = false;
			errMsg = new JSONArray();	//TODO
		}else{
			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);
		}

		result = makeResult(status, result, errMsg);
		return result.toString();
	}
	
	@GET
	@Path("/{userName}/register")
	@Produces(MediaType.APPLICATION_JSON)
	public String register(@PathParam("repositoryId") String repositoryId, @PathParam("userName") String userName, @QueryParam("app") String app){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Validation
		if(StringUtils.isBlank(app)){
			app = "";
		}
		if(StringUtils.isBlank(userName)){
			addErrMsg(errMsg, "username", "isNull");
			return makeResult(status, result, errMsg).toString();
		}
		if(StringUtils.isBlank(repositoryId)){
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(status, result, errMsg).toString();
		}
		
		
		Token token = tokenService.setToken(app, repositoryId, userName);


		JSONObject obj = new JSONObject();
		obj.put("app", app);
		obj.put("repositoryId", repositoryId);
		obj.put("userName", userName);
		obj.put("token", token.getToken());
		obj.put("expiration", token.getExpiration());
		result.put("value", obj);
		result = makeResult(status, result, errMsg);
		return result.toString();
	}
	
	@POST
	@Path("/{userName}/login")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String login(@PathParam("repositoryId") String repositoryId, @PathParam("userName") String userName, 
			@FormParam("password") String password, @QueryParam("app") String app){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if(StringUtils.isBlank(app)){
			app = "";
		}
		if(StringUtils.isBlank(userName)){
			addErrMsg(errMsg, "username", "isNull");
			return makeResult(status, result, errMsg).toString();
		}
		if(StringUtils.isBlank(repositoryId)){
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(status, result, errMsg).toString();
		}
		if(StringUtils.isBlank(password)){
			addErrMsg(errMsg, "password", "isNull");
			return makeResult(status, result, errMsg).toString();
		}
		
		if(!"admin".equals(userName) || !"admin".equals(password)){
			addErrMsg(errMsg, "credentials", "invalid");
			status = false;
			return makeResult(status, result, errMsg).toString();
		}
		
		Token token = tokenService.setToken(app, repositoryId, userName);

		JSONObject obj = new JSONObject();
		obj.put("app", app);
		obj.put("repositoryId", repositoryId);
		obj.put("userName", userName);
		obj.put("token", token.getToken());
		obj.put("expiration", token.getExpiration());
		result.put("value", obj);
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@POST
	@Path("/oidc/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertOIDCToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);
			
			String oidcToken = (String) requestJson.get("oidc_token");
			String idToken = (String) requestJson.get("id_token");
			JSONObject userInfo = (JSONObject) requestJson.get("user_info");
			
			if (StringUtils.isBlank(oidcToken)) {
				addErrMsg(errMsg, "oidc_token", "isNull");
				status = false;
				return makeResult(status, result, errMsg).toString();
			}
			
			if (StringUtils.isBlank(repositoryId)) {
				addErrMsg(errMsg, "repositoryId", "isNull");
				status = false;
				return makeResult(status, result, errMsg).toString();
			}
			
			String userName = extractUsernameFromOIDC(userInfo);
			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "username", "cannotExtract");
				status = false;
				return makeResult(status, result, errMsg).toString();
			}
			
			Token token = tokenService.setToken("oidc", repositoryId, userName);
			
			JSONObject obj = new JSONObject();
			obj.put("app", "oidc");
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);
			
		} catch (Exception e) {
			logger.error("Error converting OIDC token", e);
			addErrMsg(errMsg, "conversion", "failed");
			status = false;
		}
		
		result = makeResult(status, result, errMsg);
		return result.toString();
	}
	
	private String extractUsernameFromOIDC(JSONObject userInfo) {
		if (userInfo == null) return null;
		
		String[] usernameFields = {"preferred_username", "sub", "email", "name"};
		for (String field : usernameFields) {
			String value = (String) userInfo.get(field);
			if (StringUtils.isNotBlank(value)) {
				return value;
			}
		}
		return null;
	}

	public void setTokenService(TokenService tokenService) {
		this.tokenService = tokenService;
	}
}
