package jp.aegif.nemaki.cmis.factory.auth.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jp.aegif.nemaki.cmis.factory.auth.TokenService;

public class TokenServiceImpl implements TokenService{
	private Map<String,String> tokenMap = new HashMap<String, String>();
	private Set<String> admins = new HashSet<String>();
	
	@Override
	public String getToken(String userName) {
		return tokenMap.get(userName);
	}

	@Override
	public String setToken(String userName) {
		String token = UUID.randomUUID().toString();
		tokenMap.put(userName, token);
		return token;
	}
	
	@Override
	public boolean isAdmin(String userName){
		return admins.contains(userName);
	}
	
	@Override
	public void setAdmin(String userName){
		admins.add(userName);
	}
	
}
