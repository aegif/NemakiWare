package jp.aegif.nemaki.action;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.springframework.plugin.core.Plugin;


public interface JavaBackedAction extends Plugin<String>{

	public String getUniqueId();

	public ActionTriggerBase getActionTrigger();

	public boolean canExecute(CmisObject obj);

	public void executeAction(CmisObject obj);




}
