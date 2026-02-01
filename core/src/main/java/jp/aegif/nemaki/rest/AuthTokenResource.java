package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mindrot.jbcrypt.BCrypt;
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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Context;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.Inflater;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;

@Path("/repo/{repositoryId}/authtoken/")
public class AuthTokenResource extends ResourceBase{

	private static final Logger logger = LoggerFactory.getLogger(AuthTokenResource.class);

	private TokenService tokenService;
	
	@Context 
	private HttpServletRequest request;
	
	@Context
	private HttpServletResponse response;
	
	// Cookie name for HttpOnly auth token
	public static final String AUTH_TOKEN_COOKIE_NAME = "nemaki_auth_token";
	
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
	
	/**
	 * Logout endpoint - clears the HttpOnly auth cookie and invalidates the token.
	 * 
	 * This endpoint should be called when the user logs out to ensure:
	 * 1. The HttpOnly cookie is cleared (browser will delete it)
	 * 2. The server-side token is invalidated via TokenService.invalidateToken()
	 * 
	 * @param repositoryId The repository ID
	 * @param userName The username
	 * @return JSON response indicating success/failure
	 */
	@POST
	@Path("/{userName}/logout")
	@Produces(MediaType.APPLICATION_JSON)
	public String logout(@PathParam("repositoryId") String repositoryId,
	                    @PathParam("userName") String userName) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== AuthTokenResource.logout() called for user: {} in repository: {} ===",
		           userName, repositoryId);

		// Clear the HttpOnly cookie
		clearAuthTokenCookie();

		// Invalidate the token on server side
		// This ensures the token cannot be reused even if it hasn't expired yet
		try {
			TokenService tokenService = getTokenService();
			if (tokenService != null) {
				String app = ""; // Default app for React UI
				tokenService.invalidateToken(app, repositoryId, userName);
				logger.info("Token invalidated for user: {} in repository: {}", userName, repositoryId);
			} else {
				logger.warn("TokenService not available, token not invalidated for user: {}", userName);
			}
		} catch (Exception e) {
			// Log but don't fail the logout - cookie is already cleared
			logger.warn("Failed to invalidate token for user: {}, error: {}", userName, e.getMessage());
		}

		JSONObject obj = new JSONObject();
		obj.put("repositoryId", repositoryId);
		obj.put("userName", userName);
		obj.put("message", "Logged out successfully");
		result.put("value", obj);

		logger.info("=== Logout successful for user: {} ===", userName);

		return makeResult(status, result, errMsg).toString();
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

			// Set HttpOnly cookie for secure token storage
			// This prevents XSS attacks from accessing the token via JavaScript
			setAuthTokenCookie(token.getToken(), repositoryId);

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

			// Set HttpOnly cookie for secure token storage (same as login)
			setAuthTokenCookie(token.getToken(), repositoryId);

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

	/**
	 * Convert an OIDC authentication to a NemakiWare auth token.
	 * The server validates the access_token by calling the provider's UserInfo endpoint.
	 * This prevents clients from forging identity claims.
	 *
	 * Required JSON body:
	 *   { "access_token": "...", "userinfo_endpoint": "https://..." }
	 * OR for backward compatibility:
	 *   { "user_info": {...}, "access_token": "...", "userinfo_endpoint": "https://..." }
	 *
	 * When access_token + userinfo_endpoint are provided, the server calls the endpoint
	 * to obtain verified user information. The client-supplied user_info is ignored.
	 */
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

			String accessToken = (String) requestJson.get("access_token");
			String userinfoEndpoint = (String) requestJson.get("userinfo_endpoint");

			if (StringUtils.isBlank(accessToken) || StringUtils.isBlank(userinfoEndpoint)) {
				addErrMsg(errMsg, "access_token", "access_token and userinfo_endpoint are required");
				return makeResult(false, result, errMsg).toString();
			}

			// Server-side validation: call the provider's UserInfo endpoint with the access token
			JSONObject verifiedUserInfo = fetchUserInfoFromProvider(userinfoEndpoint, accessToken);
			if (verifiedUserInfo == null) {
				addErrMsg(errMsg, "access_token", "invalidOrExpired - UserInfo endpoint returned error");
				return makeResult(false, result, errMsg).toString();
			}

			boolean isMicrosoft = userinfoEndpoint.contains("graph.microsoft.com");
			String userName = extractUserNameFromOIDCUserInfo(verifiedUserInfo, isMicrosoft);
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

			// Set HttpOnly cookie for secure token storage (same as login)
			setAuthTokenCookie(token.getToken(), repositoryId);

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

	/**
	 * Convert a Google ID token to a NemakiWare auth token.
	 * The ID token is verified server-side using Google's public keys.
	 */
	@POST
	@Path("/google/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertGoogleToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== Google token conversion requested for repository: {} ===", repositoryId);

		if (StringUtils.isBlank(repositoryId)) {
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);

			String idTokenString = (String) requestJson.get("id_token");
			if (StringUtils.isBlank(idTokenString)) {
				addErrMsg(errMsg, "id_token", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			// Get Google client ID from configuration
			PropertyManager pm = getPropertyManager();
			String clientId = pm != null ? pm.readValue(PropertyKey.CLOUD_AUTH_GOOGLE_CLIENT_ID) : null;
			if (StringUtils.isBlank(clientId)) {
				addErrMsg(errMsg, "google", "notConfigured");
				return makeResult(false, result, errMsg).toString();
			}

			// Verify ID token using Google's library
			GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
					new NetHttpTransport(), GsonFactory.getDefaultInstance())
				.setAudience(Collections.singletonList(clientId))
				.build();

			GoogleIdToken idToken = verifier.verify(idTokenString);
			if (idToken == null) {
				addErrMsg(errMsg, "id_token", "invalidOrExpired");
				return makeResult(false, result, errMsg).toString();
			}

			GoogleIdToken.Payload payload = idToken.getPayload();
			String email = payload.getEmail();
			String userName = email != null ? email : payload.getSubject();

			logger.info("Google authentication successful for user: {}", userName);

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
			setAuthTokenCookie(token.getToken(), repositoryId);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);

			status = true;
			logger.info("=== Google token conversion successful for user: {} ===", userName);

		} catch (ParseException e) {
			logger.error("Failed to parse Google request body", e);
			addErrMsg(errMsg, "requestBody", "invalidJson");
		} catch (Exception e) {
			logger.error("Google token conversion failed", e);
			addErrMsg(errMsg, "google", "conversionFailed");
		}

		return makeResult(status, result, errMsg).toString();
	}

	/**
	 * Convert a Microsoft ID token to a NemakiWare auth token.
	 * The ID token is verified server-side using Microsoft's JWKS endpoint.
	 * Validates: signature (RS256), audience, issuer, exp, nbf.
	 */
	@POST
	@Path("/microsoft/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertMicrosoftToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		logger.info("=== Microsoft token conversion requested for repository: {} ===", repositoryId);

		if (StringUtils.isBlank(repositoryId)) {
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);

			String idTokenString = (String) requestJson.get("id_token");
			if (StringUtils.isBlank(idTokenString)) {
				addErrMsg(errMsg, "id_token", "isNull");
				return makeResult(false, result, errMsg).toString();
			}

			// Get Microsoft configuration
			PropertyManager pm = getPropertyManager();
			String clientId = pm != null ? pm.readValue(PropertyKey.CLOUD_AUTH_MICROSOFT_CLIENT_ID) : null;
			String tenantId = pm != null ? pm.readValue(PropertyKey.CLOUD_AUTH_MICROSOFT_TENANT_ID) : null;
			if (StringUtils.isBlank(clientId)) {
				addErrMsg(errMsg, "microsoft", "notConfigured");
				return makeResult(false, result, errMsg).toString();
			}
			// Require specific tenant ID for issuer validation security.
			// "common" or "organizations" disable issuer pinning and are not supported.
			if (StringUtils.isBlank(tenantId) || "common".equals(tenantId) || "organizations".equals(tenantId)) {
				addErrMsg(errMsg, "microsoft", "tenantId must be a specific tenant UUID, not 'common' or 'organizations'");
				return makeResult(false, result, errMsg).toString();
			}

			// Verify ID token using Microsoft's JWKS endpoint via nimbus-jose-jwt
			String jwksUrl = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
			ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
			JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(new URL(jwksUrl));
			JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
			jwtProcessor.setJWSKeySelector(keySelector);

			// Configure claims verification: issuer, exp, nbf
			String expectedIssuer = "https://login.microsoftonline.com/" + tenantId + "/v2.0";
			jwtProcessor.setJWTClaimsSetVerifier((claimsSet, context) -> {
				// Verify issuer
				String issuer = claimsSet.getIssuer();
				if (issuer == null || !issuer.equals(expectedIssuer)) {
					throw new com.nimbusds.jwt.proc.BadJWTException(
							"Invalid issuer: " + issuer + " (expected: " + expectedIssuer + ")");
				}
				// Verify expiration
				java.util.Date exp = claimsSet.getExpirationTime();
				if (exp == null || new java.util.Date().after(exp)) {
					throw new com.nimbusds.jwt.proc.BadJWTException("Token has expired");
				}
				// Verify not-before
				java.util.Date nbf = claimsSet.getNotBeforeTime();
				if (nbf != null && new java.util.Date().before(nbf)) {
					throw new com.nimbusds.jwt.proc.BadJWTException("Token is not yet valid (nbf)");
				}
			});

			JWTClaimsSet claims = jwtProcessor.process(idTokenString, null);

			// Verify audience
			if (!claims.getAudience().contains(clientId)) {
				addErrMsg(errMsg, "id_token", "audienceMismatch");
				return makeResult(false, result, errMsg).toString();
			}

			// Extract user identifier
			String preferredUsername = claims.getStringClaim("preferred_username");
			String email = claims.getStringClaim("email");
			String oid = claims.getStringClaim("oid");
			String userName = preferredUsername != null ? preferredUsername :
			                  email != null ? email : oid;

			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.info("Microsoft authentication successful for user: {}", userName);

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
			setAuthTokenCookie(token.getToken(), repositoryId);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);

			status = true;
			logger.info("=== Microsoft token conversion successful for user: {} ===", userName);

		} catch (ParseException e) {
			logger.error("Failed to parse Microsoft request body", e);
			addErrMsg(errMsg, "requestBody", "invalidJson");
		} catch (Exception e) {
			logger.error("Microsoft token conversion failed", e);
			addErrMsg(errMsg, "microsoft", "conversionFailed");
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

			// XXE prevention: disable external entities and DTDs
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new ByteArrayInputStream(xmlBytes));

			// WARNING: This implementation does NOT verify the SAML response signature.
			// In production, you MUST validate the XML signature against the IdP's certificate
			// to prevent identity spoofing. Consider using OpenSAML or a similar library
			// for proper SAML signature validation, issuer/audience/conditions checking.
			logger.warn("SAML response signature validation is not implemented. " +
					"This is a security risk in production environments.");

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

	/**
	 * Extract username from OIDC UserInfo response.
	 * Priority order differs by provider:
	 * - Microsoft: userPrincipalName → mail → preferred_username → email → sub
	 *   (MS Graph /v1.0/me returns userPrincipalName as primary identifier; mail can be null)
	 * - Other (Google etc.): preferred_username → email → sub
	 */
	private String extractUserNameFromOIDCUserInfo(JSONObject userInfo, boolean isMicrosoft) {
		if (isMicrosoft) {
			// Microsoft: userPrincipalName is the canonical identifier
			if (userInfo.containsKey("userPrincipalName")) {
				String upn = (String) userInfo.get("userPrincipalName");
				if (upn != null && !upn.isEmpty()) return upn;
			}
			if (userInfo.containsKey("mail")) {
				String mail = (String) userInfo.get("mail");
				if (mail != null && !mail.isEmpty()) return mail;
			}
		}
		// Standard OIDC claims (used by Google and as fallback for Microsoft)
		if (userInfo.containsKey("preferred_username")) {
			String pu = (String) userInfo.get("preferred_username");
			if (pu != null && !pu.isEmpty()) return pu;
		}
		if (userInfo.containsKey("email")) {
			String email = (String) userInfo.get("email");
			if (email != null && !email.isEmpty()) return email;
		}
		// Fallback to sub (opaque ID)
		if (userInfo.containsKey("sub")) {
			return (String) userInfo.get("sub");
		}
		return null;
	}

	/**
	 * Allowed OIDC UserInfo endpoints: host → allowed path prefixes.
	 * Validation uses URI parsing (not string prefix) to prevent userinfo/port SSRF bypasses.
	 */
	private static final java.util.Map<String, java.util.List<String>> ALLOWED_USERINFO_HOSTS;
	static {
		java.util.Map<String, java.util.List<String>> m = new java.util.HashMap<>();
		m.put("www.googleapis.com", java.util.List.of("/oauth2/"));
		m.put("openidconnect.googleapis.com", java.util.List.of("/"));
		m.put("graph.microsoft.com", java.util.List.of("/oidc/userinfo", "/v1.0/me"));
		ALLOWED_USERINFO_HOSTS = java.util.Collections.unmodifiableMap(m);
	}

	/**
	 * Validates a UserInfo endpoint URL against the allowlist using URI parsing.
	 * Rejects URLs with: userinfo (user:pass@), non-443 ports, non-HTTPS scheme,
	 * or hosts/paths not in the allowlist.
	 */
	private boolean isAllowedUserInfoEndpoint(String url) {
		try {
			java.net.URI uri = new java.net.URI(url);

			// Reject userinfo component (e.g. https://graph.microsoft.com@evil.com/...)
			if (uri.getUserInfo() != null) {
				return false;
			}

			// Must be https
			String scheme = uri.getScheme();
			if (scheme == null || !"https".equals(scheme.toLowerCase(java.util.Locale.ROOT))) {
				return false;
			}

			// Reject explicit non-standard port
			int port = uri.getPort();
			if (port != -1 && port != 443) {
				return false;
			}

			String host = uri.getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(java.util.Locale.ROOT);

			java.util.List<String> allowedPaths = ALLOWED_USERINFO_HOSTS.get(host);
			if (allowedPaths == null) {
				return false;
			}

			String path = uri.getPath();
			if (path == null) {
				path = "/";
			}

			for (String allowedPath : allowedPaths) {
				if (path.startsWith(allowedPath)) {
					return true;
				}
			}
			return false;
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	/**
	 * Fetch user info from an OIDC provider's UserInfo endpoint using the access token.
	 * This provides server-side validation that the access token is valid.
	 *
	 * SSRF prevention: Only known OIDC provider hosts and path prefixes are allowed.
	 * URI is normalized (trailing slash removed) before validation.
	 */
	@SuppressWarnings("unchecked")
	private JSONObject fetchUserInfoFromProvider(String userinfoEndpoint, String accessToken) {
		if (userinfoEndpoint == null || userinfoEndpoint.trim().isEmpty()) {
			return null;
		}

		// Normalize: trim whitespace, remove trailing slash
		String normalized = userinfoEndpoint.trim();
		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		// SSRF prevention: validate against allowed hosts/paths via URI parsing
		if (!isAllowedUserInfoEndpoint(normalized)) {
			logger.error("UserInfo endpoint not allowed: {}", userinfoEndpoint);
			return null;
		}

		try {
			URL url = new URL(normalized);
			if (!"https".equals(url.getProtocol())) {
				logger.error("UserInfo endpoint must use HTTPS: {}", normalized);
				return null;
			}

			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Authorization", "Bearer " + accessToken);
			conn.setRequestProperty("Accept", "application/json");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);

			int responseCode = conn.getResponseCode();
			if (responseCode != 200) {
				logger.error("UserInfo endpoint returned HTTP {}", responseCode);
				return null;
			}

			try (java.io.InputStream is = conn.getInputStream();
			     java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
				JSONParser parser = new JSONParser();
				return (JSONObject) parser.parse(reader);
			}
		} catch (Exception e) {
			logger.error("Failed to fetch UserInfo from provider: {}", e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Get an existing user or create a new one for SSO authentication.
	 *
	 * SSO AUTO-PROVISIONING (2026-01-08):
	 * When a user authenticates via OIDC/SAML and doesn't exist in NemakiWare,
	 * this method automatically creates a user account with:
	 * - userId: extracted from SSO token (preferred_username, email, or sub)
	 * - name: same as userId (can be updated later)
	 * - password: random UUID hash (SSO users don't use password authentication)
	 * - admin: false (non-admin by default)
	 * - parentId: users folder under system folder
	 *
	 * @param repositoryId Repository ID
	 * @param userName User name extracted from SSO token
	 * @return UserItem object (existing or newly created), or null if creation failed
	 */
	private UserItem getOrCreateUser(String repositoryId, String userName) {
		try {
			ContentService contentService = getContentService();
			if (contentService == null) {
				logger.error("ContentService not available");
				return null;
			}

			// Check if user already exists
			UserItem userItem = contentService.getUserItemById(repositoryId, userName);
			if (userItem != null) {
				logger.info("Found existing user: {}", userName);
				return userItem;
			}

			// User not found - create new user for SSO
			logger.info("User {} not found, creating new user for SSO auto-provisioning", userName);

			// Get users folder
			Folder usersFolder = getOrCreateUsersFolder(repositoryId, contentService);
			if (usersFolder == null) {
				logger.error("Failed to get or create users folder for SSO user: {}", userName);
				return null;
			}

			// Generate a random password hash (SSO users don't use password auth)
			String randomPassword = UUID.randomUUID().toString();
			String passwordHash = BCrypt.hashpw(randomPassword, BCrypt.gensalt());

			// Create new user
			UserItem newUser = new UserItem(
				null,                          // id (auto-generated)
				NemakiObjectType.nemakiUser,  // objectType
				userName,                      // userId
				userName,                      // name (same as userId, can be updated later)
				passwordHash,                  // password (random hash for SSO users)
				false,                         // isAdmin
				usersFolder.getId()           // parentId
			);

			// Set creation signature
			newUser.setCreator(userName);
			newUser.setModifier(userName);
			newUser.setCreated(new java.util.GregorianCalendar());
			newUser.setModified(new java.util.GregorianCalendar());

			// Create user in repository
			UserItem createdUser = contentService.createUserItem(
				new SystemCallContext(repositoryId),
				repositoryId,
				newUser
			);

			if (createdUser != null) {
				logger.info("Successfully created SSO user: {} (id: {})", userName, createdUser.getId());
				return createdUser;
			} else {
				logger.error("Failed to create SSO user: {} - createUserItem returned null", userName);
				return null;
			}

		} catch (Exception e) {
			logger.error("Failed to get or create user: " + userName, e);
			return null;
		}
	}

	/**
	 * Get or create the users folder under the system folder.
	 *
	 * Uses the same pattern as UserItemResource.getOrCreateSystemSubFolder()
	 *
	 * @param repositoryId Repository ID
	 * @param contentService ContentService instance
	 * @return Users folder, or null if not found/created
	 */
	private Folder getOrCreateUsersFolder(String repositoryId, ContentService contentService) {
		try {
			// Get system folder (same approach as UserItemResource)
			Folder systemFolder = contentService.getSystemFolder(repositoryId);
			if (systemFolder == null) {
				logger.error("System folder not found for repository: {}", repositoryId);
				return null;
			}

			// Search for existing users folder in system folder children
			java.util.List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
			if (children != null) {
				for (jp.aegif.nemaki.model.Content child : children) {
					if ("users".equals(child.getName()) && child instanceof Folder) {
						logger.debug("Found existing users folder: {}", child.getId());
						return (Folder) child;
					}
				}
			}

			// Create users folder if it doesn't exist
			logger.info("Creating users folder under system folder for repository: {}", repositoryId);
			org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl properties =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl("cmis:name", "users"));
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));

			Folder usersFolder = contentService.createFolder(
				new SystemCallContext(repositoryId),
				repositoryId,
				properties,
				systemFolder,
				null, null, null, null
			);

			if (usersFolder != null) {
				logger.info("Successfully created users folder: {}", usersFolder.getId());
			}

			return usersFolder;
		} catch (Exception e) {
			logger.error("Failed to get or create users folder: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Get PropertyManager from Spring context.
	 */
	private PropertyManager getPropertyManager() {
		try {
			WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(
				request.getServletContext());
			if (context != null) {
				return context.getBean("propertyManager", PropertyManager.class);
			}
		} catch (Exception e) {
			logger.error("Failed to retrieve PropertyManager from Spring context", e);
		}
		return null;
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
	 * Set HttpOnly cookie with authentication token.
	 * 
	 * Security features:
	 * - HttpOnly: Prevents JavaScript access (XSS protection)
	 * - Secure: Only sent over HTTPS (when not localhost)
	 * - SameSite=Strict: CSRF protection
	 * - Path=/core: Scoped to application context
	 * 
	 * @param token The authentication token
	 * @param repositoryId The repository ID (for logging)
	 */
	private void setAuthTokenCookie(String token, String repositoryId) {
		if (response == null) {
			logger.warn("HttpServletResponse not available, cannot set auth cookie");
			return;
		}

		// Build cookie string manually to include SameSite attribute
		// This avoids using setHeader which could overwrite other Set-Cookie headers
		StringBuilder cookieBuilder = new StringBuilder();
		cookieBuilder.append(AUTH_TOKEN_COOKIE_NAME).append("=").append(token);
		cookieBuilder.append("; Path=/core");
		cookieBuilder.append("; Max-Age=").append(24 * 60 * 60); // 24 hours
		cookieBuilder.append("; HttpOnly");

		// Set Secure flag for HTTPS connections (skip for localhost development)
		String serverName = request != null ? request.getServerName() : "";
		boolean isSecure = request != null && request.isSecure();
		if (isSecure || (!serverName.equals("localhost") && !serverName.equals("127.0.0.1"))) {
			cookieBuilder.append("; Secure");
		}

		// SameSite=Strict for CSRF protection
		cookieBuilder.append("; SameSite=Strict");

		// Use addHeader to avoid overwriting other Set-Cookie headers
		response.addHeader("Set-Cookie", cookieBuilder.toString());

		logger.debug("Auth token cookie set for repository: {}", repositoryId);
	}

	/**
	 * Clear the authentication cookie on logout.
	 * Sets the cookie with empty value and immediate expiration.
	 * 
	 * Uses the same format as setAuthTokenCookie() to ensure the cookie
	 * is properly deleted (must match Path, SameSite, etc. attributes).
	 */
	private void clearAuthTokenCookie() {
		if (response == null) {
			logger.warn("HttpServletResponse not available, cannot clear auth cookie");
			return;
		}

		// Build cookie string manually to include SameSite attribute
		// Must match the attributes used when setting the cookie for proper deletion
		StringBuilder cookieBuilder = new StringBuilder();
		cookieBuilder.append(AUTH_TOKEN_COOKIE_NAME).append("=");
		cookieBuilder.append("; Path=/core");
		cookieBuilder.append("; Max-Age=0"); // Immediate expiration
		cookieBuilder.append("; HttpOnly");

		// Set Secure flag for HTTPS connections (skip for localhost development)
		String serverName = request != null ? request.getServerName() : "";
		boolean isSecure = request != null && request.isSecure();
		if (isSecure || (!serverName.equals("localhost") && !serverName.equals("127.0.0.1"))) {
			cookieBuilder.append("; Secure");
		}

		// SameSite must match the original cookie for proper deletion
		cookieBuilder.append("; SameSite=Strict");

		// Use addHeader to avoid overwriting other Set-Cookie headers
		response.addHeader("Set-Cookie", cookieBuilder.toString());

		logger.debug("Auth token cookie cleared");
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
