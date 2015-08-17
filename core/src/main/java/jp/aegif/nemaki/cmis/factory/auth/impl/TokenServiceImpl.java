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
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class TokenServiceImpl implements TokenService{
	
	private static final Log log = LogFactory
             .getLog(TokenServiceImpl.class);
	private PropertyManager propertyManager;
	private PrincipalService principalService;
	
	private TokenMap tokenMap = new TokenMap();
	private Set<String> admins = new HashSet<String>();
	
	private class TokenMap {
		private Map<String, HashMap<String, Token>> map = new HashMap<String, HashMap<String, Token>>();
		
		private Token get(String app, String userName){
			Map<String, Token> appMap = map.get(app);
			if(appMap == null){
				log.warn("No such app regitered for AuthToken");
				return null;
			}else{
				return appMap.get(userName);
			}
		}
		
		private Token set(String app, String userName){
			Map<String, Token> appMap = map.get(app);
			if(appMap == null){
				map.put(app, new HashMap<String, Token>());
				appMap = map.get(app);
			}
			
			String token = UUID.randomUUID().toString();
			
			long expiration = System.currentTimeMillis() + Long.valueOf(propertyManager.readValue(PropertyKey.AUTH_TOKEN_EXPIRATION));
			appMap.put(userName, new Token(userName, token, expiration));
			
			return appMap.get(userName);
		}
	}
	
	public void init() {
		final String repositoryId = "bedroom";	//TODO hard coding
		User admin = principalService.getAdmin(repositoryId);
		admins.add(admin.getId());
	}

	@Override
	public Token getToken(String app, String userName) {
		return tokenMap.get(app, userName);
	}

	@Override
	public Token setToken(String app, String userName) {
		return tokenMap.set(app, userName);
	}
	
	@Override
	public boolean isAdmin(String userName){
		return admins.contains(userName);
	}
	
	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
	
	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}
