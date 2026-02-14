package jp.aegif.nemaki.cmis.factory.auth;

public interface TokenService {
	public Token getToken(String app, String repositoryId, String userName);
	public Token setToken(String app, String repositoryId, String userName);
	
	/**
	 * Removes a token for the specified user.
	 * 
	 * @param app Application identifier (typically empty string for REST API)
	 * @param repositoryId Repository ID the token was issued for
	 * @param userName The username whose token should be removed
	 * @deprecated Use {@link #invalidateToken(String, String, String)} instead for explicit logout
	 */
	@Deprecated
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
	
	/**
	 * Invalidates a token for the specified user during logout.
	 * This method should be called when a user explicitly logs out to ensure
	 * the token cannot be reused even if it hasn't expired yet.
	 * 
	 * Default implementation delegates to removeToken() for backward compatibility.
	 * Implementations may override to add additional cleanup (e.g., blacklist, audit log).
	 * 
	 * @param app Application identifier (typically empty string for REST API)
	 * @param repositoryId Repository ID the token was issued for
	 * @param userName The username whose token should be invalidated
	 */
	default void invalidateToken(String app, String repositoryId, String userName) {
		removeToken(app, repositoryId, userName);
	}
	
	public boolean isAdmin(String repositoryId, String userName);
}
