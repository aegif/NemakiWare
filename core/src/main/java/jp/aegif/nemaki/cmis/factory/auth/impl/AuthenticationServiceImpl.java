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
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
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
	private PropertyManager propertyManager;
	private RepositoryInfoMap repositoryInfoMap;

	public boolean login(CallContext callContext) {
		String repositoryId = callContext.getRepositoryId();

		// Set flag of SuperUsers
		String suId = repositoryInfoMap.getSuperUsers().getId();
		((CallContextImpl)callContext).put(CallContextKey.IS_SU,
				suId.equals(repositoryId));

		// SSO
		if (loginWithExternalAuth(callContext)) {
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
				boolean isAdmin = userItem.isAdmin() == null ? false : true;
				setAdminFlagInContext(callContext, isAdmin);
			}
			log.debug("Header Authenticated. UserId=" + userItem.getUserId());
			return true;
		}
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

		boolean isAdmin = user.isAdmin() == null ? false : true;
		setAdminFlagInContext(callContext, isAdmin);
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

			boolean isAdmin = user.isAdmin() == null ? false : true;
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
			return false;
		} else {
			long expiration = registeredToken.getExpiration();
			if (System.currentTimeMillis() > expiration) {
				return false;
			} else {
				String _registeredToken = registeredToken.getToken();
				return StringUtils.isNotEmpty(_registeredToken) && _registeredToken.equals(token);
			}
		}
	}

	private boolean authenticateAdminByToken(String repositoryId, String userName) {
		return tokenService.isAdmin(repositoryId, userName);
	}

	private UserItem getAuthenticatedUserItem(String repositoryId, String userId, String password) {
		UserItem u = contentService.getUserItemById(repositoryId, userId);

		// succeeded
		if (u != null && StringUtils.isNotBlank(u.getPassowrd())) {
			if (AuthenticationUtil.passwordMatches(password, u.getPassowrd())) {
				log.debug(String.format( "[%s][%s]Get authenticated user successfully ! , Is admin?  : %s", repositoryId, userId , u.isAdmin()));
				return u;
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

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}