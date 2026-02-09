package jp.aegif.nemaki.util.constant;

public interface SystemConst {
	public static final String NEMAKI_CONF_DB = "nemaki_conf";

	// XXX produces ISO 8601 colon-separated offset (+09:00) which is safely
	// parsed by all major JS engines. The previous Z pattern (+0900) caused
	// Invalid Date on Safari / older WebKit.
	public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
}
