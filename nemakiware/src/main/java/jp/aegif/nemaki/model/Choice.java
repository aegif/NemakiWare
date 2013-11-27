package jp.aegif.nemaki.model;

import java.util.List;

public class Choice {
	private String displayName;
	private List<Object> value;
	private List<Choice> children;
	
	public Choice(){
		
	}
	
	public Choice(String displayname, List<Object> value, List<Choice> children){
		setDisplayName(displayname);
		setValue(value);
		setChildren(children);
	}
	
	public String getDisplayName() {
		return displayName;
	}
	
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	
	public List<Object> getValue() {
		return value;
	}

	public void setValue(List<Object> value) {
		this.value = value;
	}

	public List<Choice> getChildren() {
		return children;
	}
	
	public void setChildren(List<Choice> children) {
		this.children = children;
	}
}
