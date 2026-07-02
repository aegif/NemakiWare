package jp.aegif.nemaki.cmis.factory.auth.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.UUID;

public class TokenServiceImpl implements TokenService{
	
	private static final Log log = LogFactory
             .getLog(TokenServiceImpl.class);
	private PropertyManager propertyManager;
	private ContentService contentService;
	private PrincipalService principalService;
	private RepositoryInfoMap repositoryInfoMap;
	
	private TokenMap tokenMap = new TokenMap();
	
	private class TokenMap {
		// ConcurrentHashMap at all levels for thread-safe concurrent access
		// from multiple servlet request threads.
		private final java.util.concurrent.ConcurrentHashMap<String,
				java.util.concurrent.ConcurrentHashMap<String,
						java.util.concurrent.ConcurrentHashMap<String, Token>>> map =
				new java.util.concurrent.ConcurrentHashMap<>();

		private Token get(String app, String repositoryId, String userName){
			var appMap = map.get(app);
			if(appMap == null){
				log.warn(String.format("No such app(%s) registered for AuthToken", app));
				return null;
			}
			var repoMap = appMap.get(repositoryId);
			if(repoMap == null){
				log.warn(String.format("No such repositoryId(%s) registered for AuthToken", repositoryId));
				return null;
			}
			return repoMap.get(userName);
		}

		private Token set(String app, String repositoryId, String userName){
			var appMap = map.computeIfAbsent(app, k -> new java.util.concurrent.ConcurrentHashMap<>());
			var repoMap = appMap.computeIfAbsent(repositoryId, k -> new java.util.concurrent.ConcurrentHashMap<>());

			String token = UUID.randomUUID().toString();

			String expirationConfig = propertyManager.readValue(PropertyKey.AUTH_TOKEN_EXPIRATION);
			long expirationMillis = Long.parseLong(expirationConfig);
			long currentTime = System.currentTimeMillis();
			long expiration = currentTime + expirationMillis;

			if (log.isDebugEnabled()) {
				log.debug("Token created for user: " + userName + ", repository: " + repositoryId
						+ ", expires in: " + (expirationMillis / 60000) + " minutes");
			}

			Token newToken = new Token(userName, token, expiration);
			repoMap.put(userName, newToken);
			return newToken;
		}

		private void remove(String app, String repositoryId, String userName){
			var appMap = map.get(app);
			if(appMap == null) return;
			var repoMap = appMap.get(repositoryId);
			if(repoMap == null) return;
			repoMap.remove(userName);
			log.info("Token removed for user: " + userName + ", repository: " + repositoryId + ", app: " + app);
		}

		private String validate(String app, String repositoryId, String tokenString){
			if (tokenString == null) return null;
			var appMap = map.get(app);
			if(appMap == null) return null;
			var repoMap = appMap.get(repositoryId);
			if(repoMap == null) return null;

			long currentTime = System.currentTimeMillis();
			String matchedUser = null;
			// ConcurrentHashMap.entrySet() is weakly consistent — safe to
			// iterate and remove concurrently without ConcurrentModificationException.
			for (var entry : repoMap.entrySet()) {
				Token token = entry.getValue();
				if (token != null && token.getExpiration() <= currentTime) {
					repoMap.remove(entry.getKey());
					continue;
				}
				// Constant-time comparison to avoid leaking a prefix match of the
				// session token via timing (consistent with the token check in
				// AuthenticationServiceImpl.authenticateUserByToken). The full scan
				// is retained so expired entries are still swept every call.
				if (token != null) {
					String stored = token.getToken();
					if (stored != null && java.security.MessageDigest.isEqual(
							stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
							tokenString.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
						matchedUser = entry.getKey();
					}
				}
			}
			return matchedUser;
		}
	}
	
	public void init() {
		// Admin status is evaluated live via isAdmin() at request time,
		// so no startup caching is needed.
	}

	@Override
	public Token getToken(String app, String repositoryId, String userId) {
		return tokenMap.get(app, repositoryId, userId);
	}

	@Override
	public Token setToken(String app, String repositoryId, String userId) {
		return tokenMap.set(app,repositoryId,  userId);
	}
	
	@Override
	public void removeToken(String app, String repositoryId, String userId) {
		tokenMap.remove(app, repositoryId, userId);
	}
	
	@Override
	public String validateToken(String app, String repositoryId, String tokenString) {
		return tokenMap.validate(app, repositoryId, tokenString);
	}
	
	@Override
	public boolean isAdmin(String repositoryId, String userId){
		// Evaluate current privilege at request time so admin grant/revoke is reflected
		// immediately without requiring service restart.
		UserItem user = contentService.getUserItemById(repositoryId, userId);
		return user != null && Boolean.TRUE.equals(user.isAdmin());
	}
	
	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
	
	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
