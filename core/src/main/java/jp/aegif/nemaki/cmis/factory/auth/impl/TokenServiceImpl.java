package jp.aegif.nemaki.cmis.factory.auth.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class TokenServiceImpl implements TokenService{
	
	private static final Log log = LogFactory
             .getLog(TokenServiceImpl.class);
	private PropertyManager propertyManager;
	private PrincipalService principalService;
	private RepositoryInfoMap repositoryInfoMap;
	
	private TokenMap tokenMap = new TokenMap();
	private Map<String, Set<String>> admins = new HashMap<String, Set<String>>();
	
	private class TokenMap {
		private Map<String, Map<String, Map<String, Token>>> map = new HashMap<String, Map<String,Map<String,Token>>>();
		
		private Token get(String app, String repositoryId, String userName){
			Map<String, Map<String, Token>> appMap = map.get(app);
			if(appMap == null){
				log.warn("No such app regitered for AuthToken");
				return null;
			}else{
				 Map<String, Token> repoMap = appMap.get(repositoryId);
				 if(repoMap == null){
					 log.warn("No such repositoryId regitered for AuthToken");
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
			
			long expiration = System.currentTimeMillis() + Long.valueOf(propertyManager.readValue(PropertyKey.AUTH_TOKEN_EXPIRATION));
			repoMap.put(userName, new Token(userName, token, expiration));
			
			return repoMap.get(userName);
		}
	}
	
	public void init() {
		for(String key : repositoryInfoMap.keys()){
			admins.put(key, new HashSet<String>());
			admins.get(key).add(principalService.getAdmin(key).getId());
		}
	}

	@Override
	public Token getToken(String app, String repositoryId, String userName) {
		return tokenMap.get(app, repositoryId, userName);
	}

	@Override
	public Token setToken(String app, String repositoryId, String userName) {
		return tokenMap.set(app,repositoryId,  userName);
	}
	
	@Override
	public boolean isAdmin(String repositoryId, String userName){
		return admins.get(repositoryId).contains(userName);
	}
	
	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
	
	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
