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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jp.aegif.nemaki.util.constant.NodeType;

/**
 * CMIS folder object
 * This can file folder/document object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Folder extends Content {

	private List<String> allowedChildTypeIds;
	private List<String> renditionIds;


	public Folder(){
		super();
		setType(NodeType.CMIS_FOLDER.value());
	}

	public Folder(Content c){
		super(c);
		setName(c.getName());
		setDescription(c.getDescription());
		setParentId(c.getParentId());
		setAcl(c.getAcl());
		setAclInherited(c.isAclInherited());
		setSubTypeProperties(c.getSubTypeProperties());
		setAspects(c.getAspects());
		setSecondaryIds(c.getSecondaryIds());
		setObjectType(c.getObjectType());
		setChangeToken(c.getChangeToken());
	}

	public List<String> getAllowedChildTypeIds() {
		return allowedChildTypeIds;
	}

	public void setAllowedChildTypeIds(List<String> allowedChildTypeIds) {
		this.allowedChildTypeIds = allowedChildTypeIds;
	}

	public List<String> getRenditionIds() {
		return renditionIds;
	}

	public void setRenditionIds(List<String> renditionIds) {
		this.renditionIds = renditionIds;
	}

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
			}
		};
		return m.toString();
	}

}
