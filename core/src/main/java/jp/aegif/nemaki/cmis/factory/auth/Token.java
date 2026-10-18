package jp.aegif.nemaki.cmis.factory.auth;

public class Token {
		private String userName;
		private String token;
		private long expiration;

		/**
		 * The id of the UserItem DOCUMENT this token was issued for (R170, 3.4.1).
		 *
		 * <p>{@link #userName} is a NAME: deleting a user and creating the same name again let the
		 * old token authenticate as the new account. The login compares this instead.
		 */
		private String accountId;

		/**
		 * The account's credential generation when this token was issued (R170, 3.4.1).
		 *
		 * <p>Compared for session tokens, unlike API keys: changing a password is meant to end the
		 * sessions it opened (owner's decision, 2026-10-16). A generation and not a timestamp —
		 * two tokens issued in the same millisecond compare equal, and replica clock skew can make
		 * a token issued BEFORE the change carry a later time.
		 */
		private Long credentialGeneration;

		/** When this token was issued; kept for diagnosis, never compared. */
		private long issuedAt;
		
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
		public String getAccountId() {
			return accountId;
		}
		public void setAccountId(String accountId) {
			this.accountId = accountId;
		}
		public Long getCredentialGeneration() {
			return credentialGeneration;
		}
		public void setCredentialGeneration(Long credentialGeneration) {
			this.credentialGeneration = credentialGeneration;
		}
		public long getIssuedAt() {
			return issuedAt;
		}
		public void setIssuedAt(long issuedAt) {
			this.issuedAt = issuedAt;
		}
		public long getExpiration() {
			return expiration;
		}
		public void setExpiration(long expiration) {
			this.expiration = expiration;
		}
		
}
