package jp.aegif.nemaki.repository.auth;

public interface TokenService {
	public String getToken(String userName);
	public String setToken(String userName);
	public boolean isAdmin(String userName);
	public void setAdmin(String userName);
}
