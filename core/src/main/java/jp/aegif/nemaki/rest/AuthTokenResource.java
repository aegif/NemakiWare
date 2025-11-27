package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.UserItem;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
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

import java.util.Base64;
import java.util.Map;

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

	/**
	 * Convert OIDC token to NemakiWare authentication token.
	 * Called by React UI after successful OIDC authentication.
	 *
	 * Expected request body:
	 * {
	 *   "oidc_token": "access_token_string",
	 *   "id_token": "id_token_string",
	 *   "user_info": {
	 *     "sub": "user_id",
	 *     "email": "user@example.com",
	 *     "name": "User Name",
	 *     ...
	 *   }
	 * }
	 */
	@POST
	@Path("/oidc/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertOIDCToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== AuthTokenResource.convertOIDCToken() called for repository: {} ===", repositoryId);

		try {
			// Parse request body
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);

			// Extract user information from OIDC response
			JSONObject userInfo = (JSONObject) requestJson.get("user_info");
			String oidcToken = (String) requestJson.get("oidc_token");
			String idToken = (String) requestJson.get("id_token");

			if (userInfo == null) {
				addErrMsg(errMsg, "user_info", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			// Extract username - try multiple fields from OIDC user info
			String userName = extractUsernameFromOIDC(userInfo);

			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.info("OIDC user identified: {}", userName);

			// Get TokenService
			TokenService tokenService = getTokenService();
			if (tokenService == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}

			// Generate NemakiWare token for the OIDC user
			String app = "oidc";
			Token token = tokenService.setToken(app, repositoryId, userName);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);

			status = true;
			logger.info("=== OIDC token conversion successful for user: {} ===", userName);

		} catch (ParseException e) {
			logger.error("Failed to parse OIDC request body", e);
			addErrMsg(errMsg, "requestBody", "invalidJson");
		} catch (Exception e) {
			logger.error("OIDC token conversion failed", e);
			addErrMsg(errMsg, "conversion", "failed");
		}

		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	/**
	 * Extract username from OIDC user_info object.
	 * Tries multiple common OIDC claims in order of preference.
	 */
	private String extractUsernameFromOIDC(JSONObject userInfo) {
		// Try preferred_username first (Keycloak commonly uses this)
		String userName = (String) userInfo.get("preferred_username");
		if (StringUtils.isNotBlank(userName)) {
			return userName;
		}

		// Try email
		userName = (String) userInfo.get("email");
		if (StringUtils.isNotBlank(userName)) {
			return userName;
		}

		// Try sub (subject - unique user identifier)
		userName = (String) userInfo.get("sub");
		if (StringUtils.isNotBlank(userName)) {
			return userName;
		}

		// Try name
		userName = (String) userInfo.get("name");
		if (StringUtils.isNotBlank(userName)) {
			return userName;
		}

		return null;
	}

	/**
	 * Convert SAML response to NemakiWare authentication token.
	 * Called by React UI after successful SAML authentication.
	 *
	 * Expected request body:
	 * {
	 *   "saml_response": "base64_encoded_saml_response",
	 *   "relay_state": "repositoryId=bedroom",
	 *   "user_attributes": {
	 *     "email": "user@example.com",
	 *     "displayName": "User Name",
	 *     ...
	 *   }
	 * }
	 */
	@POST
	@Path("/saml/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertSAMLResponse(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== AuthTokenResource.convertSAMLResponse() called for repository: {} ===", repositoryId);

		try {
			// Parse request body
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);

			String samlResponse = (String) requestJson.get("saml_response");
			String relayState = (String) requestJson.get("relay_state");
			JSONObject userAttributes = (JSONObject) requestJson.get("user_attributes");

			if (StringUtils.isBlank(samlResponse)) {
				addErrMsg(errMsg, "saml_response", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			// Extract username from SAML response or user attributes
			String userName = extractUsernameFromSAML(samlResponse, userAttributes);

			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.info("SAML user identified: {}", userName);

			// Get TokenService
			TokenService tokenService = getTokenService();
			if (tokenService == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}

			// Generate NemakiWare token for the SAML user
			String app = "saml";
			Token token = tokenService.setToken(app, repositoryId, userName);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);

			status = true;
			logger.info("=== SAML token conversion successful for user: {} ===", userName);

		} catch (ParseException e) {
			logger.error("Failed to parse SAML request body", e);
			addErrMsg(errMsg, "requestBody", "invalidJson");
		} catch (Exception e) {
			logger.error("SAML token conversion failed", e);
			addErrMsg(errMsg, "conversion", "failed");
		}

		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	/**
	 * Extract username from SAML response or user attributes.
	 * In a real implementation, the SAML response should be decoded and validated.
	 * For simplicity, this implementation prioritizes user_attributes if provided.
	 */
	private String extractUsernameFromSAML(String samlResponse, JSONObject userAttributes) {
		// Try to get username from user_attributes first (if provided by IdP adapter)
		if (userAttributes != null) {
			// Try email
			String userName = (String) userAttributes.get("email");
			if (StringUtils.isNotBlank(userName)) {
				return userName;
			}

			// Try uid
			userName = (String) userAttributes.get("uid");
			if (StringUtils.isNotBlank(userName)) {
				return userName;
			}

			// Try username
			userName = (String) userAttributes.get("username");
			if (StringUtils.isNotBlank(userName)) {
				return userName;
			}

			// Try displayName
			userName = (String) userAttributes.get("displayName");
			if (StringUtils.isNotBlank(userName)) {
				return userName;
			}

			// Try NameID
			userName = (String) userAttributes.get("NameID");
			if (StringUtils.isNotBlank(userName)) {
				return userName;
			}
		}

		// Fallback: Try to decode SAML response and extract NameID
		// This is a simplified implementation - production would need proper SAML parsing
		try {
			String decodedSaml = new String(Base64.getDecoder().decode(samlResponse));
			// Simple extraction - look for NameID element
			int nameIdStart = decodedSaml.indexOf("<saml:NameID");
			if (nameIdStart == -1) {
				nameIdStart = decodedSaml.indexOf("<NameID");
			}
			if (nameIdStart != -1) {
				int valueStart = decodedSaml.indexOf(">", nameIdStart) + 1;
				int valueEnd = decodedSaml.indexOf("<", valueStart);
				if (valueStart > 0 && valueEnd > valueStart) {
					String nameId = decodedSaml.substring(valueStart, valueEnd).trim();
					if (StringUtils.isNotBlank(nameId)) {
						return nameId;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to decode SAML response for username extraction: {}", e.getMessage());
		}

		return null;
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
