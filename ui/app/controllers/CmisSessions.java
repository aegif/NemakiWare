package controllers;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;

import util.Util;

public class CmisSessions {
	private static Map<String, Session> cmisSessions = new HashMap<String, Session>();
	
	public static Session getCmisSession(String repositoryId, play.mvc.Http.Session session){
		Session cmisSession = cmisSessions.get(repositoryId);
		if(cmisSession == null){
			cmisSession = Util.createCmisSession(repositoryId, session);
			cmisSessions.put(repositoryId, cmisSession);
		}
		return cmisSession;
	}
}
