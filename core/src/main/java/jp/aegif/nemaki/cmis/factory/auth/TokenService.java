package jp.aegif.nemaki.cmis.factory.auth;

public interface TokenService {
	public Token getToken(String app, String repositoryId, String userName);
	public Token setToken(String app, String repositoryId, String userName);
	public void removeToken(String app, String repositoryId, String userName);
	
	/**
	 * Validates a Bearer token and returns the associated username.
	 * 
	 * @param app Application identifier (typically empty string for REST API)
	 * @param repositoryId Repository ID the token was issued for
	 * @param tokenString The Bearer token string to validate
	 * @return The username associated with the token if valid and not expired, null otherwise
	 */
	public String validateToken(String app, String repositoryId, String tokenString);
	
	public boolean isAdmin(String repositoryId, String userName);
}
