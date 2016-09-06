package jp.aegif.nemaki.plugin.action;

public class UserButtonActionTrigger extends ActionTriggerBase {


	public UserButtonActionTrigger(String displayName){
		this._displayName = displayName;

	}

	private String _displayName;
	public String getDisplayName(){
		return _displayName;
	}

	private String _fontAwesomeName = "fa fa-fire";
	public String getFontAwesomeName(){
		return _fontAwesomeName;
	}


}
