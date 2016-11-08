package jp.aegif.nemaki.action.sample;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.plugin.action.*;
import jp.aegif.nemaki.plugin.action.trigger.*;


public class SampleObjectCreateAction implements JavaBackedAction {
	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(JavaBackedAction.class);

	private static UserCreateButtonTrigger _trigger;
	@Override
	public ActionTriggerBase getActionTrigger() {
		if (_trigger == null){
			_trigger = new UserCreateButtonTrigger("サンプルアクションの実行");
			_trigger.setFormHtml(""
				+ "<div>サンプルの作成フォーム</div>\n"
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
		 return ( BaseTypeId.CMIS_FOLDER ==  obj.getBaseTypeId());
	}

	@Override
	public String executeAction(ObjectData obj, String json) {
		String name = (String) obj.getProperties().getPropertyList().stream().map(p -> p.getFirstValue()).findFirst().get();

		return "{\"message\" : \"作成アクションが実行されました ファイル名：" + name + "\"}";

	}

	@Override
	public boolean supports(String version) {
		return true;
	}

	@Override
	public String getActionTiTle() {
		return "サンプルのアクション";
	}

	@Override
	public String getActionDiscription() {
		return "アクション機能のためのサンプルです";
	}


}
