package jp.aegif.nemaki.model;

import java.util.HashMap;
import java.util.Map;

public class Configuration extends NodeBase{
	private Map<String, Object> configuration = new HashMap<>();

	public Configuration(){
		setType("configuration");
	}
	
	public Configuration(NodeBase n){
		this();
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}
}
