package jp.aegif.nemaki.plugin.action.trigger;

public class UserCreateButtonTrigger extends ActionTriggerBase {
	public UserCreateButtonTrigger(String displayName){
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


	private String _formHtml;
	public String getFormHtml(){
		return _formHtml;
	}
	public void setFormHtml(String value){
		 _formHtml = value;
	}

}
