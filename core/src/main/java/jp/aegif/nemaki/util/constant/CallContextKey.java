package jp.aegif.nemaki.util.constant;

public interface CallContextKey {
	final String IS_ADMIN = "is_admin";
	
	//Auth token
	final String AUTH_TOKEN = "nemaki_auth_token";
	final String AUTH_TOKEN_APP = "nemaki_auth_token_app";
	
	//REST token
	final String REST_REPOSITORY_ID_FOR_AUTH = "rest_repository_id_for_auth";
}