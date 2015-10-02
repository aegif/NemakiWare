package jp.aegif.nemaki.util;

public interface PropertyKey {
	public final String SOLR_CORE_MAIN = "solr.core.main";
	public final String SOLR_CORE_TOKEN = "solr.core.token";
	public final String SOLR_TRACKING_CRON_ENABLED = "solr.tracking.cron.enabled";
	public final String SOLR_TRACKING_CRON_EXPRESSION = "solr.tracking.cron.expression";
	public final String SOLR_TRACKING_FULLTEXT_ENABLED = "solr.tracking.fulltext.enabled";
	public final String SOLR_TRACKING_MIMETYPE_FILTER_ENABLED = "solr.tracking.mimetype.filter.enabled";
	public final String SOLR_TRACKING_MIMETYPE = "solr.tracking.mimetype";
	public final String SOLR_TRACKING_NUMBER_OF_THREAD = "solr.tracking.number.of.thread";

	public final String CMIS_SERVER_PROTOCOL = "cmis.server.protocol";
	public final String CMIS_SERVER_HOST = "cmis.server.host";
	public final String CMIS_SERVER_PORT = "cmis.server.port";
	public final String CMIS_SERVER_CONTEXT = "cmis.server.context";
	public final String CMIS_SERVER_WS_ENDPOINT = "cmis.server.ws.endpoint";
	public final String CMIS_CHANGELOG_ITEMS_DELTA = "cmis.changelog.items.delta";
	public final String CMIS_CHANGELOG_ITEMS_FULL = "cmis.changelog.items.full";
	public final String CMIS_LOCALE_COUNTRY = "cmis.locale.country";
	public final String CMIS_LOCALE_LANGUAGE = "cmis.locale.language";
	public final String CMIS_REPOSITORY_MAIN = "cmis.repository.main";

	public final String NEMAKI_CAPABILITY_EXTENDED_AUTH_TOKEN = "nemaki.capability.extended.auth.token";
	
	public final String REPOSITORIES_SETTING_FILE = "repositories.setting.file";
	
	public final String OVERRIDE_FILES = "override.files";
}
