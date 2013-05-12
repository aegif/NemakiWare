package jp.aegif.nemaki.model;

import java.util.ArrayList;
import java.util.List;


public class Aspect {
	private static final long serialVersionUID = -4672031101743254600L;
	private String name;
	private List<Property> properties = new ArrayList<Property>();

	public Aspect(String name, List<Property> properties) {
		this.name = name;
		this.properties = properties;
	}
	
	public Aspect(){
		
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Property> getProperties() {
		return properties;
	}

	public void setProperties(List<Property> properties) {
		this.properties = properties;
	}
}
