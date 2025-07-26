package jp.aegif.nemaki.plugin.action;


import org.springframework.plugin.core.Plugin;

import jp.aegif.nemaki.plugin.action.trigger.ActionTriggerBase;


public interface JavaBackedUIAction extends Plugin<String>{

	public String getActionTitle();

	public String getActionDiscription();

	public ActionTriggerBase getActionTrigger(UIActionContext context);

	public boolean canExecute(UIActionContext context);

	public String executeAction(UIActionContext context, String json);

}
