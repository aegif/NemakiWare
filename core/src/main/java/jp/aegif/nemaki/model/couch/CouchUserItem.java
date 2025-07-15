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

import jp.aegif.nemaki.model.UserItem;

public class CouchUserItem extends CouchItem{
	
	private static final long serialVersionUID = 3294975060332894322L;

	private String userId;
	private String passwordHash;
	private Boolean admin = false;
	
	public CouchUserItem(){
		super();
	}
	
	public CouchUserItem(UserItem userItem){
		super(userItem);
		setUserId(userItem.getUserId());
		setPasswordHash(userItem.getPassword());
		setAdmin(userItem.isAdmin());
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
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

	public void setAdmin(Boolean isAdmin) {
		this.admin = isAdmin;
	}

	public UserItem convert(){
		UserItem userItem = new UserItem(super.convert());
		userItem.setUserId(getUserId());
		userItem.setPassword(getPasswordHash());
		userItem.setAdmin(isAdmin());
		
		return userItem;
	}
}
