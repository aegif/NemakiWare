package jp.aegif.nemaki.model;

public class IndexOfContent {
	private String id;
	private String type;
	
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	
	public Boolean ofDocument(){
		return (type.equals("document")) ? true : false;
	}
	
	public Boolean ofFolder(){
		return (type.equals("folder")) ? true : false;
	}
}
