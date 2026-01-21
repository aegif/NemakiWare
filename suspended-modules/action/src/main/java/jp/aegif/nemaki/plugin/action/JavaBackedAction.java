package jp.aegif.nemaki.plugin.action;


import org.springframework.plugin.core.Plugin;

import jp.aegif.nemaki.plugin.action.trigger.ActionTriggerBase;


public interface JavaBackedAction extends Plugin<String>{

	public String getActionTitle();

	public String getActionDiscription();

	public ActionTriggerBase getActionTrigger(ActionContext actionContext);

	public boolean canExecute(ActionContext actionContext);

	public String executeAction(ActionContext actionContext, String json);

}
