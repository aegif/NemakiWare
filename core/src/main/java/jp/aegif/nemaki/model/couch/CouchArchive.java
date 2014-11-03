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

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchArchive extends CouchNodeBase{

	private static final long serialVersionUID = -1664675735259473057L;

	private String originalId;
	private String lastRevision;
	private String name;
	private String parentId;
	private Boolean deletedWithParent;
	private String path;
	private String attachmentNodeId;
	private List<String> renditionIds;
	private String versionSeriesId;
	private Boolean latestVersion;
	private String mimeType;
	
	public CouchArchive(){
		super();
	}
	
	public CouchArchive(Archive a){
		super(a);
		setOriginalId(a.getOriginalId());
		setLastRevision(a.getLastRevision());
		setName(a.getName());
		setParentId(a.getParentId());
		setDeletedWithParent(a.isDeletedWithParent());
		setAttachmentNodeId(a.getAttachmentNodeId());
		setRenditionIds(a.getRenditionIds());
		setVersionSeriesId(a.getVersionSeriesId());
		setLatestVersion(a.isLatestVersion());
		setMimeType(a.getMimeType());
	}
	
	/**
	 * Getter & Setter
	 */
	public String getOriginalId() {
		return originalId;
	}

	public void setOriginalId(String originalId) {
		this.originalId = originalId;
	}

	public String getLastRevision() {
		return lastRevision;
	}

	public void setLastRevision(String lastRevision) {
		this.lastRevision = lastRevision;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	@JsonProperty("deletedWithParent")
	public Boolean isDeletedWithParent() {
		return deletedWithParent;
	}

	public void setDeletedWithParent(Boolean deletedWithParent) {
		this.deletedWithParent = deletedWithParent;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

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

	public void setLatestVersion(Boolean isLatestVersion) {
		this.latestVersion = isLatestVersion;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				if(getId() != null) put("id", getId());
				if(getOriginalId() != null) put("originalId", getOriginalId());
				if(getLastRevision() != null) put("lastRevision", getLastRevision());
				if(getName() != null) put("name", getName());
				if(getType() != null) put("type", getType());
				if(getParentId() != null) put("parentId", getParentId());
				if(getPath() != null) put("path", getPath());
				if(getAttachmentNodeId() != null) put("nemakiAttachments", getAttachmentNodeId().toString());
				if(getVersionSeriesId() != null) put("versionSeriesId", getVersionSeriesId());
				if(isLatestVersion() != null) put("isLatestVersion", isLatestVersion());
				if(getCreated() != null) put("created", convertToDateFormat(getCreated()));
				if(getCreator() != null) put("creator", getCreator());
			}
		};
		return m.toString();
	}

	public String convertToDateFormat(GregorianCalendar cal) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss z");
		return sdf.format(cal.getTime());
	}

	@Override
	public boolean equals(Object obj) {
		return obj != null && obj instanceof Content
				&& ((Content) obj).getId().equals(this.getId());
	}

	@Override
	public int hashCode() {
		return this.getId().hashCode();
	}
	
	@JsonIgnore
	public Boolean isFolder(){
		if("folder".equals(type)){
			return true;
		}else{
			return false;
		}
	}
	@JsonIgnore
	public Boolean isDocument(){
		if("document".equals(type)){
			return true;
		}else{
			return false;
		}
	}
	@JsonIgnore
	public Boolean isAttachment(){
		if("attachment".equals(type)){
			return true;
		}else{
			return false;
		}
	}
	
	public Archive convert(){
		Archive a = new Archive(super.convert());
		a.setOriginalId(getOriginalId());
		a.setLastRevision(getLastRevision());
		a.setName(getName());
		a.setParentId(getParentId());
		a.setDeletedWithParent(isDeletedWithParent());
		a.setAttachmentNodeId(getAttachmentNodeId());
		a.setRenditionIds(getRenditionIds());
		a.setVersionSeriesId(getVersionSeriesId());
		a.setIsLatestVersion(isLatestVersion());
		a.setMimeType(getMimeType());
		
		return a;
	}
}
