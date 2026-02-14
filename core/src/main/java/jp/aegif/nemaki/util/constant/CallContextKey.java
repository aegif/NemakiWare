package jp.aegif.nemaki.util.constant;

public interface CallContextKey {
	final String IS_ADMIN = "is_admin";
	final String IS_SU = "is_su";
	
	//Auth token
	final String AUTH_TOKEN = "nemaki_auth_token";
	final String AUTH_TOKEN_APP = "nemaki_auth_token_app";

	// API Key for persistent authentication
	final String API_KEY = "nemaki_api_key";
}