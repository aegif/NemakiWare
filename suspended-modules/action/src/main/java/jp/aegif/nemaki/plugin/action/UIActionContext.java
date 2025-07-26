package jp.aegif.nemaki.plugin.action;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Session;


public class UIActionContext {
	
	private CmisObject object;
	private Session session;

	public UIActionContext(CmisObject cmisObj, Session session) {
		this.object = cmisObj;
		this.session = session;
	}
	
	public CmisObject getCmisObject() {
		return object;
	}
	
	public Session getSession() {
		return session;
	}
}
