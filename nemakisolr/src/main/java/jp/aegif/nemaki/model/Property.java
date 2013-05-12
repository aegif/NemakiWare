package jp.aegif.nemaki.model;

public class Property {
	private static final long serialVersionUID = -3115731765493819565L;
	private String key;
	private Object value;
	private String queryName;

	public Property(String key, Object value) {
		this.key = key;
		this.value = value;
	}

	public Property(){
		
	}
	
	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public Object getValue() {
		return value;
	}

	public void setValue(Object value) {
		this.value = value;
	}

	public String getQueryName() {
		return queryName;
	}

	public void setQueryName(String queryName) {
		this.queryName = queryName;
	}
}
