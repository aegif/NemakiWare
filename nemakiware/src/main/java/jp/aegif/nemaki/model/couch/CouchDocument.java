package jp.aegif.nemaki.model.couch;


import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

import jp.aegif.nemaki.model.Document;
@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchDocument extends CouchContent {

	private static final long serialVersionUID = 1993139506791735097L;

	// Attachment
	private String attachmentNodeId;
	private List<String> renditionIds;
	
	// Versioning
	private String versionSeriesId;
	private Boolean latestVersion;
	private Boolean latestMajorVersion;
	
	
	private Boolean majorVersion;
	private String checkinComment;
	private String versionLabel;
	
	//The following properties should be moved away to VersionSeries object
	private Boolean privateWorkingCopy;

	private Boolean updateSkip;	//FIXME Modify to go without it

	public CouchDocument(){
		super();
	}
	
	public CouchDocument(Document d){
		super(d);
		setAttachmentNodeId(d.getAttachmentNodeId());
		setRenditionIds(d.getRenditionIds());
		setVersionSeriesId(d.getVersionSeriesId());
		setVersionLabel(d.getVersionLabel());
		setIsLatestVersion(d.isLatestVersion());
		setMajorVersion(d.isMajorVersion());
		setLatestMajorVersion(d.isLatestMajorVersion());
		setCheckinComment(d.getCheckinComment());
		setPrivateWorkingCopy(d.isPrivateWorkingCopy());
		//setUpdateSkip(d.getUpdateSkip());
	}
	
	/**
	 * Getter & Setter
	 */
	public String getAttachmentNodeId() {
		return attachmentNodeId;
	}

	public void setAttachmentNodeId(String attachmentNodeId) {
		this.attachmentNodeId = attachmentNodeId;
	}
	
	public List<String> getRenditionIds() {
		return renditionIds;
	}

	public void setRenditionIds(List<String> renditionIds) {
		this.renditionIds = renditionIds;
	}

	public String getVersionSeriesId() {
		return versionSeriesId;
	}

	public void setVersionSeriesId(String versionSeriesId) {
		this.versionSeriesId = versionSeriesId;
	}
	
	@JsonProperty("latestVersion")
	public Boolean isLatestVersion() {
		return latestVersion;
	}
	@JsonProperty("latestVersion")
	public void setIsLatestVersion(Boolean latestVersion) {
		this.latestVersion = latestVersion;
	}

	public Boolean isMajorVersion() {
		return majorVersion;
	}

	public void setMajorVersion(Boolean majorVersion) {
		this.majorVersion = majorVersion;
	}

	public Boolean isLatestMajorVersion() {
		return latestMajorVersion;
	}

	public void setLatestMajorVersion(Boolean latestMajorVersion) {
		this.latestMajorVersion = latestMajorVersion;
	}

	public Boolean isPrivateWorkingCopy() {
		return privateWorkingCopy;
	}

	public void setPrivateWorkingCopy(Boolean privateWorkingCopy) {
		this.privateWorkingCopy = privateWorkingCopy;
	}

	
	public String getVersionLabel() {
		return versionLabel;
	}

	public void setVersionLabel(String versionLabel) {
		this.versionLabel = versionLabel;
	}

	public String getCheckinComment() {
		return checkinComment;
	}

	public void setCheckinComment(String checkinComment) {
		this.checkinComment = checkinComment;
	}

	public Boolean getUpdateSkip() {
		return updateSkip;
	}

	public void setUpdateSkip(Boolean updateSkip) {
		this.updateSkip = updateSkip;
	}

	public Document convert() {
		Document d = new Document(super.convert());
		d.setAttachmentNodeId(getAttachmentNodeId());
		d.setRenditionIds(getRenditionIds());
		d.setLatestVersion(isLatestVersion());
		d.setMajorVersion(isMajorVersion());
		d.setVersionSeriesId(getVersionSeriesId());
		d.setVersionLabel(getVersionLabel());
		d.setPrivateWorkingCopy(isPrivateWorkingCopy());
		//d.setUpdateSkip(getUpdateSkip());

		return d;
	}
}
