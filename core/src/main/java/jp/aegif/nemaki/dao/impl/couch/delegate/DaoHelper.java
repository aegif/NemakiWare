package jp.aegif.nemaki.dao.impl.couch.delegate;

import tools.jackson.databind.ObjectMapper;


/**
 * Shared helper for DAO delegate classes.
 * Provides common ObjectMapper configuration used across all delegates.
 */
public class DaoHelper {

	/**
	 * The mapper the DAO delegates decode with.
	 *
	 * <p>The DEFINITION moved to {@link jp.aegif.nemaki.config.ObjectMapperFactory} (R52). It
	 * lived here and — byte for byte except the stored-timestamps module — in
	 * {@code ContentDaoServiceImpl} as well, so fixing one did not reach the other. That is the
	 * shape the factory's own javadoc has claimed not to have since Jackson 3 landed.
	 */
	public ObjectMapper createConfiguredObjectMapper() {
		return jp.aegif.nemaki.config.ObjectMapperFactory.createDaoDelegateObjectMapper();
	}

	/**
	 * Build a log message with objectId prefix.
	 */
	public String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}
}
