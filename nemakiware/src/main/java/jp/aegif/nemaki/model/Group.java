/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.constant.NodeType;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * CMIS group.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Group extends NodeBase {

	@SuppressWarnings("unused")
	private static final long serialVersionUID = 3444548324020557989L;
	
	private String groupId;
	private String name;
	private List<String> users;
	private List<String> groups;

	public Group(){
		super();
		setType(NodeType.GROUP.value());
	}
	
	public Group(NodeBase n){
		super();
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public Group(String groupId, String name, List<String> users,
			List<String> groups) {
		super();
		setType(NodeType.GROUP.value());
		this.groupId = groupId;
		this.name = name;
		this.users = users;
		this.groups = groups;
	}
	
	/*
	 * Getters/Setters
	 */
	public List<String> getUsers() {
		return users;
	}

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setUsers(List<String> users) {
		this.users = users;
	}

	public List<String> getGroups() {
		return groups;
	}

	public void setGroups(List<String> groups) {
		this.groups = groups;
	}
	
	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("name", getName());
				put("type", getType());
				put("users", getUsers());
				put("groups", getGroups());
			}
		};
		return m.toString();
	}
}
