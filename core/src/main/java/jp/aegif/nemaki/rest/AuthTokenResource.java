package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
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
	
	public void setTokenService(TokenService tokenService) {
		this.tokenService = tokenService;
	}
}
