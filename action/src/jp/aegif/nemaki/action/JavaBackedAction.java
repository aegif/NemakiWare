package jp.aegif.nemaki.action;

import org.apache.chemistry.opencmis.client.api.CmisObject;


public interface JavaBackedAction {

	public ActionTriggerBase getActionTrigger();

	public boolean canExecute(CmisObject obj);

	public void executeAction(CmisObject obj);




}
