package constant;

public interface PropertyKey {
	final String PROPERTY_FILES = "property.files";

	final String NEMAKI_CORE_URI= "nemaki.core.uri";
	final String NEMAKI_CORE_URI_PROTOCOL= "nemaki.core.uri.protocol";
	final String NEMAKI_CORE_URI_HOST= "nemaki.core.uri.host";
	final String NEMAKI_CORE_URI_PORT= "nemaki.core.uri.port";
	final String NEMAKI_CORE_URI_CONTEXT= "nemaki.core.uri.context";
	final String NEMAKI_CORE_URI_REPOSITORY= "nemaki.core.uri.repository";
	final String NEMAKI_CORE_URI_REST= "nemaki.core.uri.rest";

	final String NAVIGATION_PAGING_SIZE="navigation.paging.size";

	final String COMPRESSION_TARGET_MAXSIZE="compression.target.maxsize";
	final String COMPRESSION_FILE_PREFIX="compression.file.prefix";


	final String UI_VISIBILITY_CREATE_OBJECT="ui.visibility.create-cmis-object";
	final String UI_VISIBILITY_CREATE_RELATIONSHIP="ui.visibility.create-relationship";

	final String SSO_LOGOUT_REDIRECT_URI="sso.logout.redirect.uri";
	final String SSO_LOGIN_REDIRECT_URI="sso.login.redirect.uri";
	final String SSO_HEADER_REMOTE_AUTHENTICATED_USER="sso.header.remote.authenticated.user";

}
