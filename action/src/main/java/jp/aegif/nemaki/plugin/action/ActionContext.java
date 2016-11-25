package jp.aegif.nemaki.plugin.action;

import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class ActionContext {

	private CallContext callContext;
	private Properties cmisProperties;
	
	public ActionContext(CallContext callContext, Properties cmisProperties) {
		this.callContext = callContext;
		this.cmisProperties = cmisProperties;
	}
	
	public CallContext getCallContext() {
		return this.callContext;
	}
	
	public Properties getProperties() {
		return this.cmisProperties;
	}
}
