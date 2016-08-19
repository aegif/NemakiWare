package jp.aegif.nemaki.action.sample;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

import com.sun.xml.ws.api.PropertySet.Property;

import jp.aegif.nemaki.action.ActionTriggerBase;
import jp.aegif.nemaki.action.JavaBackedAction;
import jp.aegif.nemaki.action.UserButtonActionTrigger;

public class SampleAction implements JavaBackedAction {

	@Override
	public ActionTriggerBase getActionTrigger() {
		return new UserButtonActionTrigger("サンプル");
	}

	@Override
	public boolean canExecute(CmisObject obj) {
		 return ( BaseTypeId.CMIS_DOCUMENT ==  obj.getBaseTypeId());
	}

	@Override
	public void executeAction(CmisObject obj) {


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
