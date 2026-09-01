package jp.aegif.nemaki.util.cache.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Tree implements Serializable {
	private static final long serialVersionUID = 1L;
	private String parent;
	private Set<String> children;
	
	/**
	 * Children the store could not decode when this tree was BUILT.
	 *
	 * <p>Cached with the tree because it is a fact about the listing the tree stands for, and a
	 * warm hit never touches the store again — so without it the decorator has to answer
	 * "unknown" on every hit, which is not a rare failure but the ORDINARY state of a working
	 * cache. A guard that fires on ordinary work is an outage, and the callers refuse on it:
	 * external ingest would stop for any folder listed twice.
	 *
	 * <p>Zero for a tree deserialised from an older cache entry, which is the same answer the
	 * store gives for a clean read — and a stale entry rebuilds on the next miss anyway.
	 */
	private int unreadableAtBuild;

	public Tree(String parent){
		this.parent = parent;
		this.children = new HashSet<>();
	}

	/** @see #unreadableAtBuild */
	public int getUnreadableAtBuild() {
		return unreadableAtBuild;
	}

	/** @see #unreadableAtBuild */
	public void setUnreadableAtBuild(int unreadableAtBuild) {
		this.unreadableAtBuild = unreadableAtBuild;
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
