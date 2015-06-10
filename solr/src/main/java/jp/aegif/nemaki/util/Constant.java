package jp.aegif.nemaki.util;

public interface Constant {
	String MODE_FULL = "FULL";
	String MODE_DELTA = "DELTA";

	// Solr filed name
	String FIELD_ID = "id";
	String FIELD_NAME = "name";
	String FIELD_DESCRIPTION = "cmis_description";
	String FIELD_BASE_TYPE = "basetype";
	String FIELD_OBJECT_TYPE = "objecttype";
	String FIELD_SECONDARY_OBJECT_TYPE_IDS = "secondary_object_type_ids";
	String FIELD_CREATED = "created";
	String FIELD_CREATOR = "creator";
	String FIELD_MODIFIED = "modified";
	String FIELD_MODIFIER = "modifier";

	String FIELD_CONTENT_ID = "content_id";
	String FIELD_CONTENT_NAME = "content_name";
	String FIELD_CONTENT_MIMETYPE = "content_mimetype";
	String FIELD_CONTENT_LENGTH = "content_length";
	String FIELD_IS_MAJOR_VEERSION = "is_major_version";
	String FIELD_IS_PRIVATE_WORKING_COPY = "is_pwc";
	String FIELD_IS_CHECKEDOUT = "is_checkedout";
	String FIELD_CHECKEDOUT_BY = "checkedout_by";
	String FIELD_CHECKEDOUT_ID = "checkedout_id";
	String FIELD_CHECKIN_COMMENT = "checkein_comment";
	String FIELD_VERSION_LABEL = "version_label";
	String FIELD_VERSION_SERIES_ID = "version_series_id";

	String FIELD_PARENT_ID = "parent_id";
	String FIELD_PATH = "path";

	String FIELD_TOKEN = "change_token";

	String SEPARATOR = ".";
	
	//Auth token
	String AUTH_TOKEN = "nemaki_auth_token";
	String AUTH_TOKEN_APP = "nemaki_auth_token_app";
}
