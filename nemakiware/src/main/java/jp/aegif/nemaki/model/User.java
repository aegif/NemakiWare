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
import java.util.Map;

import jp.aegif.nemaki.model.constant.NodeType;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * Nemaki user.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class User extends NodeBase {

	@SuppressWarnings("unused")
	private static final long serialVersionUID = 4372606781795402581L;

	private String userId;
	private String name;
	private String lastName;
	private String firstName;
	private String email;
	private String passwordHash;
	
	public User() {
		super();
		setType(NodeType.USER.value());
	}

	public User(String id, String name, String firstName, String lastName,
			String email, String passwordHash) {
		setType(NodeType.USER.value());
		setUserId(id);
		setName(name);
		setFirstName(firstName);
		setLastName(lastName);
		setEmail(email);
		setPasswordHash(passwordHash);
	}
	
	public User(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	/*
	 * Getters/Setters
	 */
	public String getName() {
		return name;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public void setName(String name) {
		this.name = name;
	}
	
	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("name", getName());
				put("type", getType());
				put("email", getEmail());
				put("lastName", getLastName());
				put("firstName", getFirstName());
				put("passwordHash", getPasswordHash());
			}
		};
		return m.toString();
	}

}
