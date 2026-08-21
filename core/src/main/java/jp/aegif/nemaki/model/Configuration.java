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
	
	/**
	 * True when this object is empty because the read FAILED, not because there is nothing.
	 *
	 * <p>{@code getConfiguration} catches every exception and returns an empty object, so a
	 * CouchDB blip is indistinguishable from "no settings" — and the cached layer then stores
	 * that empty object with NO expiry. One unreachable moment at startup therefore made every
	 * dynamic setting read as absent for the rest of the JVM's life, including
	 * {@code lineage.mode}, which falls back to {@code disabled} silently (external review).
	 *
	 * <p>It describes THIS read, not the stored document, and must never be persisted. What
	 * actually keeps it out of CouchDB today is that neither write path serialises this class:
	 * both {@code create} and {@code update} convert to {@code CouchConfiguration} first, which
	 * has no such field. {@code @JsonIgnore} is the guard for any path that serialises the model
	 * directly — and it is the one that matters, not {@code transient}: the couchdb mapper sets
	 * field visibility to ANY and {@code PROPAGATE_TRANSIENT_MARKER} is off by default, so a
	 * {@code transient} field with the annotation removed WOULD be written (external review).
	 * {@code transient} is kept for the Java-serialization and Gson paths, which do honour it.
	 */
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient boolean loadFailed;

	@com.fasterxml.jackson.annotation.JsonIgnore
	public boolean isLoadFailed() {
		return loadFailed;
	}

	public void setLoadFailed(boolean loadFailed) {
		this.loadFailed = loadFailed;
	}

	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}
}
