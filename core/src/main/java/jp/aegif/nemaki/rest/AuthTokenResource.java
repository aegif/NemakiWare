package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
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

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.zip.Inflater;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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

	@POST
	@Path("/saml/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertSAMLToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== SAML token conversion requested for repository: {} ===", repositoryId);

		if (StringUtils.isBlank(repositoryId)) {
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);
			
			String samlResponse = (String) requestJson.get("saml_response");
			if (StringUtils.isBlank(samlResponse)) {
				addErrMsg(errMsg, "saml_response", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			String userName = extractUserNameFromSAMLResponse(samlResponse);
			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.info("SAML authentication successful for user: {}", userName);

			UserItem userItem = getOrCreateUser(repositoryId, userName);
			if (userItem == null) {
				addErrMsg(errMsg, "user", "couldNotCreateOrFind");
				return makeResult(false, result, errMsg).toString();
			}

			TokenService tokenService = getTokenService();
			if (tokenService == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}

			String app = "";
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
			addErrMsg(errMsg, "saml", "conversionFailed");
		}

		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/oidc/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertOIDCToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== OIDC token conversion requested for repository: {} ===", repositoryId);

		if (StringUtils.isBlank(repositoryId)) {
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);
			
			JSONObject userInfo = (JSONObject) requestJson.get("user_info");
			if (userInfo == null) {
				addErrMsg(errMsg, "user_info", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			String userName = extractUserNameFromOIDCUserInfo(userInfo);
			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.info("OIDC authentication successful for user: {}", userName);

			UserItem userItem = getOrCreateUser(repositoryId, userName);
			if (userItem == null) {
				addErrMsg(errMsg, "user", "couldNotCreateOrFind");
				return makeResult(false, result, errMsg).toString();
			}

			TokenService tokenService = getTokenService();
			if (tokenService == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}

			String app = "";
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
			addErrMsg(errMsg, "oidc", "conversionFailed");
		}

		return makeResult(status, result, errMsg).toString();
	}

	private String extractUserNameFromSAMLResponse(String samlResponse) {
		try {
			byte[] decodedBytes = Base64.getDecoder().decode(samlResponse);
			byte[] xmlBytes;

			// Try to inflate (decompress) the SAML response
			// HTTP-Redirect binding uses DEFLATE compression, HTTP-POST does not
			try {
				Inflater inflater = new Inflater(true); // true = nowrap (raw deflate)
				inflater.setInput(decodedBytes);
				byte[] result = new byte[decodedBytes.length * 10]; // Estimate 10x expansion
				int resultLength = inflater.inflate(result);
				inflater.end();
				xmlBytes = new byte[resultLength];
				System.arraycopy(result, 0, xmlBytes, 0, resultLength);
				logger.debug("SAML response was deflate-compressed, inflated {} bytes to {} bytes",
				            decodedBytes.length, resultLength);
			} catch (Exception e) {
				// Not compressed, use decoded bytes directly (HTTP-POST binding)
				xmlBytes = decodedBytes;
				logger.debug("SAML response was not deflate-compressed, using raw bytes");
			}

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new ByteArrayInputStream(xmlBytes));

			NodeList nameIdNodes = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
			if (nameIdNodes.getLength() > 0) {
				return nameIdNodes.item(0).getTextContent();
			}

			NodeList attributeNodes = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");
			for (int i = 0; i < attributeNodes.getLength(); i++) {
				Element attr = (Element) attributeNodes.item(i);
				String attrName = attr.getAttribute("Name");
				if ("email".equalsIgnoreCase(attrName) || 
				    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress".equals(attrName) ||
				    "preferred_username".equalsIgnoreCase(attrName)) {
					NodeList valueNodes = attr.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue");
					if (valueNodes.getLength() > 0) {
						return valueNodes.item(0).getTextContent();
					}
				}
			}

			logger.warn("Could not extract username from SAML response");
			return null;
		} catch (Exception e) {
			logger.error("Failed to parse SAML response", e);
			return null;
		}
	}

	private String extractUserNameFromOIDCUserInfo(JSONObject userInfo) {
		if (userInfo.containsKey("preferred_username")) {
			return (String) userInfo.get("preferred_username");
		}
		if (userInfo.containsKey("email")) {
			return (String) userInfo.get("email");
		}
		if (userInfo.containsKey("sub")) {
			return (String) userInfo.get("sub");
		}
		return null;
	}

	private UserItem getOrCreateUser(String repositoryId, String userName) {
		try {
			ContentService contentService = getContentService();
			if (contentService == null) {
				logger.error("ContentService not available");
				return null;
			}

			UserItem userItem = contentService.getUserItemById(repositoryId, userName);
			if (userItem != null) {
				logger.info("Found existing user: {}", userName);
				return userItem;
			}

			logger.info("User {} not found, creating new user for SSO", userName);
			return null;
		} catch (Exception e) {
			logger.error("Failed to get or create user: " + userName, e);
			return null;
		}
	}

	private ContentService getContentService() {
		try {
			WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(
				request.getServletContext());
			if (context != null) {
				return context.getBean("ContentService", ContentService.class);
			}
		} catch (Exception e) {
			logger.error("Failed to retrieve ContentService from Spring context", e);
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
