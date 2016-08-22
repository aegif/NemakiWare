package jp.aegif.nemaki.action.sample;

import java.util.logging.Logger;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.action.ActionTriggerBase;
import jp.aegif.nemaki.action.JavaBackedAction;
import jp.aegif.nemaki.action.UserButtonActionTrigger;


public class SampleAction implements JavaBackedAction {
	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(JavaBackedAction.class);

	@Override
	public ActionTriggerBase getActionTrigger() {
		return new UserButtonActionTrigger("サンプル");
	}

	@Override
	public boolean canExecute(ObjectData obj) {
		 return ( BaseTypeId.CMIS_DOCUMENT ==  obj.getBaseTypeId());
	}

	@Override
	public void executeAction(ObjectData obj) {
		logger.debug("ボタンが押されました ID="+obj.getId());

	}

	@Override
	public boolean supports(String version) {
		return true;
	}

	@Override
	public String getUniqueId() {
		return "sample";
	}

}
