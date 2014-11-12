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

import jp.aegif.nemaki.model.Group;

public class CouchGroup  extends CouchNodeBase{
	
	private static final long serialVersionUID = -5513898484272039889L;
	private String groupId;
	private String name;
	private List<String> users;
	private List<String> groups;
	
	public CouchGroup(){
		super();
	}

	public CouchGroup(Group g){
		super(g);
		setGroupId(g.getGroupId());
		setName(g.getName());
		setUsers(g.getUsers());
		setGroups(g.getGroups());
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

	public List<String> getUsers() {
		return users;
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

	public Group convert(){
		Group g = new Group(super.convert());
		g.setGroupId(getGroupId());
		g.setName(getName());
		g.setUsers(getUsers());
		g.setGroups(getGroups());
		return g;
	}
}
