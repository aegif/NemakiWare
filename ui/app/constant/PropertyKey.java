package constant;

public interface PropertyKey {
	final String PROPERTY_FILES = "property.files";

	final String PLAY_HTTP_CONTEXT = "play.http.context";
	final String PLAY_SERVER_HTTP_ADDRESS = "play.server.http.address";
	final String PLAY_SERVER_HTTP_PORT = "play.server.http.port";

	final String NEMAKI_CORE_URI= "nemaki.core.uri";
	final String NEMAKI_CORE_URI_PROTOCOL= "nemaki.core.uri.protocol";
	final String NEMAKI_CORE_URI_HOST= "nemaki.core.uri.host";
	final String NEMAKI_CORE_URI_PORT= "nemaki.core.uri.port";
	final String NEMAKI_CORE_URI_CONTEXT= "nemaki.core.uri.context";
	final String NEMAKI_CORE_URI_REPOSITORY= "nemaki.core.uri.repository";
	final String NEMAKI_CORE_URI_REST= "nemaki.core.uri.rest";

	final String NEMAKI_UI_URI_PROTOCOL= "nemaki.ui.uri.protocol";
	final String NEMAKI_UI_URI_HOST= "nemaki.ui.uri.host";
	final String NEMAKI_UI_URI_PORT= "nemaki.ui.uri.port";

	final String NEMAKI_DEFAULT_REPOSITRY_ID="nemaki.default.repository.id";

	final String NAVIGATION_PAGING_SIZE="navigation.paging.size";

	final String COMPRESSION_TARGET_MAXSIZE="compression.target.maxsize";
	final String COMPRESSION_FILE_PREFIX="compression.file.prefix";


	final String UI_VISIBILITY_CREATE_OBJECT="ui.visibility.create-cmis-object";
	final String UI_VISIBILITY_CREATE_RELATIONSHIP="ui.visibility.create-relationship";

	final String SSO_SAML_ATUTHENTICATION_ENABLE="sso.saml.enabled";
	final String SSO_LOGOUT_REDIRECT_URI="sso.logout.redirect.uri";
	final String SSO_HEADER_REMOTE_AUTHENTICATED_USER="sso.header.remote.authenticated.user";
	final String SSO_MAPPER_KEY_USERID="sso.mapper.key.userid";

}
