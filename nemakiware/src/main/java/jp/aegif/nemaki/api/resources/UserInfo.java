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
package jp.aegif.nemaki.api.resources;

public class UserInfo {
		public String userId;
		public String password;
		public String[] roles;

		public UserInfo() {
			userId = null;
			password = null;
			roles = null;
		}

		public UserInfo(String userId, String password){
			this.userId = userId;
			this.password = password;
		}
		
		public boolean isInRole(String role) {
			for (int i = 0; i < roles.length; i++) {
				if (roles[i].equals(role)) {
					return true;
				}
			}
			return false;
		}

		public String getUserId() {
			return userId;
		}

		public void setUserId(String userId) {
			this.userId = userId;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String[] getRoles() {
			return roles;
		}

		public void setRoles(String[] roles) {
			this.roles = roles;
		}
		
		
}
