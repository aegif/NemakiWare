/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * CMIS document object<br/>
 * This can be filed by folder object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Document extends Content {

	public static final String TYPE = "document";

	// Attachment
		private String attachmentNodeId;
		private List<String> renditionIds;
		
		// Versioning
		private String versionSeriesId;
		
		private Boolean latestVersion;
		private Boolean majorVersion;
		private Boolean latestMajorVersion;
		private String checkinComment;
		private String versionLabel;
		
		//The following properties should be moved away to VersionSeries object
		private Boolean privateWorkingCopy;

		private Boolean updateSkip;	//FIXME Modify to go without it
	
	public Document(){
		super();
	}
	
	public Document(Content c){
		super(c);
		setName(c.getName());
		setDescription(c.getDescription());
		setParentId(c.getParentId());
		setAcl(c.getAcl());
		setAclInherited(c.isAclInherited());
		setAspects(c.getAspects());
		setSecondaryIds(c.getSecondaryIds());
		setObjectType(c.getObjectType());
		setChangeToken(c.getChangeToken());
	}
	
	/**
	 * Getter & Setter
	 * @return
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
	
	public Boolean isLatestVersion() {
		return latestVersion;
	}
	public void setLatestVersion(Boolean latestVersion) {
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
		return (privateWorkingCopy == null) ? false : privateWorkingCopy;
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

	/*public Boolean getUpdateSkip() {
		return updateSkip;
	}

	public void setUpdateSkip(Boolean updateSkip) {
		this.updateSkip = updateSkip;
	}*/
	
	
	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("name", getName());
				put("type", getType());
				put("creator", getCreator());
				put("created", getCreated());
				put("modifier", getModifier());
				put("modified", getModified());
				put("parentId", getParentId());
				put("aspects", getAspects());
			}
		};
		return m.toString();
	}
}
