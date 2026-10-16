/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.factory.auth.impl;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Authentication Service implementation.
 */
public class AuthenticationServiceImpl implements AuthenticationService {

	private static final Log log = LogFactory.getLog(AuthenticationServiceImpl.class);

	// Brute-force throttle for Basic-auth password logins (JVM-local, per
	// repo+user+IP). Failures only; a success clears the counter.
	private final jp.aegif.nemaki.security.LoginThrottle loginThrottle =
			new jp.aegif.nemaki.security.LoginThrottle();

	// CMIS has no session: credentials arrive on every request, so without this the server
	// re-runs BCrypt per request (measured at 96.8% of all CPU). Successes only, keyed on the
	// stored hash so a password change invalidates by itself.
	private final jp.aegif.nemaki.security.VerifiedPasswordCache passwordCache =
			new jp.aegif.nemaki.security.VerifiedPasswordCache();

	private ContentService contentService;
	private ContentDaoService contentDaoService;
	private PrincipalService principalService;
	private TokenService tokenService;
	private ApiKeyService apiKeyService;
	private PropertyManager propertyManager;
	private RepositoryInfoMap repositoryInfoMap;

	public boolean login(CallContext callContext) {
		if (log.isDebugEnabled()) {
			log.debug("Login method called at " + new java.util.Date());
		}
		
		String repositoryId = callContext.getRepositoryId();

		// Set flag of SuperUsers
		String suId = repositoryInfoMap.getSuperUsers().getId();
		((CallContextImpl)callContext).put(CallContextKey.IS_SU,
				suId.equals(repositoryId));

		// SSO
		if (loginWithExternalAuth(callContext)) {
			return true;
		}

		// API Key authentication (for MCP clients and cloud-only users)
		if (loginWithApiKey(callContext)) {
			return true;
		}

		// Token for Basic auth
		if (loginWithToken(callContext)) {
			return true;
		}

		// Basic auth
		return loginWithBasicAuth(callContext);
	}

	private boolean loginWithExternalAuth(CallContext callContext) {
		String repositoryId = callContext.getRepositoryId();

		String proxyHeaderKey = propertyManager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_PROXY_HEADER);
		if(StringUtils.isBlank(proxyHeaderKey)) return false;

		// Trusted proxy check: only accept the proxy header from configured IPs.
		// If trustedProxies is not configured, proxy header auth is disabled for safety.
		String trustedProxies = propertyManager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_TRUSTED_PROXIES);
		if (StringUtils.isBlank(trustedProxies)) {
			log.warn("Proxy header authentication is configured (proxyHeader=" + proxyHeaderKey + ") but external.authentication.trustedProxies is not set — rejecting for safety");
			return false;
		}
		String remoteAddr = (String) callContext.get("remoteAddress");
		if (remoteAddr == null) {
			// Fallback: try HttpServletRequest if available in context
			Object httpReq = callContext.get("httpServletRequest");
			if (httpReq instanceof jakarta.servlet.http.HttpServletRequest req) {
				remoteAddr = req.getRemoteAddr();
			}
		}
		if (remoteAddr == null) {
			log.warn("Proxy header auth rejected: remote address is unknown (not in CallContext) — cannot verify trusted proxy");
			return false;
		}
		if (!jp.aegif.nemaki.util.TrustedProxyResolver.isTrusted(remoteAddr, trustedProxies)) {
			log.warn("Proxy header auth rejected: remote address " + remoteAddr + " is not in trustedProxies");
			return false;
		}

		String proxyUserId = (String) callContext.get(proxyHeaderKey);
		if (StringUtils.isBlank(proxyUserId)) {
			return false;
		} else {
			UserItem userItem = contentService.getUserItemById(repositoryId, proxyUserId);
			if (userItem == null) {
				Boolean isAutoCreate = propertyManager.readBoolean(PropertyKey.EXTERNAL_AUTHENTICATION_AUTO_CREATE_USER);
				if(isAutoCreate){
					String parentFolderId = propertyManager.readValue(PropertyKey.CAPABILITY_EXTENDED_USER_ITEM_FOLDER);
					userItem = new UserItem(null, "nemaki:user", proxyUserId, proxyUserId, null, false, parentFolderId);
					contentDaoService.create(repositoryId, userItem);
				}else{
					return false;
				}
			} else {
				// Check if cloud/external authentication is allowed for this user
				if (!isAuthMethodAllowed(userItem, "cloud")) {
					log.info("External/cloud authentication denied for user " + proxyUserId + " (not in allowedAuthMethods)");
					return false;
				}
				boolean isAdmin = Boolean.TRUE.equals(userItem.isAdmin());
				setAdminFlagInContext(callContext, isAdmin);
			}
			log.debug("Header Authenticated. UserId=" + userItem.getUserId());
			return true;
		}
	}

	// trusted-proxy logic centralised in jp.aegif.nemaki.util.TrustedProxyResolver
	// (also used by AuditLogger.getClientIp). Keep them in lockstep so a future
	// trustedProxies policy update applies to both auth and audit consistently.

	private boolean loginWithToken(CallContext callContext) {
		String userName = callContext.getUsername();
		String token;
		if (callContext.get(CallContextKey.AUTH_TOKEN) == null) {
			return false;
		} else {
			token = (String) callContext.get(CallContextKey.AUTH_TOKEN);
			if (StringUtils.isBlank(token)) {
				return false;
			}
		}
		Object _app = callContext.get(CallContextKey.AUTH_TOKEN_APP);
		String app = (_app == null) ? "" : (String) _app;

		// If username is not set (e.g., token-only authentication from SSO/cloud auth),
		// resolve the username from the token via reverse lookup
		if (StringUtils.isBlank(userName)) {
			String resolvedUser = tokenService.validateToken(app, callContext.getRepositoryId(), token);
			if (resolvedUser != null) {
				userName = resolvedUser;
				// Set the username in the call context so downstream code can use it
				((CallContextImpl) callContext).put(CallContext.USERNAME, userName);
				log.info("Token-only auth: resolved username '" + userName + "' from token for repository: " + callContext.getRepositoryId());
			} else {
				log.warn("Token-only auth: could not resolve username from token for repository: " + callContext.getRepositoryId());
				return false;
			}
		}

		if (authenticateUserByToken(app, callContext.getRepositoryId(), userName, token)) {
			// The account the token was issued FOR must still be there, not disabled, and still the
			// same account document at the same generation (3.4.1, zero-trust P0 and R170). The
			// token itself is read here because validateToken answers with a name only, and a name
			// is what R170 is about: it can resolve to a different document than the one the token
			// was issued for.
			UserItem account = contentService.getUserItemById(callContext.getRepositoryId(), userName);
			Token issued = tokenService.getToken(app, callContext.getRepositoryId(), userName);
			AuthenticationUtil.CredentialBinding binding = AuthenticationUtil.credentialBinding(
					issued == null ? null : issued.getAccountId(),
					issued == null ? null : issued.getCredentialGeneration(),
					account, true);
			if (binding != AuthenticationUtil.CredentialBinding.OK) {
				log.info("Token authentication refused: " + binding);
				return false;
			}
			if (authenticateAdminByToken(callContext.getRepositoryId(), userName)) {
				setAdminFlagInContext(callContext, true);
			} else {
				setAdminFlagInContext(callContext, false);
			}
			return true;
		}


		return false;
	}

	private boolean loginWithBasicAuth(CallContext callContext) {
		String repositoryId = callContext.getRepositoryId();

		//Check repositoryId exists
		if(!repositoryInfoMap.contains(repositoryId)) return false;

		// Brute-force throttle: deny during lockout before checking the password.
		String username = callContext.getUsername();
		String throttleKey = throttleKey(repositoryId, username, resolveClientIpForThrottle(callContext));
		if (loginThrottle.isBlocked(throttleKey)) {
			log.warn("Login temporarily locked (too many failed attempts) for user '" + username
					+ "' in repository '" + repositoryId + "'");
			return false;
		}

		// Basic auth with id/password
		UserItem user = getAuthenticatedUserItem(repositoryId, username, callContext.getPassword());
		if (user == null) {
			// Count only genuine password attempts (non-blank id + password) as failures,
			// so ambient/empty probes don't accumulate.
			if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(callContext.getPassword())) {
				loginThrottle.recordFailure(throttleKey);
			}
			return false;
		}
		loginThrottle.recordSuccess(throttleKey);

		boolean isAdmin = Boolean.TRUE.equals(user.isAdmin());
		setAdminFlagInContext(callContext, isAdmin);
		return true;
	}

	/**
	 * Client IP for throttle keying; {@code "unknown"} if unavailable.
	 *
	 * <p>{@code "unknown"} is one key for everyone: 15 failures against a name from anywhere lock that
	 * name out for all callers (external review S5, 3.4.1). So every door that reaches
	 * {@link #loginWithBasicAuth} has to supply an address, and the round-5 review found two that did
	 * not. All four, measured:
	 *
	 * <ul>
	 * <li>{@code rest/AuthTokenResource} (the {@code /authtoken/{user}/login} door) and the two
	 *     authentication filters ({@code rest/AuthenticationFilter}, {@code api/v1/ApiAuthenticationFilter},
	 *     which together cover {@code /rest/**}, {@code /api/**} and {@code /odata/**}) put the request
	 *     under {@code throttleRequest} and are resolved here through
	 *     {@link jp.aegif.nemaki.util.TrustedProxyResolver} — the same helper {@code AuditLogger} uses, so
	 *     a forwarded-for header counts only from a configured proxy.</li>
	 * <li>The CMIS bindings reach this through {@code NemakiAuthCallContextHandler}, whose context map
	 *     holds strings only, so they supply the immediate peer under {@code remoteAddress} and get no
	 *     proxy awareness. That key is also the SSO proxy-header check's input, where it must stay the
	 *     peer — so it is not rewritten to the resolved client. It is read SECOND because the
	 *     proxy-aware answer is the better one when a door supplies both; no door does today
	 *     (round-6 review: the earlier wording gave the SSO check as the reason for the order, which
	 *     does not follow).</li>
	 * </ul>
	 *
	 * <p>The request is read from {@code throttleRequest} and not from {@code httpServletRequest}
	 * deliberately: {@link #loginWithExternalAuth} falls back to the latter for its trusted-proxy check,
	 * so putting the request there would also turn proxy-header SSO authentication ON for doors where it
	 * is currently inert — a widening that has nothing to do with counting failures.
	 *
	 * <p>What this does NOT fix: with {@code external.authentication.trustedProxies} unset — it has no
	 * default anywhere — the resolver returns the immediate peer, so behind a reverse proxy every caller
	 * is the proxy and the named lockout is still reachable from behind it. Recorded as a residual.
	 */
	private String resolveClientIpForThrottle(CallContext callContext) {
		try {
			Object req = callContext.get("throttleRequest");
			if (req instanceof jakarta.servlet.http.HttpServletRequest r) {
				String resolved = resolveClientIpForThrottle(r);
				if (StringUtils.isNotBlank(resolved)) {
					return resolved;
				}
			}
			Object ra = callContext.get("remoteAddress");
			if (ra instanceof String s && StringUtils.isNotBlank(s)) {
				return s;
			}
		} catch (RuntimeException ignored) {
			// fall through to "unknown"
		}
		return "unknown";
	}

	/** The same resolution for a door that has the request itself (API v1 login). */
	private String resolveClientIpForThrottle(jakarta.servlet.http.HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		String resolved = jp.aegif.nemaki.util.TrustedProxyResolver.resolveClientIp(request, propertyManager);
		if (StringUtils.isNotBlank(resolved)) {
			return resolved;
		}
		String addr = request.getRemoteAddr();
		return StringUtils.isNotBlank(addr) ? addr : null;
	}

	/**
	 * One construction of the throttle key, so the doors that check a password themselves cannot
	 * count against a different key than {@link #loginWithBasicAuth} does.
	 */
	private String throttleKey(String repositoryId, String username, String address) {
		return repositoryId + ":" + username + ":" + (StringUtils.isNotBlank(address) ? address : "unknown");
	}

	@Override
	public boolean isPasswordGuessingBlocked(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request) {
		return loginThrottle.isBlocked(
				throttleKey(repositoryId, username, resolveClientIpForThrottle(request)));
	}

	@Override
	public void recordPasswordFailure(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request) {
		loginThrottle.recordFailure(
				throttleKey(repositoryId, username, resolveClientIpForThrottle(request)));
	}

	@Override
	public void recordPasswordSuccess(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request) {
		loginThrottle.recordSuccess(
				throttleKey(repositoryId, username, resolveClientIpForThrottle(request)));
	}

	/**
	 * Authenticate using an API key.
	 * API keys bypass the method allow-list of allowedAuthMethods, allowing cloud-only users
	 * to authenticate via MCP clients and other programmatic access — but not "disabled", and not
	 * an account that no longer exists (3.4.1, AuthenticationUtil.mayStillAct).
	 */
	private boolean loginWithApiKey(CallContext callContext) {
		if (apiKeyService == null) {
			return false;
		}

		String repositoryId = callContext.getRepositoryId();
		Object apiKeyObj = callContext.get(CallContextKey.API_KEY);

		if (apiKeyObj == null) {
			return false;
		}

		String apiKey = (String) apiKeyObj;
		if (StringUtils.isBlank(apiKey)) {
			return false;
		}

		// Validate the API key
		String userId = apiKeyService.validateApiKey(repositoryId, apiKey);
		if (userId == null) {
			log.debug("API key authentication failed for repository: " + repositoryId);
			return false;
		}

		// The account the key belongs to must still be there, and not disabled: an API key does not expire, and it used to
		// authenticate an account that had been deleted or disabled (3.4.1, zero-trust P0).
		UserItem user = contentService.getUserItemById(repositoryId, userId);
		if (!AuthenticationUtil.mayStillAct(user)) {
			log.info("API key authentication refused in repository " + repositoryId + ": the account no longer exists or is disabled");
			return false;
		}

		// Set the username in the call context
		((CallContextImpl) callContext).put(CallContext.USERNAME, userId);
		setAdminFlagInContext(callContext, Boolean.TRUE.equals(user.isAdmin()));

		log.info("API key authentication successful for user: " + userId + " in repository: " + repositoryId);
		return true;
	}

	@Override
	public boolean loginForNemakiConfDb(CallContext callContext){
		final String suId = repositoryInfoMap.getSuperUsers().getId();
		final String repositoryId = callContext.getRepositoryId();

		// check system config db
		if(ObjectUtils.equals(repositoryId, SystemConst.NEMAKI_CONF_DB)){
			UserItem user = getAuthenticatedUserItem(suId, callContext.getUsername(), callContext.getPassword());
			if (user == null)
				return false;

			boolean isAdmin = Boolean.TRUE.equals(user.isAdmin());
			return isAdmin;
		}

		return false;
	}

	private void setAdminFlagInContext(CallContext callContext, Boolean isAdmin) {
		((CallContextImpl) callContext).put(CallContextKey.IS_ADMIN, isAdmin);
	}

	private boolean authenticateUserByToken(String app, String repositoryId, String userName, String token) {
		Token registeredToken = tokenService.getToken(app, repositoryId, userName);
		if (registeredToken == null) {
			log.warn("[TOKEN VALIDATION] No token found for user: " + userName + ", repository: " + repositoryId + ", app: " + app);
			return false;
		} else {
			long expiration = registeredToken.getExpiration();
			long currentTime = System.currentTimeMillis();

			// Keep token-validation logs minimal and never output token material.
			if (log.isDebugEnabled()) {
				log.debug("Token validation for user=" + userName + ", repository=" + repositoryId
						+ ", app=" + app + ", expiresInSec=" + ((expiration - currentTime) / 1000));
			}

			if (currentTime > expiration) {
				log.warn("[TOKEN VALIDATION] Token EXPIRED for user: " + userName + " (expired " + ((currentTime - expiration) / 1000) + " seconds ago)");
				return false;
			} else {
				String _registeredToken = registeredToken.getToken();
				boolean isValid = StringUtils.isNotEmpty(_registeredToken)
						&& StringUtils.isNotEmpty(token)
						&& java.security.MessageDigest.isEqual(
								_registeredToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
								token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				if (log.isDebugEnabled()) {
					log.debug("Token validation result for user=" + userName
							+ " in repository=" + repositoryId + ": " + isValid);
				}
				return isValid;
			}
		}
	}

	private boolean authenticateAdminByToken(String repositoryId, String userName) {
		return tokenService.isAdmin(repositoryId, userName);
	}

	/**
	 * Check if a specific authentication method is allowed for a user.
	 *
	 * The user's allowedAuthMethods property (nemaki:allowedAuthMethods) controls which
	 * authentication methods are permitted:
	 * - null/empty: All methods allowed (backward compatibility)
	 * - "password": Only password authentication
	 * - "cloud": Only cloud/OIDC authentication
	 * - "password,cloud": Both methods allowed
	 * - "disabled" anywhere in the list: No authentication allowed (account disabled)
	 *
	 * @param user The user to check
	 * @param method The authentication method to check ("password" or "cloud")
	 * @return true if the method is allowed, false otherwise
	 */
	public boolean isAuthMethodAllowed(UserItem user, String method) {
		// Delegate to the shared policy evaluator so every authentication entry
		// point (this primary CMIS path, api/v1 login, MCP, legacy admin re-auth)
		// enforces the same allowedAuthMethods gate.
		return AuthenticationUtil.isAuthMethodAllowed(user, method);
	}

	private UserItem getAuthenticatedUserItem(String repositoryId, String userId, String password) {
		if (StringUtils.isBlank(userId)) {
			log.debug("Authentication attempt with null/empty userId — rejecting");
			return null;
		}
		UserItem u = contentService.getUserItemById(repositoryId, userId);

		if (log.isDebugEnabled()) {
			log.debug("Authentication attempt - repositoryId: " + repositoryId + ", userId: " + userId +
				", userItem: " + (u != null ? "found" : "not found"));
		}

		// Check if password authentication is allowed for this user
		if (u != null && !isAuthMethodAllowed(u, "password")) {
			log.info("Password authentication denied for user " + userId + " (not in allowedAuthMethods)");
			return null;
		}

		// パスワード認証とセキュリティアップグレード
		if (u != null && StringUtils.isNotBlank(u.getPassowrd())) {
			if (log.isDebugEnabled()) {
				log.debug("Password field is not blank, attempting match with upgrade");
			}

			// This same password was verified against this same stored hash moments ago. Redoing
			// the BCrypt would produce the identical answer for ~225ms of CPU (cost 12), on every
			// request, because CMIS clients re-send credentials each time. The stored hash is part
			// of the cache key, so a password change makes the entry stop matching on its own.
			if (passwordCache.isVerified(repositoryId, userId, u.getPassowrd(), password)) {
				if (log.isDebugEnabled()) {
					log.debug("Password match SUCCESS (recently verified)");
				}
				return u;
			}

			// 新しいアップグレード対応認証を使用
			AuthenticationUtil.PasswordMatchResult result =
				AuthenticationUtil.passwordMatchesWithUpgrade(password, u.getPassowrd());

			if (result.matches()) {
				if (log.isDebugEnabled()) {
					log.debug("Password match SUCCESS");
				}
				
				// MD5からBCryptへのセキュリティアップグレードが必要な場合
				if (result.requiresUpgrade()) {
					if (log.isDebugEnabled()) {
						log.debug("SECURITY UPGRADE: Updating MD5 hash to BCrypt for user: " + userId);
					}
					
					try {
						// ユーザーのパスワードハッシュをBCryptに更新
						// bumpGeneration = false: the user did not change their password, so raising the
						// generation would end the session of the person who just logged in (R170).
						contentService.stampPasswordChange(u, result.getNewHash(), false);
						contentDaoService.update(repositoryId, u);
						
						log.info("SECURITY UPGRADE COMPLETED: User password hash updated to BCrypt for user: " + userId);
					} catch (Exception e) {
						if (log.isDebugEnabled()) {
							log.debug("SECURITY UPGRADE FAILED: " + e.getMessage());
						}
						// アップグレードが失敗しても認証は成功として扱う
						log.warn("Failed to upgrade password hash for user " + userId + ": " + e.getMessage());
					}
				}
				
				// Remember the verification that just ran — after any upgrade, so the key carries
				// the hash now on the account rather than the one it replaced. Failures are
				// deliberately not remembered: LoginThrottle has to keep seeing every one of them.
				passwordCache.remember(repositoryId, userId, u.getPassowrd(), password);

				log.debug(String.format( "[%s][%s]Get authenticated user successfully ! , Is admin?  : %s", repositoryId, userId , u.isAdmin()));
				return u;
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Password match FAILED");
				}
			}
		} else {
			if (log.isDebugEnabled()) {
				log.debug("Password field is blank or user is null");
			}
		}

		// Check anonymous
		String anonymousId = principalService.getAnonymous(repositoryId);
		if (StringUtils.isNotBlank(anonymousId) && anonymousId.equals(userId)) {
			if (u != null) {
				log.warn(anonymousId + " should have not been registered in the database.");
			}
			return null;
		}

		return null;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setTokenService(TokenService tokenService) {
		this.tokenService = tokenService;
	}

	public void setApiKeyService(ApiKeyService apiKeyService) {
		this.apiKeyService = apiKeyService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
