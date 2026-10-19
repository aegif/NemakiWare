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

	/** Verifies the generic OIDC login's ID token (3.4.1, zero-trust P0). */
	private OidcLogin oidcLogin = new OidcLogin();
	
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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("AuthTokenResource.logout called for user: {} in repository: {}",
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
				logger.debug("Token invalidated for user: {} in repository: {}", userName, repositoryId);
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

		logger.debug("Logout successful for user: {}", userName);

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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("AuthTokenResource.login called for user: {} in repository: {}", 
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
					Object passwordObj = bodyJson.get("password");
					if (passwordObj instanceof String) {
						password = (String) passwordObj;
					} else if (passwordObj != null) {
						password = passwordObj.toString();
					}
				} catch (ParseException e) {
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
			// The brute-force throttle keys on the client IP, and it reads it from here. Without this the
			// key was "<repo>:<user>:unknown" for every caller, so 15 failures against a name — anyone's,
			// from anywhere — locked that account out for everyone (external review S5, 3.4.1).
			// "throttleRequest" and not "httpServletRequest": the SSO proxy-header check falls back to
			// the latter, so using it would also turn proxy-header authentication on for this door
			// (round-5 review).
			((org.apache.chemistry.opencmis.server.impl.CallContextImpl) ctxt).put(
				"throttleRequest", request);

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
			logger.debug("Login successful for user: {}", userName);
			
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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("SAML token conversion requested for repository: {}", repositoryId);

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

			// Load SAML configuration from PropertyManager
			PropertyManager pm = getPropertyManager();
			if (pm == null) {
				addErrMsg(errMsg, "saml", "PropertyManager not available");
				return makeResult(false, result, errMsg).toString();
			}

			String idpCertPem = pm.readValue(repositoryId, PropertyKey.SAML_IDP_CERTIFICATE);
			if (StringUtils.isBlank(idpCertPem)) {
				addErrMsg(errMsg, "saml", "IdP certificate not configured. Configure via Setup Wizard.");
				return makeResult(false, result, errMsg).toString();
			}

			String spEntityId = pm.readValue(repositoryId, PropertyKey.SAML_SP_ENTITY_ID);
			if (StringUtils.isBlank(spEntityId)) {
				spEntityId = "nemakiware-sp"; // default
			}

			// Parse IdP certificate
			java.security.cert.X509Certificate idpCert;
			try {
				idpCert = SamlSignatureVerifier.parseCertificate(idpCertPem);
			} catch (Exception e) {
				logger.error("Failed to parse IdP certificate", e);
				addErrMsg(errMsg, "saml", "Invalid IdP certificate");
				return makeResult(false, result, errMsg).toString();
			}

			// Base64 decode + optional DEFLATE decompression (10 MB limit to prevent DoS)
			byte[] decodedBytes = Base64.getDecoder().decode(samlResponse);
			byte[] xmlBytes;
			try {
				final int MAX_INFLATED_SIZE = 10 * 1024 * 1024; // 10 MB
				java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
				inflater.setInput(decodedBytes);
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				while ((n = inflater.inflate(buf)) > 0) {
					if (baos.size() + n > MAX_INFLATED_SIZE) {
						inflater.end();
						addErrMsg(errMsg, "saml", "SAML response exceeds 10 MB inflated size limit");
						return makeResult(false, result, errMsg).toString();
					}
					baos.write(buf, 0, n);
				}
				inflater.end();
				xmlBytes = baos.toByteArray();
				logger.debug("SAML response was deflate-compressed, inflated {} -> {} bytes",
						decodedBytes.length, xmlBytes.length);
			} catch (java.util.zip.DataFormatException e) {
				// DEFLATE failed.  Reject explicitly if the raw bytes look like
				// gzip (magic 1f 8b) or zip (50 4b 03 04) — those would never
				// parse as valid XML and could mask a compression bomb that
				// the inflater limit didn't catch.
				if (decodedBytes.length >= 2
						&& (decodedBytes[0] & 0xff) == 0x1f
						&& (decodedBytes[1] & 0xff) == 0x8b) {
					addErrMsg(errMsg, "saml", "SAML response uses gzip — only DEFLATE (RFC 1951) is accepted");
					return makeResult(false, result, errMsg).toString();
				}
				if (decodedBytes.length >= 4
						&& (decodedBytes[0] & 0xff) == 0x50
						&& (decodedBytes[1] & 0xff) == 0x4b
						&& (decodedBytes[2] & 0xff) == 0x03
						&& (decodedBytes[3] & 0xff) == 0x04) {
					addErrMsg(errMsg, "saml", "SAML response is a zip archive — not allowed");
					return makeResult(false, result, errMsg).toString();
				}
				xmlBytes = decodedBytes;
				logger.debug("SAML response was not deflate-compressed, using raw bytes");
			}

			// XXE-safe XML parsing via the shared hardened factory (SecureXml).
			DocumentBuilderFactory factory = jp.aegif.nemaki.util.xml.SecureXml.newSecureDocumentBuilderFactory();
			factory.setNamespaceAware(true);

			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new ByteArrayInputStream(xmlBytes));

			// Verify SAML signature, conditions, audience, replay, and the
			// browser-bound InResponseTo. Strict mode is opt-in via
			// saml.require.inResponseTo=true; when enabled, the SP-initiated
			// flow must have started with POST /rest/all/saml/initiate so
			// that the NEMAKI_SAML_BIND cookie is set. The cookie value
			// (HttpOnly, never reaches JavaScript) is the trust anchor that
			// ties this Response back to a request *this browser session*
			// initiated.
			boolean requireInResponseTo = Boolean.parseBoolean(
					String.valueOf(pm.readValue(repositoryId, PropertyKey.SAML_REQUIRE_IN_RESPONSE_TO)));
			String bindingToken = readBindingCookie(request);
			SamlSignatureVerifier.VerificationResult verifyResult =
					SamlSignatureVerifier.verify(document, idpCert, spEntityId, requireInResponseTo, bindingToken);
			if (!verifyResult.isValid()) {
				logger.warn("SAML signature verification failed: {}", verifyResult.getError());
				addErrMsg(errMsg, "saml", "SAML signature verification failed");
				return makeResult(false, result, errMsg).toString();
			}

			// Extract username only from the signed subtree (Response or Assertion)
			// to prevent unsigned sibling assertion injection attacks
			String attributeMapping = pm.readValue(repositoryId, PropertyKey.SAML_ATTRIBUTE_MAPPING);
			String userName = extractUserNameFromSAMLElement(verifyResult.getSignedElement(), attributeMapping);
			if (StringUtils.isBlank(userName)) {
				addErrMsg(errMsg, "userName", "couldNotExtract");
				return makeResult(false, result, errMsg).toString();
			}

			logger.debug("SAML authentication successful for user: {}", userName);

			// WHICH assertion this is, from the SIGNED subtree only — the same subtree the name comes from,
			// for the same reason (an unsigned sibling assertion must not be able to name a subject).
			String samlBinding = SsoLogin.binding(
					extractFromSAMLElement(verifyResult.getSignedElement(), "Issuer"),
					extractFromSAMLElement(verifyResult.getSignedElement(), "NameID"));
			UserItem userItem = accountForSsoLogin(repositoryId, userName, SsoLogin.SAML, samlBinding, errMsg);
			if (userItem == null) {
				return makeResult(false, result, errMsg).toString();
			}

			// Check if SAML/cloud authentication is allowed for this user
			// Read the policy from the account, not through a service lookup that let the login through when it
			// failed — the SAML arm (3.4.1, batch 18 review round 1; the same shape the OIDC arm was fixed to in
			// batch 16). No entry lock measures this arm yet: residual R173.
			if (!jp.aegif.nemaki.util.AuthenticationUtil.isAuthMethodAllowed(userItem, "cloud")) {
				logger.debug("SAML authentication denied for user {} (not in allowedAuthMethods)", userName);
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

			// Token issued successfully — only NOW commit the SAML state
			// (binding cookie consume + replay record). Earlier revisions
			// committed at verify() time, so a transient DAO/user/token
			// failure burned the SAML Response and forced the user back
			// through the IdP.
			SamlSignatureVerifier.PendingCommits pending = verifyResult.getPendingCommits();
			if (pending != null) {
				String commitError = pending.apply();
				if (commitError != null) {
					// Race condition: another concurrent caller committed
					// the same SAML state first. The token we just minted
					// has not been returned to the caller (no cookie, no
					// JSON body), so revoke it from the server's token
					// store before returning the error so it does not
					// linger in tokenMap until natural expiry.
					try {
						tokenService.invalidateToken(app, repositoryId, userName);
					} catch (Exception revokeError) {
						logger.warn("Failed to revoke orphaned SAML token for {} after commit race: {}",
								userName, revokeError.getMessage());
					}
					logger.warn("SAML commit race for user {}: {}", userName, commitError);
					addErrMsg(errMsg, "saml", "commitRace:" + commitError);
					return makeResult(false, result, errMsg).toString();
				}
			}

			setAuthTokenCookie(token.getToken(), repositoryId);

			JSONObject obj = new JSONObject();
			obj.put("app", app);
			obj.put("repositoryId", repositoryId);
			obj.put("userName", userName);
			obj.put("token", token.getToken());
			obj.put("expiration", token.getExpiration());
			result.put("value", obj);

			status = true;
			logger.debug("SAML token conversion successful for user: {}", userName);

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
	 * Convert an OIDC login to a NemakiWare auth token — on the ID token the repository's OIDC provider issued to this
	 * application, verified ({@link OidcLogin}).
	 *
	 * Required JSON body: { "id_token": "..." } (an "access_token" or "oidc_token" beside it is not read).
	 *
	 * It used to call a UserInfo endpoint — one the body could name, among a fixed list that included Google's and
	 * Microsoft's — with the access token the body carried, and to trust the answer, with OIDC enabled or not: a token
	 * the provider issued to any application logged in as its holder, and created a user for it (3.4.1, zero-trust P0).
	 */
	@POST
	@Path("/oidc/convert")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String convertOIDCToken(@PathParam("repositoryId") String repositoryId, String requestBody) {
		boolean status = false;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("OIDC token conversion requested for repository: {}", repositoryId);

		if (StringUtils.isBlank(repositoryId)) {
			addErrMsg(errMsg, "repositoryId", "isNull");
			return makeResult(false, result, errMsg).toString();
		}

		try {
			JSONParser parser = new JSONParser();
			JSONObject requestJson = (JSONObject) parser.parse(requestBody);
			Object idToken = requestJson.get("id_token");

			OidcLogin.Outcome outcome = oidcLogin.verify(OidcLogin.Settings.read(getPropertyManager(), repositoryId),
					idToken instanceof String ? (String) idToken : null);
			if (!outcome.verified()) {
				addErrMsg(errMsg, "oidc", outcome.refusal());
				return makeResult(false, result, errMsg).toString();
			}
			String userName = outcome.userName();

			logger.debug("OIDC authentication successful for user: {}", userName);

			// The account the token may act as: created for it, linked to its subject, or one an administrator allowed SSO
			// for — linked now (batch 16 review round 1). The name alone was enough, the built-in admin included.
			// The same create / adopt / link / read-back as the other three routes (R175 + R168): this arm had
			// the rule since batch 16 and the others had none, so the rule moved into SsoLogin and the write of
			// the binding gained a read-back here too.
			UserItem userItem = accountForSsoLogin(repositoryId, userName, SsoLogin.OIDC,
					OidcLogin.binding(outcome), errMsg);
			if (userItem == null) {
				return makeResult(false, result, errMsg).toString();
			}

			// Whether SSO is allowed for the account, read from the account itself: the service lookup this went through
			// let the login through when it failed (batch 16 review round 1: Codex P1).
			if (!jp.aegif.nemaki.util.AuthenticationUtil.isAuthMethodAllowed(userItem, "cloud")) {
				logger.debug("OIDC authentication denied for user {} (not in allowedAuthMethods)", userName);
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
			logger.debug("OIDC token conversion successful for user: {}", userName);

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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("Google token conversion requested for repository: {}", repositoryId);

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

			// Get Google client ID from configuration (repo-specific → global fallback)
			PropertyManager pm = getPropertyManager();
			String clientId = pm != null ? pm.readValue(repositoryId, PropertyKey.CLOUD_AUTH_GOOGLE_CLIENT_ID) : null;
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

			logger.debug("Google authentication successful for user: {}", userName);

			UserItem userItem = accountForSsoLogin(repositoryId, userName, SsoLogin.GOOGLE,
					SsoLogin.binding(payload.getIssuer(), payload.getSubject()), errMsg);
			if (userItem == null) {
				return makeResult(false, result, errMsg).toString();
			}

			// Check if cloud/Google authentication is allowed for this user
			// Read the policy from the account, not through a service lookup that let the login through when it
			// failed — the Google arm (3.4.1, batch 18 review round 1; the same shape the OIDC arm was fixed to in
			// batch 16). No entry lock measures this arm yet: residual R173.
			if (!jp.aegif.nemaki.util.AuthenticationUtil.isAuthMethodAllowed(userItem, "cloud")) {
				logger.debug("Google authentication denied for user {} (not in allowedAuthMethods)", userName);
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
			logger.debug("Google token conversion successful for user: {}", userName);

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
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		logger.debug("Microsoft token conversion requested for repository: {}", repositoryId);

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

			// Get Microsoft configuration (repo-specific → global fallback)
			PropertyManager pm = getPropertyManager();
			String clientId = pm != null ? pm.readValue(repositoryId, PropertyKey.CLOUD_AUTH_MICROSOFT_CLIENT_ID) : null;
			String tenantId = pm != null ? pm.readValue(repositoryId, PropertyKey.CLOUD_AUTH_MICROSOFT_TENANT_ID) : null;
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
				addErrMsg(errMsg, "id_token", "invalidOrExpired");
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

			logger.debug("Microsoft authentication successful for user: {}", userName);

			// oid, not sub: the object id is the account's own identifier in the tenant, stable across the
			// applications a user signs into, while sub is per-application.
			UserItem userItem = accountForSsoLogin(repositoryId, userName, SsoLogin.MICROSOFT,
					SsoLogin.binding(claims.getIssuer(), oid), errMsg);
			if (userItem == null) {
				return makeResult(false, result, errMsg).toString();
			}

			// Check if cloud/Microsoft authentication is allowed for this user
			// Read the policy from the account, not through a service lookup that let the login through when it
			// failed — the Microsoft arm (3.4.1, batch 18 review round 1; the same shape the OIDC arm was fixed to in
			// batch 16). No entry lock measures this arm yet: residual R173.
			if (!jp.aegif.nemaki.util.AuthenticationUtil.isAuthMethodAllowed(userItem, "cloud")) {
				logger.debug("Microsoft authentication denied for user {} (not in allowedAuthMethods)", userName);
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
			logger.debug("Microsoft token conversion successful for user: {}", userName);

		} catch (ParseException e) {
			logger.error("Failed to parse Microsoft request body", e);
			addErrMsg(errMsg, "requestBody", "invalidJson");
		} catch (Exception e) {
			logger.error("Microsoft token conversion failed", e);
			addErrMsg(errMsg, "microsoft", "conversionFailed");
		}

		return makeResult(status, result, errMsg).toString();
	}



	/**
	 * Extract username from the signed SAML subtree (Response or Assertion element).
	 * Only searches within the verified signed element to prevent unsigned
	 * sibling assertion injection attacks.
	 *
	 * Attribute mapping priority:
	 * 1. If attributeMapping is specified, look for that attribute name first
	 * 2. NameID
	 * 3. email / emailaddress claim
	 * 4. preferred_username
	 *
	 * @param signedElement    the signed Response or Assertion element
	 * @param attributeMapping optional custom attribute name for username extraction
	 * @return extracted username, or null if not found
	 */
	/**
	 * The text of the first {@code localName} element in the SIGNED subtree, or null.
	 *
	 * <p>For {@code Issuer} and {@code NameID}: together they are which identity provider said this and whom it
	 * named, which is the binding an account is linked to (R175). Read from the signed subtree only, for the
	 * same reason the user name is — an unsigned sibling assertion must not be able to name a subject.
	 */
	private String extractFromSAMLElement(Element signedElement, String localName) {
		try {
			NodeList nodes = signedElement.getElementsByTagNameNS(
					"urn:oasis:names:tc:SAML:2.0:assertion", localName);
			if (nodes.getLength() == 0) {
				return null;
			}
			String text = nodes.item(0).getTextContent();
			return text == null || text.isBlank() ? null : text.trim();
		} catch (Exception e) {
			logger.error("Failed to read " + localName + " from the signed SAML element", e);
			return null;
		}
	}

	private String extractUserNameFromSAMLElement(Element signedElement, String attributeMapping) {
		try {
			// 1. If custom attribute mapping is specified, try it first
			if (attributeMapping != null && !attributeMapping.trim().isEmpty() && !"NameID".equalsIgnoreCase(attributeMapping.trim())) {
				String mapped = findSAMLAttribute(signedElement, attributeMapping.trim());
				if (mapped != null) return mapped;
			}

			// 2. NameID (default)
			NodeList nameIdNodes = signedElement.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
			if (nameIdNodes.getLength() > 0) {
				String nameId = nameIdNodes.item(0).getTextContent();
				if (nameId != null && !nameId.trim().isEmpty()) return nameId.trim();
			}

			// 3. email attribute
			String email = findSAMLAttribute(signedElement, "email");
			if (email == null) {
				email = findSAMLAttribute(signedElement, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
			}
			if (email != null) return email;

			// 4. preferred_username
			String pu = findSAMLAttribute(signedElement, "preferred_username");
			if (pu != null) return pu;

			logger.warn("Could not extract username from SAML response");
			return null;
		} catch (Exception e) {
			logger.error("Failed to extract username from SAML element", e);
			return null;
		}
	}

	/**
	 * Find a SAML Attribute value by name within the given element subtree.
	 */
	private String findSAMLAttribute(Element element, String attributeName) {
		NodeList attributeNodes = element.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");
		for (int i = 0; i < attributeNodes.getLength(); i++) {
			Element attr = (Element) attributeNodes.item(i);
			String attrName = attr.getAttribute("Name");
			if (attributeName.equalsIgnoreCase(attrName) || attributeName.equals(attrName)) {
				NodeList valueNodes = attr.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue");
				if (valueNodes.getLength() > 0) {
					String value = valueNodes.item(0).getTextContent();
					if (value != null && !value.trim().isEmpty()) return value.trim();
				}
			}
		}
		return null;
	}

	/**
	 * The account an SSO login may act as — created, adopted or refused — or null with the reason already in
	 * {@code errMsg} (R175 + R168, 3.4.1).
	 *
	 * <p>Before this, three of the four routes (SAML, Google, Microsoft) took the account
	 * {@code getOrCreateUser} handed back and asked one question: {@code isAuthMethodAllowed(account, "cloud")},
	 * which answers TRUE for an account that lists no method at all. So the built-in {@code admin}, the service
	 * accounts and every account made with a password were enterable by anyone the IdP let choose that name
	 * (R175). The generic OIDC route asked {@link SsoLogin#refusal} — but that rule read 「{@code cloud} is
	 * listed」 as 「an administrator allowed SSO」, and {@code cloud} is written by these very routes and by the
	 * cloud directory sync, which also writes it onto accounts that already exist (R168).
	 *
	 * <p>What the read-back does and does not prove: it catches a save that returns WITHOUT storing the binding.
	 * It does not undo the cache poisoning of residual R196 — if {@code applyUserUpdate} refuses by throwing,
	 * this method does not return at all and the login fails on that exception (fail-closed), while the cached
	 * instance keeps the binding until it is evicted.
	 */
	private UserItem accountForSsoLogin(String repositoryId, String userName, String route, String binding,
			org.json.simple.JSONArray errMsg) {
		ContentService contentService = getContentService();
		if (contentService == null) {
			logger.error("ContentService not available");
			addErrMsg(errMsg, "user", "couldNotCreateOrFind");
			return null;
		}

		UserItem account = contentService.getUserItemById(repositoryId, userName);
		if (account == null) {
			UserItem created = getOrCreateUser(repositoryId, userName, route, binding);
			if (created == null) {
				addErrMsg(errMsg, "user", "couldNotCreateOrFind");
			}
			return created;
		}

		String refused = SsoLogin.refusal(account, route, binding);
		if (refused != null) {
			logger.info("{} login refused for an existing account: {}", SsoLogin.label(route), refused);
			addErrMsg(errMsg, route, refused);
			return null;
		}

		if (binding != null && !binding.isBlank() && SsoLogin.unlinked(account, route)) {
			account.setSubTypeProperties(SsoLogin.with(account, SsoLogin.subjectKey(route), binding));
			contentService.applyUserUpdate(repositoryId, account, userName);
			UserItem stored = contentService.getUserItemById(repositoryId, userName);
			String storedBinding = stored == null ? null : SsoLogin.boundSubject(stored, route);
			if (!binding.equals(storedBinding)) {
				logger.warn("{} login refused: the binding of {} was not stored (read back: {})",
						SsoLogin.label(route), userName, storedBinding);
				addErrMsg(errMsg, route, "the account's link to this identity could not be stored");
				return null;
			}
		}
		return account;
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
	 * - allowedAuthMethods: "cloud" (cloud-only authentication)
	 *
	 * @param repositoryId Repository ID
	 * @param userName User name extracted from SSO token
	 * @return UserItem object (existing or newly created), or null if creation failed
	 *
	 * <p>A user this creates is linked to the subject given, if any.
	 *
	 * <p>A user this creates records WHICH ROUTE made it ({@code nemaki:authRoute}), because {@code cloud} in
	 * {@code nemaki:allowedAuthMethods} does not mean 「an administrator allowed SSO」 — these creators write it
	 * themselves, and the generic-OIDC login used to read it as permission to adopt the account (R168). The mark is
	 * what lets {@link SsoLogin#refusal} tell 「a person set this」 from 「a login path set this」.
	 */
	private UserItem getOrCreateUser(String repositoryId, String userName, String route, String subject) {
		try {
			ContentService contentService = getContentService();
			if (contentService == null) {
				logger.error("ContentService not available");
				return null;
			}

			// Check if user already exists
			UserItem userItem = contentService.getUserItemById(repositoryId, userName);
			if (userItem != null) {
				logger.debug("Found existing user: {}", userName);
				return userItem;
			}

			// User not found - create new user for SSO
			logger.debug("User {} not found, creating new user for SSO auto-provisioning", userName);

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

			// Set allowedAuthMethods to "cloud" for SSO-provisioned users
			java.util.List<jp.aegif.nemaki.model.Property> subTypeProperties = new java.util.ArrayList<>();
			subTypeProperties.add(new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "cloud"));
			if (route != null) {
				subTypeProperties.add(new jp.aegif.nemaki.model.Property(SsoLogin.ROUTE_KEY, route));
			}
			if (subject != null) {
				subTypeProperties.add(new jp.aegif.nemaki.model.Property(
						SsoLogin.subjectKey(route == null ? SsoLogin.OIDC : route), subject));
			}
			newUser.setSubTypeProperties(subTypeProperties);

			// Create user in repository
			UserItem createdUser = contentService.createUserItem(
				new SystemCallContext(repositoryId),
				repositoryId,
				newUser
			);

			if (createdUser != null) {
				logger.debug("Successfully created SSO user: {} (id: {}, allowedAuthMethods: cloud)", userName, createdUser.getId());
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
								logger.debug("Found .system folder via root scan: {}", systemFolder.getId());
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
			// Same find-or-create rule as ContentServiceImpl.getOrCreateSystemSubFolder: a
			// short listing may be hiding the very folder this scan is looking for, and
			// creating a second one on that blindness is the duplicate-.system shape again.
			if (contentService.lastUnreadableChildCount() > 0) {
				throw new IllegalStateException("the .system folder's listing is incomplete ("
						+ contentService.lastUnreadableChildCount() + " child row(s) could not"
						+ " be read), so whether a 'users' folder already exists is unknown;"
						+ " refusing to create a possible duplicate");
			}
			if (children != null) {
				for (jp.aegif.nemaki.model.Content child : children) {
					if ("users".equals(child.getName()) && child instanceof Folder) {
						return (Folder) child;
					}
				}
			}

			// Create users folder
			logger.debug("Creating users folder under system folder for repository: {}", repositoryId);
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
	 * Uses RepositoryInfoMap for dynamic lookup from repositories.yml.
	 */
	private String getRootFolderIdForRepository(String repositoryId) {
		try {
			jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repoInfoMap =
				jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
					.getBean("repositoryInfoMap", jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
			if (repoInfoMap != null) {
				org.apache.chemistry.opencmis.commons.data.RepositoryInfo repoInfo = repoInfoMap.get(repositoryId);
				if (repoInfo != null && repoInfo.getRootFolderId() != null) {
					logger.debug("Root folder ID from RepositoryInfoMap: {} for repository: {}", repoInfo.getRootFolderId(), repositoryId);
					return repoInfo.getRootFolderId();
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to get root folder ID from RepositoryInfoMap for repository: {}", repositoryId);
		}
		logger.warn("Root folder ID not found for repository: {}", repositoryId);
		return null;
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
	/**
	 * Read the SP-issued SAML binding cookie (set by SamlInitiateServlet).
	 * Returns null if no such cookie is present — verifier handles the
	 * non-strict case gracefully.
	 */
	private String readBindingCookie(HttpServletRequest request) {
		if (request == null) return null;
		jakarta.servlet.http.Cookie[] cookies = request.getCookies();
		if (cookies == null) return null;
		for (jakarta.servlet.http.Cookie c : cookies) {
			if (SamlAuthnRequestRegistry.BINDING_COOKIE_NAME.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}

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

		// Set Secure flag when the connection is HTTPS (direct or behind reverse proxy).
		// Checks both request.isSecure() and X-Forwarded-Proto header to support
		// TLS termination at load balancer/reverse proxy (e.g. nginx, AWS ALB, Ingress).
		if (request != null && isSecureConnection(request)) {
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

		// Secure flag must match the original cookie for proper deletion
		if (request != null && isSecureConnection(request)) {
			cookieBuilder.append("; Secure");
		}

		// SameSite must match the original cookie for proper deletion
		cookieBuilder.append("; SameSite=Strict");

		// Use addHeader to avoid overwriting other Set-Cookie headers
		response.addHeader("Set-Cookie", cookieBuilder.toString());

		logger.debug("Auth token cookie cleared");
	}

	/**
	 * Determine if the current connection is secure (HTTPS).
	 *
	 * <p>Delegates to {@link jp.aegif.nemaki.util.TrustedProxyResolver#shouldFlagCookiesSecure}.
	 * Under {@code nemakiware.public.scheme=https} this always returns
	 * true so that a proxy hop that drops {@code X-Forwarded-Proto}
	 * cannot silently serve a non-Secure auth-token cookie over an HTTPS
	 * public URL. The misconfig itself is surfaced via
	 * {@link #warnIfPublicSchemeMisconfigured}.</p>
	 */
	private boolean isSecureConnection(jakarta.servlet.http.HttpServletRequest req) {
		jp.aegif.nemaki.util.PropertyManager pm = getPropertyManager();
		boolean secure = jp.aegif.nemaki.util.TrustedProxyResolver.shouldFlagCookiesSecure(req, pm);
		warnIfPublicSchemeMisconfigured(req, pm);
		return secure;
	}

	/** One warn per minute when public scheme is https but request isSecure()=false. */
	private static final java.util.concurrent.atomic.AtomicLong LAST_PUBLIC_SCHEME_WARN_AT = new java.util.concurrent.atomic.AtomicLong(0);

	private void warnIfPublicSchemeMisconfigured(jakarta.servlet.http.HttpServletRequest req,
			jp.aegif.nemaki.util.PropertyManager pm) {
		if (!jp.aegif.nemaki.util.TrustedProxyResolver.isPublicSchemeMisconfigured(req, pm)) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = LAST_PUBLIC_SCHEME_WARN_AT.get();
		if (now - last < 60_000L) {
			return;
		}
		if (LAST_PUBLIC_SCHEME_WARN_AT.compareAndSet(last, now)) {
			logger.warn("nemakiware.public.scheme=https but request.isSecure()=false on auth path "
					+ "(remote={}). The proxy in front of NemakiWare is not propagating the TLS "
					+ "scheme to Tomcat. Configure RemoteIpValve to trust the proxy IP, or set "
					+ "X-Forwarded-Proto from the proxy. Cookies were Secure-flagged anyway to "
					+ "avoid silent downgrade.", req == null ? "?" : req.getRemoteAddr());
		}
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
				logger.debug("TokenService retrieved from Spring context via fallback mechanism");
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
		Boolean isAdmin = (Boolean) callContext.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN);
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
