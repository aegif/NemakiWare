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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.User;

public class CouchUser extends CouchNodeBase{

	private static final long serialVersionUID = -77254842849407974L;
	private String userId;
	private String name;
	private String lastName;
	private String firstName;
	private String email;
	private String passwordHash;
	private Boolean admin;
	private Set<String> favorites;

	public CouchUser() {
		super();
	}

	public CouchUser(User u){
		super(u);
		setUserId(u.getUserId());
		setName(u.getName());
		setFirstName(u.getFirstName());
		setLastName(u.getLastName());
		setEmail(u.getEmail());
		setPasswordHash(u.getPasswordHash());
		setAdmin(u.isAdmin());
		setFavorites(u.getFavorites());
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public Boolean isAdmin() {
		return admin;
	}

	public void setAdmin(Boolean admin) {
		this.admin = admin;
	}

	public Set<String> getFavorites() {
		return favorites;
	}

	public void setFavorites(Set<String> favorites) {
		this.favorites = favorites;
	}

	@Override
	public User convert(){
		User u = new User(super.convert());
		u.setUserId(getUserId());
		u.setName(getName());
		u.setFirstName(getFirstName());
		u.setLastName(getLastName());
		u.setEmail(getEmail());
		u.setPasswordHash(getPasswordHash());
		u.setAdmin(isAdmin());
		u.setFavorites(getFavorites());
		return u;
	}
}
