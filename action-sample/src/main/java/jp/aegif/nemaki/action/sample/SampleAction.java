package jp.aegif.nemaki.action.sample;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.plugin.action.ActionTriggerBase;
import jp.aegif.nemaki.plugin.action.JavaBackedAction;
import jp.aegif.nemaki.plugin.action.UserButtonActionTrigger;


public class SampleAction implements JavaBackedAction {
	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(JavaBackedAction.class);

	@Override
	public ActionTriggerBase getActionTrigger() {
		return new UserButtonActionTrigger("サンプルアクションの実行");
	}

	@Override
	public boolean canExecute(ObjectData obj) {
		 return ( BaseTypeId.CMIS_DOCUMENT ==  obj.getBaseTypeId());
	}

	@Override
	public void executeAction(ObjectData obj, Map<String, List<String>> params) {
		logger.debug("アクションが実行されました オブジェクトID="+obj.getId());
		for(String key : params.keySet()){
			logger.debug(key + "=" + params.get(key));
		}
	}

	@Override
	public boolean supports(String version) {
		return true;
	}

	@Override
	public String getActionTile() {
		return "サンプルのアクション";
	}

	@Override
	public String getActionDiscription() {
		return "アクション機能のためのサンプルです";
	}


}
