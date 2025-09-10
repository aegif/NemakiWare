package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;

@Path("/repo/{repositoryId}/authtoken/")
public class AuthTokenResource extends ResourceBase{

	private static final Logger logger = LoggerFactory.getLogger(AuthTokenResource.class);

	private TokenService tokenService;
	
	@Context 
	private HttpServletRequest request;
	
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
		
		TokenService tokenService = getTokenService();
		if (tokenService == null) {
			status = false;
			errMsg.add("TokenService not available");
			result = makeResult(status, result, errMsg);
			return result.toString();
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
		
		
		TokenService tokenService = getTokenService();
		if (tokenService == null) {
			status = false;
			addErrMsg(errMsg, "tokenService", "notAvailable");
			return makeResult(false, result, errMsg).toString();
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
	@Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_PLAIN})
	public String login(@PathParam("repositoryId") String repositoryId, 
	                   @PathParam("userName") String userName,
	                   String requestBody){
		boolean status = false; // Default to failed
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== AuthTokenResource.login() called for user: {} in repository: {} ===", 
		           userName, repositoryId);

		//Validation
		if(StringUtils.isBlank(userName)){
			addErrMsg(errMsg, "username", "isNull");
			return makeResult(false, result, errMsg).toString();
		}
		if(StringUtils.isBlank(repositoryId)){
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}
		
		// SECURITY FIX: Basic Authentication validation is required
		// This endpoint should only be accessible with valid Basic auth credentials
		// The AuthenticationFilter should handle the actual authentication
		// If we reach here, authentication was successful via HTTP Basic Auth
		
		try {
			TokenService tokenService = getTokenService();
			if (tokenService == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				result = makeResult(false, result, errMsg);
				return result.toString();
			}
			
			// Only generate token after successful authentication
			String app = ""; // Default app for React UI
			Token token = tokenService.setToken(app, repositoryId, userName);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);
			
			status = true; // Only set to true after successful token generation
			logger.info("=== Login successful for user: {} ===", userName);
			
		} catch (Exception e) {
			logger.error("Login failed for user: " + userName, e);
			addErrMsg(errMsg, "login", "failed");
		}

		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	public void setTokenService(TokenService tokenService) {
		this.tokenService = tokenService;
	}
	
	/**
	 * Get TokenService from Spring context if not injected via setter
	 * This is a fallback mechanism for Jersey-Spring integration issues
	 */
	private TokenService getTokenService() {
		if (tokenService != null) {
			return tokenService;
		}
		
		try {
			// Fallback: Get TokenService from Spring WebApplicationContext
			WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(
				request.getServletContext());
			if (context != null) {
				tokenService = context.getBean("TokenService", TokenService.class);
				logger.info("TokenService retrieved from Spring context via fallback mechanism");
				return tokenService;
			}
		} catch (Exception e) {
			logger.error("Failed to retrieve TokenService from Spring context", e);
		}
		
		logger.error("TokenService is not available - neither via injection nor Spring context lookup");
		return null;
	}
}
