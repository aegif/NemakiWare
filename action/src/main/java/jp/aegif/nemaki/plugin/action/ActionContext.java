package jp.aegif.nemaki.plugin.action;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class ActionContext {

	private CallContext callContext;
	private Properties cmisProperties;
	private ObjectData objectData;
	
	public ActionContext(CallContext callContext, Properties cmisProperties, ObjectData objectData) {
		this.callContext = callContext;
		this.cmisProperties = cmisProperties;
		this.objectData = objectData;
	}
	
	public CallContext getCallContext() {
		return this.callContext;
	}
	
	public Properties getProperties() {
		return this.cmisProperties;
	}
	
	public ObjectData getObjectData() {
		return this.objectData;
	}
}
