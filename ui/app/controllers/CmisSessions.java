package controllers;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;

import constant.Token;
import util.Util;

public class CmisSessions {
	private static Map<String, CmisSessionsOfRepository> cmisSessions = new HashMap<String, CmisSessionsOfRepository>();
	
	static class CmisSessionsOfRepository{
		private Map<String, Session> map = new HashMap<String, Session>();
		public Session get(String userId){
			return map.get(userId);
		}
		public void put(String userId, Session session){
			map.put(userId, session);
		}
	}
	
	public static Session getCmisSession(String repositoryId, play.mvc.Http.Session session){
		CmisSessionsOfRepository repoSession = cmisSessions.get(repositoryId);
		if(repoSession == null){
			repoSession = new CmisSessionsOfRepository();
			cmisSessions.put(repositoryId, repoSession);
		}
		
		String userId = session.get(Token.LOGIN_USER_ID);
		Session cmisSession = repoSession.get(userId);
		if(cmisSession == null){
			cmisSession = Util.createCmisSession(repositoryId, session);
			repoSession.put(userId, cmisSession);
		}
		
		return cmisSession;
	}
}
