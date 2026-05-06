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
		if (!isTrustedProxy(remoteAddr, trustedProxies)) {
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

	/** Check if remoteAddr is in the comma-separated trustedProxies list. */
	private static boolean isTrustedProxy(String remoteAddr, String trustedProxies) {
		if (remoteAddr == null || trustedProxies == null) return false;
		for (String raw : trustedProxies.split(",")) {
			String trusted = raw.trim();
			if (trusted.isEmpty()) continue;
			if (trusted.equals(remoteAddr)) return true;
			// Allow localhost variants
			if (("127.0.0.1".equals(trusted) || "localhost".equals(trusted))
					&& ("127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr))) {
				return true;
			}
		}
		return false;
	}

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

		// Basic auth with id/password
		UserItem user = getAuthenticatedUserItem(callContext.getRepositoryId(), callContext.getUsername(), callContext.getPassword());
		if (user == null) return false;

		boolean isAdmin = Boolean.TRUE.equals(user.isAdmin());
		setAdminFlagInContext(callContext, isAdmin);
		return true;
	}

	/**
	 * Authenticate using an API key.
	 * API keys bypass allowedAuthMethods restrictions, allowing cloud-only users
	 * to authenticate via MCP clients and other programmatic access.
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

		// Set the username in the call context
		((CallContextImpl) callContext).put(CallContext.USERNAME, userId);

		// Get user info and set admin flag
		UserItem user = contentService.getUserItemById(repositoryId, userId);
		if (user != null) {
			boolean isAdmin = Boolean.TRUE.equals(user.isAdmin());
			setAdminFlagInContext(callContext, isAdmin);
		}

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
				boolean isValid = StringUtils.isNotEmpty(_registeredToken) && _registeredToken.equals(token);
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
	 * - "disabled": No authentication allowed (account disabled)
	 *
	 * @param user The user to check
	 * @param method The authentication method to check ("password" or "cloud")
	 * @return true if the method is allowed, false otherwise
	 */
	public boolean isAuthMethodAllowed(UserItem user, String method) {
		if (user == null || method == null) {
			return false;
		}

		// Get allowedAuthMethods from subTypeProperties
		String allowedMethods = null;
		if (user.getSubTypeProperties() != null) {
			for (jp.aegif.nemaki.model.Property prop : user.getSubTypeProperties()) {
				if ("nemaki:allowedAuthMethods".equals(prop.getKey())) {
					allowedMethods = String.valueOf(prop.getValue());
					break;
				}
			}
		}

		// null/empty means all methods allowed (backward compatibility)
		if (allowedMethods == null || allowedMethods.isEmpty() || "null".equals(allowedMethods)) {
			return true;
		}

		// "disabled" means no authentication allowed
		if ("disabled".equalsIgnoreCase(allowedMethods.trim())) {
			if (log.isDebugEnabled()) {
				log.debug("User " + user.getUserId() + " has authentication disabled");
			}
			return false;
		}

		// Check if the requested method is in the comma-separated list
		String[] methods = allowedMethods.split(",");
		for (String m : methods) {
			if (method.equalsIgnoreCase(m.trim())) {
				return true;
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Auth method '" + method + "' not allowed for user " + user.getUserId() +
					  " (allowed: " + allowedMethods + ")");
		}
		return false;
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
						u.setPassowrd(result.getNewHash());
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
