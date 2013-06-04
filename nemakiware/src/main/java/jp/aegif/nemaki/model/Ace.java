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

import java.util.List;

public class Ace {
	private String principalId;
	private List<String> permissions;
	private boolean objectOnly;
	
	public Ace(){
		
	}
	
	public Ace(String principalId, List<String> permissions, boolean objectOnly) {
		super();
		this.principalId = principalId;
		this.permissions = permissions;
		this.objectOnly = objectOnly;
	}

	public String getPrincipalId() {
		return principalId;
	}
	public void setPrincipalId(String principalId) {
		this.principalId = principalId;
	}
	public List<String> getPermissions() {
		return permissions;
	}
	public void setPermissions(List<String> permissions) {
		this.permissions = permissions;
	}

	public boolean isObjectOnly() {
		return objectOnly;
	}

	public void setObjectOnly(boolean objectOnly) {
		this.objectOnly = objectOnly;
	}
}
