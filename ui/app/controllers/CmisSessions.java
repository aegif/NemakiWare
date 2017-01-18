package controllers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.Session;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.play.PlayWebContext;

import constant.Token;
import util.Util;
import util.authentication.NemakiProfile;

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

	public static Session getCmisSession(String repositoryId, play.mvc.Http.Context ctx){
		RepoSession repoSession = getRepoSession(repositoryId);
		Session cmisSession = getUserSession(repoSession, ctx);
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

	private static Session getUserSession(RepoSession repoSession, play.mvc.Http.Context ctx){
		CommonProfile profile =  Util.getProfile(ctx);
		String userId = profile.getAttribute(Token.LOGIN_USER_ID, String.class);

		if (userId == null) return null;
		Session cmisSession = repoSession.get(userId);
		if(cmisSession == null){
			cmisSession = Util.createCmisSession(repoSession.getRepositoryId(), ctx);
			repoSession.put(userId, cmisSession);
		}
		return cmisSession;
	}

	public static void clear(play.mvc.Http.Context ctx){
		Set<String> keySet = cmisSessions.keySet();
		for(String key : keySet){
			disconnect(key, ctx);
		}
		cmisSessions.clear();
	}

	public static void disconnect(String repositoryId, play.mvc.Http.Context ctx){
		CommonProfile profile =  Util.getProfile(ctx);
		String userId = profile.getAttribute(Token.LOGIN_USER_ID, String.class);

		RepoSession repoSession = getRepoSession(repositoryId);
		if (userId != null) {
			Session cmisSession = repoSession.get(userId);
			if (cmisSession != null){
				cmisSession.clear();
				repoSession.remove(userId);
			}
		}
	}
}
