package jp.aegif.nemaki.cmis.factory.auth;

public class Token {
		private String userName;
		private String token;
		private long expiration;
		
		public Token(){
			
		}
		
		public Token(String userName, String token, long expiration) {
			super();
			this.userName = userName;
			this.token = token;
			this.expiration = expiration;
		}
		
		public String getUserName() {
			return userName;
		}
		public void setUserName(String userName) {
			this.userName = userName;
		}
		public String getToken() {
			return token;
		}
		public void setToken(String token) {
			this.token = token;
		}
		public long getExpiration() {
			return expiration;
		}
		public void setExpiration(long expiration) {
			this.expiration = expiration;
		}
		
}
