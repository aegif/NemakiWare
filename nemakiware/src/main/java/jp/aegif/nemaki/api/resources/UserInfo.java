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
