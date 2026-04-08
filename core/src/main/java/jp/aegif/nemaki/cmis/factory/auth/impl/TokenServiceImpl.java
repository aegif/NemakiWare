package jp.aegif.nemaki.cmis.factory.auth.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TokenServiceImpl implements TokenService{
	
	private static final Log log = LogFactory
             .getLog(TokenServiceImpl.class);
	private PropertyManager propertyManager;
	private ContentService contentService;
	private PrincipalService principalService;
	private RepositoryInfoMap repositoryInfoMap;
	
	private TokenMap tokenMap = new TokenMap();
	private Map<String, List<String>> admins = new HashMap<String, List<String>>();
	
	private class TokenMap {
		private Map<String, Map<String, Map<String, Token>>> map = new HashMap<String, Map<String,Map<String,Token>>>();
		
		private Token get(String app, String repositoryId, String userName){
			Map<String, Map<String, Token>> appMap = map.get(app);
			if(appMap == null){
				log.warn(String.format("No such app(%s) registered for AuthToken", app));
				return null;
			}else{
				 Map<String, Token> repoMap = appMap.get(repositoryId);
				 if(repoMap == null){
					 log.warn(String.format("No such repositoryId(%s) registered for AuthToken", repositoryId));
					 log.warn("app: " + app + " user: " + userName);
					 log.warn("appMap: "+appMap.toString());
					 return null;
				 }else{
					 return repoMap.get(userName);
				 }
			}
		}
		
		private Token set(String app, String repositoryId, String userName){
			Map<String, Map<String, Token>> appMap = map.get(app);
			if(appMap == null){
				map.put(app, new HashMap<String, Map<String, Token>>());
				appMap = map.get(app);
			}
			
			Map<String, Token> repoMap = appMap.get(repositoryId);
			if(repoMap == null){
				appMap.put(repositoryId, new HashMap<String, Token>());
				repoMap = appMap.get(repositoryId);
			}
			
			String token = UUID.randomUUID().toString();

			String expirationConfig = propertyManager.readValue(PropertyKey.AUTH_TOKEN_EXPIRATION);
			long expirationMillis = Long.parseLong(expirationConfig);
			long currentTime = System.currentTimeMillis();
			long expiration = currentTime + expirationMillis;

			if (log.isDebugEnabled()) {
				log.debug("Token created for user: " + userName + ", repository: " + repositoryId
						+ ", expires in: " + (expirationMillis / 60000) + " minutes");
			}

			repoMap.put(userName, new Token(userName, token, expiration));

			return repoMap.get(userName);
		}
		
		private void remove(String app, String repositoryId, String userName){
			Map<String, Map<String, Token>> appMap = map.get(app);
			if(appMap == null){
				return;
			}
			Map<String, Token> repoMap = appMap.get(repositoryId);
			if(repoMap == null){
				return;
			}
			repoMap.remove(userName);
			log.info("Token removed for user: " + userName + ", repository: " + repositoryId + ", app: " + app);
		}
		
		private String validate(String app, String repositoryId, String tokenString){
			if (tokenString == null) {
				return null;
			}
			Map<String, Map<String, Token>> appMap = map.get(app);
			if(appMap == null){
				return null;
			}
			Map<String, Token> repoMap = appMap.get(repositoryId);
			if(repoMap == null){
				return null;
			}
			long currentTime = System.currentTimeMillis();
			// Prune expired tokens opportunistically during validation to avoid
			// unbounded in-memory growth in long-running processes.
			Iterator<Map.Entry<String, Token>> it = repoMap.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, Token> entry = it.next();
				Token token = entry.getValue();
				if (token != null && token.getExpiration() <= currentTime) {
					it.remove();
					continue;
				}
				if(token != null && tokenString.equals(token.getToken())){
					return entry.getKey();
				}
			}
			return null;
		}
	}
	
	public void init() {
		for(String key : repositoryInfoMap.keys()){
			//extract admin ids
			List<User>admins = principalService.getAdmins(key);
			List<String>userIds = new ArrayList<String>();
			if(CollectionUtils.isNotEmpty(admins)){
				for(User admin : admins){
					userIds.add(admin.getUserId());
				}
			}

			this.admins.put(key, userIds);
		}
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
