package jp.aegif.nemaki.cmis.factory.auth;

public interface TokenService {
	public Token getToken(String app, String userName);
	public Token setToken(String app, String userName);
	public boolean isAdmin(String userName);
}
