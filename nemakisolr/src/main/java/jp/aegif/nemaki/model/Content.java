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
package jp.aegif.nemaki.model;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.ektorp.support.CouchDbDocument;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Content extends CouchDbDocument{

	private static final long serialVersionUID = -3546167145767750470L;

	/**
	 * Displayed name of the content. For instance: "請求書 (過去)"
	 */
	private String name;

	/**
	 * Type of the content. For instance: "folder"
	 */
	private String type;

	/**
	 * Date this content was created.
	 */
	private GregorianCalendar created;

	/**
	 * Creator of this content For instance: "jiro"
	 */
	private String creator;

	/**
	 * Date this content was last modified.
	 */
	private GregorianCalendar modified;

	/**
	 * Name of the last person who modified this content. For instance: "jiro"
	 */
	private String modifier;

	/**
	 * Identifier of the parent of this content.
	 */
	private String parentId;

	/**
	 * Set of permissions detailing who can be what with this content.
	 */
	private Permission permission;

	/**
	 * Path to this content. Not implemented yet.
	 */
	private String path;

	/**
	 * File attachments belonging to this content.
	 */
	private String attachmentNodeId;

	/**
	 * Length of this content.
	 */
	private long length;

	/**
	 * MIME type of this content.
	 */
	private String mimeType;

	// TODO should be tested for thread safety
	private Role role = Role.CONSUMER;

	/**
	 * Aspect for this content
	 */
	private List<Aspect> aspects = new ArrayList<Aspect>();

	private Boolean latestVersion;
	
	
	/*
	 * Getters/Setters
	 */
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public GregorianCalendar getCreated() {
		return created;
	}

	public void setCreated(GregorianCalendar created) {
		this.created = created;
	}

	public String getCreator() {
		return creator;
	}

	public void setCreator(String creator) {
		this.creator = creator;
	}

	public GregorianCalendar getModified() {
		return modified;
	}

	public void setModified(GregorianCalendar modified) {
		this.modified = modified;
	}

	public String getModifier() {
		return modifier;
	}

	public void setModifier(String modifier) {
		this.modifier = modifier;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	public Permission getPermission() {
		return permission;
	}

	public void setPermission(Permission permission) {
		this.permission = permission;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	public String getAttachmentNodeId() {
		return attachmentNodeId;
	}

	public void setAttachmentNodeId(String attachmentNodeId) {
		this.attachmentNodeId = attachmentNodeId;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public long getLength() {
		return length;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public List<Aspect> getAspects() {
		return aspects;
	}

	public void setAspects(List<Aspect> aspects) {
		this.aspects = aspects;
	}
	
	public Boolean isLatestVersion() {
		return latestVersion;
	}

	public void setLatestVersion(Boolean latestVersion) {
		this.latestVersion = latestVersion;
	}

	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("revision", getRevision());
				put("name", getName());
				put("type", getType());
				put("creator", getCreator());
				put("created", getCreated());
				put("modifier", getModifier());
				put("modified", getModified());
				put("parentId", getParentId());
				put("path", getPath());
				put("role", getRole());
				put("length", getLength());
				put("mimeType", getMimeType());
				put("aspects", getAspects().toString());
			}
		};
		return m.toString();
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
	
	public boolean isDocument(){
		return (type.equals("cmis:document"))? true : false;
	}

}
