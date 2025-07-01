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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model.couch;


import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jp.aegif.nemaki.model.Document;

@JsonDeserialize(as = CouchDocument.class)
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

	private Boolean immutable;
	
	public CouchDocument(){
		super();
	}
	
	// Mapベースのコンストラクタを追加（Cloudant Document変換用）
	@JsonCreator
	public CouchDocument(Map<String, Object> properties) {
		super(properties); // 親クラスのMapコンストラクタを呼び出し
		
		if (properties != null) {
			// CouchDocument固有のフィールドマッピング
			this.attachmentNodeId = (String) properties.get("attachmentNodeId");
			this.versionSeriesId = (String) properties.get("versionSeriesId");
			this.versionLabel = (String) properties.get("versionLabel");
			this.checkinComment = (String) properties.get("checkinComment");
			
			// List型の処理
			if (properties.containsKey("renditionIds")) {
				Object renditionIdsValue = properties.get("renditionIds");
				if (renditionIdsValue instanceof List) {
					this.renditionIds = (List<String>) renditionIdsValue;
				}
			}
			
			// Boolean型の処理
			if (properties.containsKey("latestVersion")) {
				Object value = properties.get("latestVersion");
				this.latestVersion = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
			}
			if (properties.containsKey("latestMajorVersion")) {
				Object value = properties.get("latestMajorVersion");
				this.latestMajorVersion = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
			}
			if (properties.containsKey("majorVersion")) {
				Object value = properties.get("majorVersion");
				this.majorVersion = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
			}
			if (properties.containsKey("privateWorkingCopy")) {
				Object value = properties.get("privateWorkingCopy");
				this.privateWorkingCopy = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
			}
			if (properties.containsKey("immutable")) {
				Object value = properties.get("immutable");
				this.immutable = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
			}
		}
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
		setImmutable(d.isImmutable());
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

	public Boolean isImmutable(){
		return immutable;
	}
	
	public void setImmutable(Boolean immutable){
		this.immutable = immutable;
	}
	
	public Document convert() {
		Document d = new Document(super.convert());
		d.setAttachmentNodeId(getAttachmentNodeId());
		d.setRenditionIds(getRenditionIds());
		d.setLatestVersion(isLatestVersion());
		d.setMajorVersion(isMajorVersion());
		d.setLatestMajorVersion(isLatestMajorVersion());
		d.setVersionSeriesId(getVersionSeriesId());
		d.setVersionLabel(getVersionLabel());
		d.setPrivateWorkingCopy(isPrivateWorkingCopy());
		d.setCheckinComment(getCheckinComment());
		d.setImmutable(isImmutable());

		return d;
	}
}
