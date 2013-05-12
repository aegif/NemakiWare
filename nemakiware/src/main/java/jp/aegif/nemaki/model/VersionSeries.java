package jp.aegif.nemaki.model;


public class VersionSeries extends NodeBase{
	public static final String TYPE = "versionSeries";
	
	private Boolean versionSeriesCheckedOut;
	private String versionSeriesCheckedOutBy;
	private String versionSeriesCheckedOutId;
	
	public VersionSeries(){
		super();
	}
	
	public VersionSeries(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
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
}
