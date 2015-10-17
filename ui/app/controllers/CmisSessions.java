package controllers;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;

import constant.Token;
import util.Util;

public class CmisSessions {
	private static Map<String, RepoSession> cmisSessions = new HashMap<String, RepoSession>();
	
	static class RepoSession{
		private String repositoryId;
		private Map<String, Session> map = new HashMap<String, Session>();
		
		public RepoSession(String repositoryId){
			setRepositoryId(repositoryId);
		}
		
		public Session get(String userId){
			return map.get(userId);
		}
		
		public void put(String userId, Session session){
			map.put(userId, session);
		}
		
		public void remove(String userId){
			map.remove(userId);
		}
		
		public String getRepositoryId(){
			return repositoryId;
		}
		
		public void setRepositoryId(String repositoryId){
			this.repositoryId = repositoryId;
		}
	}
	
	public static Session getCmisSession(String repositoryId, play.mvc.Http.Session session){
		RepoSession repoSession = getRepoSession(repositoryId);
		Session cmisSession = getUserSession(repoSession, session);
		return cmisSession;
	}
	
	private static RepoSession getRepoSession(String repositoryId){
		RepoSession repoSession = cmisSessions.get(repositoryId);
		if(repoSession == null){
			repoSession = new RepoSession(repositoryId);
			cmisSessions.put(repositoryId, repoSession);
		}
		return repoSession;
	}
	
	private static Session getUserSession(RepoSession repoSession, play.mvc.Http.Session session){
		String userId = session.get(Token.LOGIN_USER_ID);
		Session cmisSession = repoSession.get(userId);
		if(cmisSession == null){
			cmisSession = Util.createCmisSession(repoSession.getRepositoryId(), session);
			repoSession.put(userId, cmisSession);
		}
		return cmisSession;
	}
	
	public static void disconnect(String repositoryId, play.mvc.Http.Session session){
		RepoSession repoSession = getRepoSession(repositoryId);
		Session cmisSession = getUserSession(repoSession, session);

		cmisSession.clear();
		String userId = session.get(Token.LOGIN_USER_ID);
		repoSession.remove(userId);
	}
}
