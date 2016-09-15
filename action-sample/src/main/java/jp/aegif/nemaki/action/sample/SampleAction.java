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

	private static UserButtonActionTrigger _trigger;
	@Override
	public ActionTriggerBase getActionTrigger() {
		if (_trigger == null){
			_trigger = new UserButtonActionTrigger("サンプルアクションの実行");
			_trigger.setFormHtml(""
				+ "<div class='fav caption'>サンプルのフォーム</div>\n"
				+ "<select class='dropdown' name='sampleFormData'>\n"
				+ "   <option value='1'>テスト1</option>\n"
				+ "   <option value='2'>テスト2</option>\n"
				+ "   <option value='3'>テスト3</option>\n"
				+ "</select><br />\n"
				+ "<input class='form-control' type='textbox' name='sampleTextboxData'></input>\n"
			);
		}
		return _trigger;
	}

	@Override
	public boolean canExecute(ObjectData obj) {
		 return ( BaseTypeId.CMIS_DOCUMENT ==  obj.getBaseTypeId());
	}

	@Override
	public void executeAction(ObjectData obj, String json) {
		logger.info("アクションが実行されました オブジェクトID="+obj.getId());

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
