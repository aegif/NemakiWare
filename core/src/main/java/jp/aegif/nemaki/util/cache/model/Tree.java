package jp.aegif.nemaki.util.cache.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Tree {
	private String parent;
	private Set<String> children;
	
	public Tree(String parent){
		this.parent = parent;
		this.children = new HashSet<>();
	}
	
	public void add(String objectId){
		children.add(objectId);
	}
	
	public void remove(String objectId){
		children.remove(objectId);
	}
	
	public String getParent() {
		return parent;
	}

	public void setParent(String parent) {
		this.parent = parent;
	}

	public Set<String> getChildren() {
		return children;
	}

	public void setChildren(Set<String> children) {
		this.children = children;
	}
}
