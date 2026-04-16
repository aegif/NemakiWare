package jp.aegif.nemaki.rest;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;

import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.util.spring.SpringContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * WebAuthn (Passkey) REST API endpoints.
 * Handles passkey registration and authentication flows.
 */
@Path("/repo/{repositoryId}/webauthn")
public class WebAuthnResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(WebAuthnResource.class);
	private static final ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new Jdk8Module());

	// Challenge store with TTL (5 minutes) and size cap.
	//
	// /webauthn/authenticate/begin is unauthenticated (see
	// AuthenticationFilter), so a remote attacker could otherwise
	// flood this map and exhaust heap.  CHALLENGE_STORE_MAX_SIZE
	// caps the entry count; when reached, expired entries are
	// purged first, and if still full the oldest entries are
	// evicted to make room.  Legitimate concurrent registrations
	// rarely exceed a few hundred at once.
	private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000;
	private static final int CHALLENGE_STORE_MAX_SIZE = 10_000;
	private static final ConcurrentHashMap<String, ChallengeData> challengeStore = new ConcurrentHashMap<>();

	@Context
	private HttpServletRequest request;

	@Context
	private HttpServletResponse response;

	private ContentDaoService contentDaoService;
	private TokenService tokenService;

	public WebAuthnResource() {
		super();
	}

	private ContentDaoService getContentDaoService() {
		if (contentDaoService != null) return contentDaoService;
		try {
			contentDaoService = SpringContext.getApplicationContext()
					.getBean("ContentDaoService", ContentDaoService.class);
		} catch (Exception e) {
			log.error("Failed to get ContentDaoService: " + e.getMessage());
		}
		return contentDaoService;
	}

	private TokenService getTokenService() {
		if (tokenService != null) return tokenService;
		try {
			tokenService = SpringContext.getApplicationContext()
					.getBean("tokenService", TokenService.class);
		} catch (Exception e) {
			log.error("Failed to get TokenService: " + e.getMessage());
		}
		return tokenService;
	}

	private RelyingParty createRelyingParty(String repositoryId) {
		// Determine RP ID and origin from request
		String rpId = request.getServerName();
		Set<String> origins = new HashSet<>();
		String scheme = request.getScheme();
		int port = request.getServerPort();
		if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
			origins.add(scheme + "://" + rpId);
		} else {
			origins.add(scheme + "://" + rpId + ":" + port);
		}

		RelyingPartyIdentity rpIdentity = RelyingPartyIdentity.builder()
				.id(rpId)
				.name("NemakiWare")
				.build();

		return RelyingParty.builder()
				.identity(rpIdentity)
				.credentialRepository(new NemakiCredentialRepository(repositoryId))
				.origins(origins)
				.build();
	}

	// ==========================================
	// Registration endpoints
	// ==========================================

	@POST
	@Path("/register/begin")
	@Produces(MediaType.APPLICATION_JSON)
	public String registerBegin(@PathParam("repositoryId") String repositoryId) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			// Get authenticated user from CallContext
			String userId = getCallContextUsername(request);
			if (userId == null) {
				addErrMsg(errMsg, "auth", "notAuthenticated");
				return makeResult(false, result, errMsg).toString();
			}

			// Check allowedAuthMethods: passkey requires password auth to be allowed
			ContentDaoService dao = getContentDaoService();
			if (dao != null) {
				jp.aegif.nemaki.model.UserItem userItem = dao.getUserItemById(repositoryId, userId);
				if (userItem != null) {
					String allowedAuthMethods = null;
					for (jp.aegif.nemaki.model.Property p : userItem.getSubTypeProperties()) {
						if ("nemaki:allowedAuthMethods".equals(p.getKey())) {
							allowedAuthMethods = (String) p.getValue();
							break;
						}
					}
					// "null" string = not set in DB (all methods allowed, same as null/empty)
					if (allowedAuthMethods != null && !allowedAuthMethods.isEmpty()
							&& !"null".equals(allowedAuthMethods)) {
						if ("disabled".equalsIgnoreCase(allowedAuthMethods.trim())) {
							addErrMsg(errMsg, "registration", "Authentication is disabled for this user");
							return makeResult(false, result, errMsg).toString();
						}
						boolean passwordAllowed = false;
						for (String method : allowedAuthMethods.split(",")) {
							if ("password".equalsIgnoreCase(method.trim())) {
								passwordAllowed = true;
								break;
							}
						}
						if (!passwordAllowed) {
							addErrMsg(errMsg, "registration", "Passkey registration is not allowed for cloud-only users");
							return makeResult(false, result, errMsg).toString();
						}
					}
				}
			}

			RelyingParty rp = createRelyingParty(repositoryId);

			// Create user identity
			byte[] userHandle = userId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			UserIdentity userIdentity = UserIdentity.builder()
					.name(userId)
					.displayName(userId)
					.id(new ByteArray(userHandle))
					.build();

			// Start registration
			StartRegistrationOptions options = StartRegistrationOptions.builder()
					.user(userIdentity)
					.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
							.residentKey(ResidentKeyRequirement.PREFERRED)
							.userVerification(UserVerificationRequirement.PREFERRED)
							.build())
					.build();

			PublicKeyCredentialCreationOptions creationOptions = rp.startRegistration(options);

			// Store challenge
			String challengeB64 = creationOptions.getChallenge().getBase64Url();
			challengeStore.put(challengeB64, new ChallengeData(
					userId, creationOptions, null, System.currentTimeMillis()));

			// Clean up expired challenges (protect the one we just created)
			cleanupExpiredChallenges(challengeB64);

			// Serialize to JSON
			String optionsJson = objectMapper.writeValueAsString(creationOptions);
			result.put("options", new JSONParser().parse(optionsJson));
			return makeResult(true, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error starting WebAuthn registration", e);
			addErrMsg(errMsg, "registration", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	@POST
	@Path("/register/complete")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String registerComplete(@PathParam("repositoryId") String repositoryId, String requestBody) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			String userId = getCallContextUsername(request);
			if (userId == null) {
				addErrMsg(errMsg, "auth", "notAuthenticated");
				return makeResult(false, result, errMsg).toString();
			}

			// Parse the client response
			JSONParser parser = new JSONParser();
			JSONObject bodyJson = (JSONObject) parser.parse(requestBody);
			String credentialJson = objectMapper.writeValueAsString(bodyJson.get("credential"));
			String displayName = (String) bodyJson.get("displayName");
			if (displayName == null || displayName.isEmpty()) {
				displayName = "Passkey " + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
			}

			PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
					PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

			// Extract the challenge from the client data to find the matching challenge entry
			String clientDataJsonStr = new String(pkc.getResponse().getClientDataJSON().getBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			JSONObject clientDataObj = (JSONObject) parser.parse(clientDataJsonStr);
			String challengeFromResponse = (String) clientDataObj.get("challenge");

			// Look up challenge directly by key
			ChallengeData challengeData = challengeStore.remove(challengeFromResponse);
			if (challengeData == null || challengeData.creationOptions == null) {
				// Fall back: try finding by userId (most recent)
				challengeData = null;
				String challengeKey = null;
				long latestTimestamp = 0;
				for (Map.Entry<String, ChallengeData> entry : challengeStore.entrySet()) {
					ChallengeData cd = entry.getValue();
					if (userId.equals(cd.userId) && cd.creationOptions != null && cd.timestamp > latestTimestamp) {
						challengeData = cd;
						challengeKey = entry.getKey();
						latestTimestamp = cd.timestamp;
					}
				}
				if (challengeKey != null) {
					challengeStore.remove(challengeKey);
				}
			}
			if (challengeData == null || !userId.equals(challengeData.userId)) {
				addErrMsg(errMsg, "registration", "challengeNotFound");
				return makeResult(false, result, errMsg).toString();
			}

			RelyingParty rp = createRelyingParty(repositoryId);

			// Finish registration
			RegistrationResult registrationResult = rp.finishRegistration(
					FinishRegistrationOptions.builder()
							.request(challengeData.creationOptions)
							.response(pkc)
							.build());

			// Save credential to CouchDB
			WebAuthnCredential credential = new WebAuthnCredential();
			credential.setType("cmis:item");
			credential.setObjectType("nemaki:webauthnCredential");
			credential.setUserId(userId);
			credential.setCredentialId(registrationResult.getKeyId().getId().getBase64Url());
			credential.setPublicKeyCose(registrationResult.getPublicKeyCose().getBase64Url());
			credential.setSignCount(registrationResult.getSignatureCount());
			credential.setAaguid(registrationResult.getAaguid().getHex());
			credential.setDisplayName(displayName);
			credential.setDiscoverable(registrationResult.isDiscoverable().orElse(false));
			credential.setCreatedAt(new GregorianCalendar());

			// Extract transports
			List<String> transports = pkc.getResponse().getTransports()
					.stream()
					.map(t -> t.getId())
					.collect(Collectors.toList());
			credential.setTransports(transports);

			getContentDaoService().createWebAuthnCredential(repositoryId, credential);

			result.put("credentialId", credential.getCredentialId());
			result.put("displayName", credential.getDisplayName());
			return makeResult(true, result, errMsg).toString();
		} catch (RegistrationFailedException e) {
			log.error("WebAuthn registration verification failed", e);
			addErrMsg(errMsg, "registration", "verificationFailed");
			return makeResult(false, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error completing WebAuthn registration", e);
			addErrMsg(errMsg, "registration", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	// ==========================================
	// Authentication endpoints (no auth required)
	// ==========================================

	@POST
	@Path("/authenticate/begin")
	@Produces(MediaType.APPLICATION_JSON)
	public String authenticateBegin(@PathParam("repositoryId") String repositoryId) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			RelyingParty rp = createRelyingParty(repositoryId);

			// Start assertion (discoverable credential / conditional UI)
			AssertionRequest assertionRequest = rp.startAssertion(
					StartAssertionOptions.builder()
							.userVerification(UserVerificationRequirement.PREFERRED)
							.build());

			// Store challenge
			String challengeB64 = assertionRequest.getPublicKeyCredentialRequestOptions()
					.getChallenge().getBase64Url();
			challengeStore.put(challengeB64, new ChallengeData(
					null, null, assertionRequest, System.currentTimeMillis()));

			cleanupExpiredChallenges(challengeB64);

			String optionsJson = objectMapper.writeValueAsString(
					assertionRequest.getPublicKeyCredentialRequestOptions());
			result.put("options", new JSONParser().parse(optionsJson));
			return makeResult(true, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error starting WebAuthn authentication", e);
			addErrMsg(errMsg, "authentication", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	@POST
	@Path("/authenticate/complete")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String authenticateComplete(@PathParam("repositoryId") String repositoryId, String requestBody) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			// Parse client response
			JSONParser parser = new JSONParser();
			JSONObject bodyJson = (JSONObject) parser.parse(requestBody);
			String credentialJson = objectMapper.writeValueAsString(bodyJson.get("credential"));

			PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc =
					PublicKeyCredential.parseAssertionResponseJson(credentialJson);

			// Extract the challenge from the client data to find the matching challenge entry
			String clientDataJsonStr = new String(pkc.getResponse().getClientDataJSON().getBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			JSONObject clientDataObj = (JSONObject) parser.parse(clientDataJsonStr);
			String challengeFromResponse = (String) clientDataObj.get("challenge");

			// Look up challenge directly by key
			ChallengeData challengeData = challengeStore.remove(challengeFromResponse);
			if (challengeData == null || challengeData.assertionRequest == null) {
				addErrMsg(errMsg, "authentication", "challengeNotFound");
				return makeResult(false, result, errMsg).toString();
			}

			RelyingParty rp = createRelyingParty(repositoryId);

			// Finish assertion
			AssertionResult assertionResult = rp.finishAssertion(
					FinishAssertionOptions.builder()
							.request(challengeData.assertionRequest)
							.response(pkc)
							.build());

			if (!assertionResult.isSuccess()) {
				addErrMsg(errMsg, "authentication", "assertionFailed");
				return makeResult(false, result, errMsg).toString();
			}

			// Update sign count
			String credentialIdB64 = assertionResult.getCredentialId().getBase64Url();
			WebAuthnCredential storedCredential = getContentDaoService()
					.getWebAuthnCredentialByCredentialId(repositoryId, credentialIdB64);
			if (storedCredential != null) {
				storedCredential.setSignCount(assertionResult.getSignatureCount());
				getContentDaoService().updateWebAuthnCredential(repositoryId, storedCredential);
			}

			// Issue auth token (same as password login)
			String userId = assertionResult.getUsername();
			if (userId == null && storedCredential != null) {
				userId = storedCredential.getUserId();
			}

			if (userId == null) {
				addErrMsg(errMsg, "authentication", "userNotFound");
				return makeResult(false, result, errMsg).toString();
			}

			// Check allowedAuthMethods before issuing token
			// Passkey is an extension of password auth, so check for "password" permission
			ContentDaoService dao = getContentDaoService();
			if (dao != null) {
				jp.aegif.nemaki.model.UserItem userItem = dao.getUserItemById(repositoryId, userId);
				if (userItem != null) {
					String allowedAuthMethods = null;
					for (jp.aegif.nemaki.model.Property p : userItem.getSubTypeProperties()) {
						if ("nemaki:allowedAuthMethods".equals(p.getKey())) {
							allowedAuthMethods = (String) p.getValue();
							break;
						}
					}
					// "disabled" means no authentication allowed at all
					if (allowedAuthMethods != null && "disabled".equalsIgnoreCase(allowedAuthMethods.trim())) {
						log.warn("WebAuthn authentication rejected: user " + userId + " has authentication disabled");
						addErrMsg(errMsg, "authentication", "accountDisabled");
						return makeResult(false, result, errMsg).toString();
					}
					// If allowedAuthMethods is set and does not include "password", reject
					if (allowedAuthMethods != null && !allowedAuthMethods.isEmpty() && !"null".equals(allowedAuthMethods)) {
						boolean passwordAllowed = false;
						for (String m : allowedAuthMethods.split(",")) {
							if ("password".equalsIgnoreCase(m.trim())) {
								passwordAllowed = true;
								break;
							}
						}
						if (!passwordAllowed) {
							log.warn("WebAuthn authentication rejected: user " + userId +
									" does not have password auth allowed (allowedAuthMethods=" + allowedAuthMethods + ")");
							addErrMsg(errMsg, "authentication", "authMethodNotAllowed");
							return makeResult(false, result, errMsg).toString();
						}
					}
				}
			}

			TokenService ts = getTokenService();
			if (ts == null) {
				addErrMsg(errMsg, "tokenService", "notAvailable");
				return makeResult(false, result, errMsg).toString();
			}

			String app = "";
			Token token = ts.setToken(app, repositoryId, userId);

			// Set HttpOnly cookie
			setAuthTokenCookie(token.getToken(), repositoryId);

			JSONObject tokenObj = new JSONObject();
			tokenObj.put("app", app);
			tokenObj.put("repositoryId", repositoryId);
			tokenObj.put("userName", userId);
			tokenObj.put("token", token.getToken());
			tokenObj.put("expiration", token.getExpiration());
			result.put("value", tokenObj);

			return makeResult(true, result, errMsg).toString();
		} catch (AssertionFailedException e) {
			log.error("WebAuthn assertion verification failed", e);
			addErrMsg(errMsg, "authentication", "verificationFailed");
			return makeResult(false, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error completing WebAuthn authentication", e);
			addErrMsg(errMsg, "authentication", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	// ==========================================
	// Credential management endpoints
	// ==========================================

	@GET
	@Path("/credentials")
	@Produces(MediaType.APPLICATION_JSON)
	public String listCredentials(@PathParam("repositoryId") String repositoryId) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			String userId = getCallContextUsername(request);
			if (userId == null) {
				addErrMsg(errMsg, "auth", "notAuthenticated");
				return makeResult(false, result, errMsg).toString();
			}

			List<WebAuthnCredential> credentials = getContentDaoService()
					.getWebAuthnCredentialsByUserId(repositoryId, userId);

			JSONArray credArray = new JSONArray();
			for (WebAuthnCredential cred : credentials) {
				JSONObject credJson = new JSONObject();
				credJson.put("id", cred.getId());
				credJson.put("credentialId", cred.getCredentialId());
				credJson.put("displayName", cred.getDisplayName());
				credJson.put("createdAt", cred.getCreatedAt() != null ?
						cred.getCreatedAt().getTimeInMillis() : null);
				credJson.put("transports", cred.getTransports());
				credArray.add(credJson);
			}
			result.put("credentials", credArray);
			return makeResult(true, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error listing WebAuthn credentials", e);
			addErrMsg(errMsg, "credentials", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	@DELETE
	@Path("/credentials/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String deleteCredential(@PathParam("repositoryId") String repositoryId,
	                               @PathParam("id") String id) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(request);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			String userId = getCallContextUsername(request);
			if (userId == null) {
				addErrMsg(errMsg, "auth", "notAuthenticated");
				return makeResult(false, result, errMsg).toString();
			}

			// Verify the credential belongs to this user
			List<WebAuthnCredential> userCredentials = getContentDaoService()
					.getWebAuthnCredentialsByUserId(repositoryId, userId);
			boolean found = false;
			for (WebAuthnCredential cred : userCredentials) {
				if (cred.getId().equals(id)) {
					found = true;
					break;
				}
			}
			if (!found) {
				addErrMsg(errMsg, "credential", "notFound");
				return makeResult(false, result, errMsg).toString();
			}

			getContentDaoService().deleteWebAuthnCredential(repositoryId, id);
			return makeResult(true, result, errMsg).toString();
		} catch (Exception e) {
			log.error("Error deleting WebAuthn credential", e);
			addErrMsg(errMsg, "credentials", e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}
	}

	// ==========================================
	// Cookie helper (same pattern as AuthTokenResource)
	// ==========================================

	private void setAuthTokenCookie(String token, String repositoryId) {
		if (response == null) {
			log.warn("HttpServletResponse not available, cannot set auth cookie");
			return;
		}
		StringBuilder cookieBuilder = new StringBuilder();
		cookieBuilder.append("nemaki_auth_token").append("=").append(token);
		cookieBuilder.append("; Path=/core");
		cookieBuilder.append("; Max-Age=").append(24 * 60 * 60);
		cookieBuilder.append("; HttpOnly");

		String serverName = request != null ? request.getServerName() : "";
		boolean isSecure = request != null && request.isSecure();
		if (isSecure || (!serverName.equals("localhost") && !serverName.equals("127.0.0.1"))) {
			cookieBuilder.append("; Secure");
		}
		cookieBuilder.append("; SameSite=Strict");

		response.addHeader("Set-Cookie", cookieBuilder.toString());
	}

	// ==========================================
	// Challenge data holder
	// ==========================================

	private static class ChallengeData {
		final String userId;
		final PublicKeyCredentialCreationOptions creationOptions;
		final AssertionRequest assertionRequest;
		final long timestamp;

		ChallengeData(String userId, PublicKeyCredentialCreationOptions creationOptions,
		              AssertionRequest assertionRequest, long timestamp) {
			this.userId = userId;
			this.creationOptions = creationOptions;
			this.assertionRequest = assertionRequest;
			this.timestamp = timestamp;
		}
	}

	/**
	 * Purge expired challenges, then enforce the hard size cap.
	 *
	 * @param protectedKey challenge key that was just created by the
	 *     current request — must NOT be evicted even if it falls into
	 *     the overflow window (may be null if called defensively)
	 */
	private void cleanupExpiredChallenges(String protectedKey) {
		long now = System.currentTimeMillis();
		challengeStore.entrySet().removeIf(entry ->
				(now - entry.getValue().timestamp) > CHALLENGE_TTL_MS);

		// Hard size cap.  Snapshot the size once; concurrent mutations can
		// only shrink it (put is done, other threads may also be cleaning),
		// so Math.max guards against negative limit().
		int currentSize = challengeStore.size();
		if (currentSize > CHALLENGE_STORE_MAX_SIZE) {
			int evictCount = Math.max(0, currentSize - CHALLENGE_STORE_MAX_SIZE / 2);
			log.warn("WebAuthn challenge store exceeded " + CHALLENGE_STORE_MAX_SIZE
					+ " entries (" + currentSize + "), evicting " + evictCount + " oldest");
			challengeStore.entrySet().stream()
					.filter(e -> !e.getKey().equals(protectedKey))
					.sorted((a, b) -> {
						int cmp = Long.compare(a.getValue().timestamp, b.getValue().timestamp);
						// deterministic tie-break so burst requests with the
						// same millisecond don't nondeterministically evict
						// each other's challenges
						return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
					})
					.limit(evictCount)
					.map(Map.Entry::getKey)
					.toList()
					.forEach(challengeStore::remove);
		}
	}

	// ==========================================
	// CredentialRepository implementation for Yubico library
	// ==========================================

	private class NemakiCredentialRepository implements CredentialRepository {
		private final String repositoryId;

		NemakiCredentialRepository(String repositoryId) {
			this.repositoryId = repositoryId;
		}

		@Override
		public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
			List<WebAuthnCredential> credentials = getContentDaoService()
					.getWebAuthnCredentialsByUserId(repositoryId, username);
			Set<PublicKeyCredentialDescriptor> descriptors = new HashSet<>();
			for (WebAuthnCredential cred : credentials) {
				try {
					descriptors.add(PublicKeyCredentialDescriptor.builder()
							.id(ByteArray.fromBase64Url(cred.getCredentialId()))
							.build());
				} catch (Base64UrlException e) {
					log.warn("Invalid credentialId encoding for credential: " + cred.getId(), e);
				}
			}
			return descriptors;
		}

		@Override
		public Optional<ByteArray> getUserHandleForUsername(String username) {
			return Optional.of(new ByteArray(
					username.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}

		@Override
		public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
			String username = new String(userHandle.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
			// Verify user exists
			List<WebAuthnCredential> credentials = getContentDaoService()
					.getWebAuthnCredentialsByUserId(repositoryId, username);
			if (!credentials.isEmpty()) {
				return Optional.of(username);
			}
			return Optional.empty();
		}

		@Override
		public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
			String credIdB64 = credentialId.getBase64Url();
			WebAuthnCredential cred = getContentDaoService()
					.getWebAuthnCredentialByCredentialId(repositoryId, credIdB64);
			if (cred == null) {
				return Optional.empty();
			}
			try {
				return Optional.of(RegisteredCredential.builder()
						.credentialId(credentialId)
						.userHandle(userHandle)
						.publicKeyCose(ByteArray.fromBase64Url(cred.getPublicKeyCose()))
						.signatureCount(cred.getSignCount())
						.build());
			} catch (Base64UrlException e) {
				log.warn("Invalid publicKeyCose encoding for credential: " + cred.getId(), e);
				return Optional.empty();
			}
		}

		@Override
		public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
			String credIdB64 = credentialId.getBase64Url();
			WebAuthnCredential cred = getContentDaoService()
					.getWebAuthnCredentialByCredentialId(repositoryId, credIdB64);
			if (cred == null) {
				return Collections.emptySet();
			}
			try {
				ByteArray userHandle = new ByteArray(
						cred.getUserId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				return Collections.singleton(RegisteredCredential.builder()
						.credentialId(credentialId)
						.userHandle(userHandle)
						.publicKeyCose(ByteArray.fromBase64Url(cred.getPublicKeyCose()))
						.signatureCount(cred.getSignCount())
						.build());
			} catch (Base64UrlException e) {
				log.warn("Invalid publicKeyCose encoding for credential: " + cred.getId(), e);
				return Collections.emptySet();
			}
		}
	}
}
