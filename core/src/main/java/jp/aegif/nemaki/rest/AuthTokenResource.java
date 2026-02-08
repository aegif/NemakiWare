package jp.aegif.nemaki.rest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
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

		// SECURITY FIX: Only allow access to own tokens (or admin)
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		if (!isAuthorizedForUser(callContext, userName)) {
			addErrMsg(errMsg, "authorization", "Access denied: can only access own tokens");
			return makeResult(false, result, errMsg).toString();
		}

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
	
	@POST
	@Path("/{userName}/register")
	@Produces(MediaType.APPLICATION_JSON)
	public String register(@PathParam("repositoryId") String repositoryId, @PathParam("userName") String userName, @QueryParam("app") String app){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// SECURITY FIX: Only allow registering own tokens (or admin)
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		if (!isAuthorizedForUser(callContext, userName)) {
			addErrMsg(errMsg, "authorization", "Access denied: can only register own tokens");
			return makeResult(false, result, errMsg).toString();
		}

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

		// SECURITY FIX: Only allow logout of own session (or admin)
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		if (!isAuthorizedForUser(callContext, userName)) {
			addErrMsg(errMsg, "authorization", "Access denied: can only logout own session");
			return makeResult(false, result, errMsg).toString();
		}

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
		
		// Extract password from request body (form-encoded or JSON)
		String password = null;
		if (requestBody != null && !requestBody.isEmpty()) {
			if (requestBody.startsWith("{")) {
				try {
					JSONParser parser = new JSONParser();
					JSONObject bodyJson = (JSONObject) parser.parse(requestBody);
					password = (String) bodyJson.get("password");
				} catch (Exception e) {
					logger.warn("Failed to parse JSON request body for login");
				}
			} else {
				// Form-encoded: password=xxx
				for (String param : requestBody.split("&")) {
					String[] kv = param.split("=", 2);
					if (kv.length == 2 && "password".equals(kv[0])) {
						password = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
					}
				}
			}
		}
		if (StringUtils.isBlank(password)) {
			addErrMsg(errMsg, "password", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		// Authenticate using AuthenticationService
		try {
			org.apache.chemistry.opencmis.commons.enums.CmisVersion cmisVersion =
				org.apache.chemistry.opencmis.commons.enums.CmisVersion.CMIS_1_1;
			org.apache.chemistry.opencmis.commons.server.CallContext ctxt =
				new org.apache.chemistry.opencmis.server.impl.CallContextImpl(
					null, cmisVersion, repositoryId, null, null, null, null, null);
			((org.apache.chemistry.opencmis.server.impl.CallContextImpl) ctxt).put(
				org.apache.chemistry.opencmis.commons.server.CallContext.USERNAME, userName);
			((org.apache.chemistry.opencmis.server.impl.CallContextImpl) ctxt).put(
				org.apache.chemistry.opencmis.commons.server.CallContext.PASSWORD, password);

			jp.aegif.nemaki.cmis.factory.auth.AuthenticationService authService = getAuthenticationService();
			if (authService == null) {
				addErrMsg(errMsg, "authService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}
			boolean authenticated = authService.login(ctxt);
			if (!authenticated) {
				addErrMsg(errMsg, "login", "invalidCredentials");
				return makeResult(false, result, errMsg).toString();
			}
		} catch (Exception e) {
			logger.error("Authentication failed for user: " + userName, e);
			addErrMsg(errMsg, "login", "authenticationError");
			return makeResult(false, result, errMsg).toString();
		}

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
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// SECURITY FIX: SAML response signature verification is not implemented.
		// Without signature verification, an attacker can forge arbitrary SAML responses
		// and impersonate any user. This endpoint is disabled until proper SAML signature
		// validation (e.g., via OpenSAML) is implemented.
		logger.warn("SAML token conversion rejected - signature verification not implemented");
		addErrMsg(errMsg, "saml", "SAML authentication is not available. " +
				"SAML response signature verification is not implemented. Use OIDC authentication instead.");
		return makeResult(false, result, errMsg).toString();
	}

	/**
	 * Convert an OIDC authentication to a NemakiWare auth token.
	 * The server validates the access_token by calling the provider's UserInfo endpoint.
	 * This prevents clients from forging identity claims.
	 *
	 * Required JSON body:
	 *   { "access_token": "...", "userinfo_endpoint": "https://..." }
	 * OR (UI compatibility):
	 *   { "oidc_token": "...", "id_token": "...", "user_info": {...} }
	 *
	 * When userinfo_endpoint is not provided, the server discovers it from the
	 * configured oidc.issuer property via OIDC Discovery (/.well-known/openid-configuration).
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

			// Accept both "access_token" (standard) and "oidc_token" (UI compatibility)
			String accessToken = (String) requestJson.get("access_token");
			if (StringUtils.isBlank(accessToken)) {
				accessToken = (String) requestJson.get("oidc_token");
			}

			// Accept "userinfo_endpoint" or derive from configured oidc.issuer via OIDC Discovery
			String userinfoEndpoint = (String) requestJson.get("userinfo_endpoint");
			if (StringUtils.isBlank(userinfoEndpoint)) {
				PropertyManager pm = getPropertyManager();
				if (pm != null) {
					String issuerUrl = pm.readValue(PropertyKey.OIDC_ISSUER);
					if (StringUtils.isNotBlank(issuerUrl)) {
						userinfoEndpoint = discoverUserInfoEndpoint(issuerUrl);
						if (userinfoEndpoint != null) {
							logger.info("Discovered userinfo_endpoint via OIDC Discovery: {}", userinfoEndpoint);
						}
					}
				}
			}

			if (StringUtils.isBlank(accessToken) || StringUtils.isBlank(userinfoEndpoint)) {
				addErrMsg(errMsg, "access_token", "access_token and userinfo_endpoint are required (or configure oidc.issuer)");
				return makeResult(false, result, errMsg).toString();
			}

			// Server-side validation: call the provider's UserInfo endpoint with the access token
			JSONObject verifiedUserInfo = fetchUserInfoFromProvider(userinfoEndpoint, accessToken);
			if (verifiedUserInfo == null) {
				addErrMsg(errMsg, "access_token", "invalidOrExpired - UserInfo endpoint returned error");
				return makeResult(false, result, errMsg).toString();
			}

			boolean isMicrosoft = userinfoEndpoint.contains("graph.microsoft.com")
					|| userinfoEndpoint.contains("login.microsoftonline.com");
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

			// Check if cloud/OIDC authentication is allowed for this user
			jp.aegif.nemaki.cmis.factory.auth.AuthenticationService authService = getAuthenticationService();
			if (authService != null && !authService.isAuthMethodAllowed(userItem, "cloud")) {
				logger.info("OIDC authentication denied for user {} (not in allowedAuthMethods)", userName);
				addErrMsg(errMsg, "auth", "methodNotAllowed");
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

			// Check if cloud/Google authentication is allowed for this user
			jp.aegif.nemaki.cmis.factory.auth.AuthenticationService authService = getAuthenticationService();
			if (authService != null && !authService.isAuthMethodAllowed(userItem, "cloud")) {
				logger.info("Google authentication denied for user {} (not in allowedAuthMethods)", userName);
				addErrMsg(errMsg, "auth", "methodNotAllowed");
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

			// Check if cloud/Microsoft authentication is allowed for this user
			jp.aegif.nemaki.cmis.factory.auth.AuthenticationService authService = getAuthenticationService();
			if (authService != null && !authService.isAuthMethodAllowed(userItem, "cloud")) {
				logger.info("Microsoft authentication denied for user {} (not in allowedAuthMethods)", userName);
				addErrMsg(errMsg, "auth", "methodNotAllowed");
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
			// ACCESS_EXTERNAL_DTD/SCHEMA may not be supported by all parsers (e.g. Apache Xerces in Tomcat)
			// The disallow-doctype-decl feature above already provides XXE protection
			try {
				factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
				factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			} catch (IllegalArgumentException e) {
				logger.debug("XML parser does not support ACCESS_EXTERNAL_DTD/SCHEMA properties (XXE prevention via other features)");
			}

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
		m.put("login.microsoftonline.com", java.util.List.of("/common/openid/userinfo", "/common/v2.0/"));
		ALLOWED_USERINFO_HOSTS = java.util.Collections.unmodifiableMap(m);
	}

	/**
	 * Validates a UserInfo endpoint URL against the allowlist using URI parsing.
	 * Rejects URLs with: userinfo (user:pass@), encoded path traversal (%2f, %2e),
	 * or hosts/paths not in the allowlist.
	 *
	 * In addition to the static allowlist (Google, Microsoft), the configured OIDC issuer
	 * host is dynamically allowed, supporting Keycloak and other OIDC providers.
	 */
	private boolean isAllowedUserInfoEndpoint(String url) {
		if (url == null || url.isEmpty()) {
			return false;
		}
		try {
			java.net.URI uri = new java.net.URI(url).normalize();

			// Reject userinfo component (e.g. https://graph.microsoft.com@evil.com/...)
			if (uri.getUserInfo() != null) {
				return false;
			}

			String scheme = uri.getScheme();
			if (scheme == null) {
				return false;
			}
			scheme = scheme.toLowerCase(java.util.Locale.ROOT);

			String host = uri.getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(java.util.Locale.ROOT);
			int port = uri.getPort();

			// Check static allowlist (must be HTTPS on port 443)
			if ("https".equals(scheme) && (port == -1 || port == 443)) {
				java.util.List<String> allowedPaths = ALLOWED_USERINFO_HOSTS.get(host);
				if (allowedPaths != null) {
					if (matchesAllowedPath(uri, allowedPaths)) {
						return true;
					}
				}
			}

			// Check dynamic allowlist: configured OIDC issuer host
			if (isAllowedByOidcIssuer(uri, host, port, scheme)) {
				return true;
			}

			return false;
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	/**
	 * Check if a URL path matches any of the allowed path patterns.
	 */
	private boolean matchesAllowedPath(java.net.URI uri, java.util.List<String> allowedPaths) {
		// Use rawPath to detect encoded path traversal (%2f, %2e, %5c)
		String rawPath = uri.getRawPath();
		if (rawPath == null) {
			rawPath = "/";
		}
		String rawPathLower = rawPath.toLowerCase(java.util.Locale.ROOT);
		if (rawPathLower.contains("%2f") || rawPathLower.contains("%2e")
				|| rawPathLower.contains("%5c") || rawPathLower.contains("\\")) {
			return false;
		}

		// Use decoded path (from normalize()) for allowlist comparison
		String path = uri.getPath();
		if (path == null) {
			path = "/";
		}

		for (String allowedPath : allowedPaths) {
			if (allowedPath.endsWith("/")) {
				if (path.startsWith(allowedPath)) {
					return true;
				}
			} else {
				if (path.equals(allowedPath)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Check if the UserInfo endpoint matches the configured OIDC issuer host.
	 * This allows Keycloak and other self-hosted OIDC providers.
	 * The endpoint must be on the same host/port/scheme as the configured issuer
	 * and the path must start with /realms/ (for Keycloak) or /protocol/ path.
	 */
	private boolean isAllowedByOidcIssuer(java.net.URI endpointUri, String host, int port, String scheme) {
		try {
			PropertyManager pm = getPropertyManager();
			if (pm == null) {
				return false;
			}
			String issuerUrl = pm.readValue(PropertyKey.OIDC_ISSUER);
			if (issuerUrl == null || issuerUrl.isEmpty()) {
				return false;
			}

			java.net.URI issuerUri = new java.net.URI(issuerUrl.trim());
			String issuerHost = issuerUri.getHost();
			if (issuerHost == null) {
				return false;
			}
			issuerHost = issuerHost.toLowerCase(java.util.Locale.ROOT);
			String issuerScheme = issuerUri.getScheme();
			if (issuerScheme == null) {
				return false;
			}
			issuerScheme = issuerScheme.toLowerCase(java.util.Locale.ROOT);
			int issuerPort = issuerUri.getPort();

			// Host must match exactly
			if (!host.equals(issuerHost)) {
				return false;
			}
			// Scheme must match
			if (!scheme.equals(issuerScheme)) {
				return false;
			}
			// Port must match (considering default ports)
			int effectivePort = port == -1 ? ("https".equals(scheme) ? 443 : 80) : port;
			int effectiveIssuerPort = issuerPort == -1 ? ("https".equals(issuerScheme) ? 443 : 80) : issuerPort;
			if (effectivePort != effectiveIssuerPort) {
				return false;
			}

			// Path must match a known OIDC endpoint under the issuer path
			String path = endpointUri.getPath();
			if (path == null) {
				return false;
			}
			String issuerPath = issuerUri.getPath();
			if (issuerPath == null) {
				issuerPath = "";
			}
			// Remove trailing slash for consistent matching
			if (issuerPath.endsWith("/")) {
				issuerPath = issuerPath.substring(0, issuerPath.length() - 1);
			}

			// Only allow known OIDC protocol endpoints (SSRF prevention)
			java.util.List<String> allowedSuffixes = java.util.Arrays.asList(
				"/protocol/openid-connect/userinfo",
				"/protocol/openid-connect/token",
				"/.well-known/openid-configuration"
			);
			for (String suffix : allowedSuffixes) {
				if (path.equals(issuerPath + suffix)) {
					logger.info("UserInfo endpoint allowed via OIDC issuer: {}", endpointUri);
					return true;
				}
			}

			return false;
		} catch (Exception e) {
			logger.debug("Error checking OIDC issuer for userinfo validation", e);
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
			java.net.URI uri = java.net.URI.create(normalized);
			// HTTPS is required for static allowlist hosts (Google, Microsoft).
			// For configured OIDC issuer hosts (e.g. Keycloak dev), HTTP is allowed
			// if the issuer itself uses HTTP (validated in isAllowedByOidcIssuer).

			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
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
	 * Discover the userinfo_endpoint from an OIDC issuer via OpenID Connect Discovery.
	 * Fetches {issuerUrl}/.well-known/openid-configuration and extracts "userinfo_endpoint".
	 * This is provider-agnostic (works with Keycloak, Google, Microsoft, Auth0, etc.).
	 *
	 * @param issuerUrl the OIDC issuer URL (e.g. "https://keycloak.example.com/realms/myrealm")
	 * @return the userinfo_endpoint URL, or null if discovery fails
	 */
	private String discoverUserInfoEndpoint(String issuerUrl) {
		if (issuerUrl == null || issuerUrl.trim().isEmpty()) {
			return null;
		}
		String normalized = issuerUrl.trim();
		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		String discoveryUrl = normalized + "/.well-known/openid-configuration";
		try {
			// SSRF prevention: validate discovery URL against allowed OIDC issuer hosts
			if (!isAllowedUserInfoEndpoint(discoveryUrl)) {
				logger.warn("OIDC Discovery URL not allowed: {}", discoveryUrl);
				return null;
			}

			java.net.URI uri = java.net.URI.create(discoveryUrl);
			java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);

			int responseCode = conn.getResponseCode();
			if (responseCode != 200) {
				logger.warn("OIDC Discovery returned HTTP {} for {}", responseCode, discoveryUrl);
				return null;
			}

			try (java.io.InputStream is = conn.getInputStream();
			     java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
				JSONParser parser = new JSONParser();
				JSONObject config = (JSONObject) parser.parse(reader);
				String endpoint = (String) config.get("userinfo_endpoint");
				if (endpoint != null && !endpoint.trim().isEmpty()) {
					return endpoint.trim();
				}
				logger.warn("OIDC Discovery response missing userinfo_endpoint field");
				return null;
			}
		} catch (Exception e) {
			logger.warn("OIDC Discovery failed for {}: {}", discoveryUrl, e.getMessage());
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
	 * Uses the same fallback pattern as UserItemResource.getOrCreateSystemSubFolder().
	 */
	private Folder getOrCreateUsersFolder(String repositoryId, ContentService contentService) {
		try {
			Folder systemFolder = contentService.getSystemFolder(repositoryId);

			// Fallback: search for .system folder directly in root children
			if (systemFolder == null) {
				logger.warn("SystemFolder not found via getSystemFolder(), searching in root children");
				String rootFolderId = getRootFolderIdForRepository(repositoryId);
				if (rootFolderId != null) {
					java.util.List<jp.aegif.nemaki.model.Content> rootChildren =
							contentService.getChildren(repositoryId, rootFolderId);
					if (rootChildren != null) {
						for (jp.aegif.nemaki.model.Content child : rootChildren) {
							if (".system".equals(child.getName()) && child instanceof Folder) {
								systemFolder = (Folder) child;
								logger.info("Found .system folder via root scan: {}", systemFolder.getId());
								break;
							}
						}
					}
				}
			}

			if (systemFolder == null) {
				logger.error("System folder not found for repository: {}", repositoryId);
				return null;
			}

			// Search for existing users folder
			java.util.List<jp.aegif.nemaki.model.Content> children =
					contentService.getChildren(repositoryId, systemFolder.getId());
			if (children != null) {
				for (jp.aegif.nemaki.model.Content child : children) {
					if ("users".equals(child.getName()) && child instanceof Folder) {
						return (Folder) child;
					}
				}
			}

			// Create users folder
			logger.info("Creating users folder under system folder for repository: {}", repositoryId);
			org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl properties =
				new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl("cmis:name", "users"));
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
			properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));

			return contentService.createFolder(
				new SystemCallContext(repositoryId),
				repositoryId,
				properties,
				systemFolder,
				null, null, null, null
			);
		} catch (Exception e) {
			logger.error("Failed to get or create users folder: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Get root folder ID for the specified repository.
	 */
	private String getRootFolderIdForRepository(String repositoryId) {
		switch (repositoryId) {
			case "bedroom":
				return "e02f784f8360a02cc14d1314c10038ff";
			case "canopy":
				return "ddd70e3ed8b847c2a364be81117c57ae";
			default:
				logger.warn("Unknown repository ID for root folder lookup: {}", repositoryId);
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

	/**
	 * SECURITY: Check if the authenticated user is authorized to access another user's resources.
	 * Only the user themselves or an admin can access user-specific endpoints.
	 */
	private boolean isAuthorizedForUser(CallContext callContext, String targetUserName) {
		if (callContext == null) {
			return false;
		}
		String authenticatedUser = callContext.getUsername();
		if (authenticatedUser == null) {
			return false;
		}
		// Allow if accessing own resources
		if (authenticatedUser.equals(targetUserName)) {
			return true;
		}
		// Allow if admin
		Boolean isAdmin = (Boolean) callContext.get("is_admin");
		return isAdmin != null && isAdmin;
	}

	private jp.aegif.nemaki.cmis.factory.auth.AuthenticationService getAuthenticationService() {
		try {
			return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
				.getBean("AuthenticationService", jp.aegif.nemaki.cmis.factory.auth.AuthenticationService.class);
		} catch (Exception e) {
			logger.error("Failed to retrieve AuthenticationService from Spring context", e);
		}
		return null;
	}
}
