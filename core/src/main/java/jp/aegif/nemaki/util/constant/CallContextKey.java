package jp.aegif.nemaki.util.constant;

public interface CallContextKey {
	final String IS_ADMIN = "is_admin";
	final String IS_SU = "is_su";
	
	//Auth token
	final String AUTH_TOKEN = "nemaki_auth_token";
	final String AUTH_TOKEN_APP = "nemaki_auth_token_app";
	
	final String OIDC_TOKEN = "oidc_token";
	final String OIDC_USER_INFO = "oidc_user_info";
	
	final String SAML_RESPONSE = "saml_response";
	final String SAML_USER_ATTRIBUTES = "saml_user_attributes";
}
