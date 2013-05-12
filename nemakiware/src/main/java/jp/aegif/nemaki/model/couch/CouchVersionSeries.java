package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.VersionSeries;

import org.codehaus.jackson.annotate.JsonProperty;

public class CouchVersionSeries extends CouchNodeBase{
	private static final long serialVersionUID = 3137549058772266715L;
	private Boolean versionSeriesCheckedOut;
	private String versionSeriesCheckedOutBy;
	private String versionSeriesCheckedOutId;
	
	public CouchVersionSeries(){
		super();
	}
	
	public CouchVersionSeries(VersionSeries vs){
		super(vs);
		setVersionSeriesCheckedOut(vs.isVersionSeriesCheckedOut());
		setVersionSeriesCheckedOutBy(vs.getVersionSeriesCheckedOutBy());
		setVersionSeriesCheckedOutId(vs.getVersionSeriesCheckedOutId());
	}
	
	@JsonProperty("versionSeriesCheckedOut")
	public Boolean isVersionSeriesCheckedOut() {
		return versionSeriesCheckedOut;
	}
	public void setVersionSeriesCheckedOut(Boolean versionSeriesCheckedOut) {
		this.versionSeriesCheckedOut = versionSeriesCheckedOut;
	}
	public String getVersionSeriesCheckedOutBy() {
		return versionSeriesCheckedOutBy;
	}
	public void setVersionSeriesCheckedOutBy(String versionSeriesCheckedOutBy) {
		this.versionSeriesCheckedOutBy = versionSeriesCheckedOutBy;
	}
	public String getVersionSeriesCheckedOutId() {
		return versionSeriesCheckedOutId;
	}
	public void setVersionSeriesCheckedOutId(String versionSeriesCheckedOutId) {
		this.versionSeriesCheckedOutId = versionSeriesCheckedOutId;
	}

	public VersionSeries convert(){
		VersionSeries vs = new VersionSeries(super.convert());
		vs.setVersionSeriesCheckedOut(isVersionSeriesCheckedOut());
		vs.setVersionSeriesCheckedOutBy(getVersionSeriesCheckedOutBy());
		vs.setVersionSeriesCheckedOutId(getVersionSeriesCheckedOutId());
		return vs;
	}
	
}
