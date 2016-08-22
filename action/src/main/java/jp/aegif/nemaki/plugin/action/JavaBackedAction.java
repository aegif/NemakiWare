package jp.aegif.nemaki.plugin.action;


import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.springframework.plugin.core.Plugin;


public interface JavaBackedAction extends Plugin<String>{

	public String getUniqueId();

	public ActionTriggerBase getActionTrigger();

	public boolean canExecute(ObjectData obj);

	public void executeAction(ObjectData obj);




}
