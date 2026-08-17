package jp.aegif.nemaki.dao.impl.couch.delegate;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared helper for DAO delegate classes.
 * Provides common ObjectMapper configuration used across all delegates.
 */
public class DaoHelper {

	/**
	 * Creates a properly configured ObjectMapper for Cloudant/CouchDB serialization.
	 * This ensures all fields from the object hierarchy are properly serialized.
	 */
	public ObjectMapper createConfiguredObjectMapper() {
		return JsonMapper.builderWithJackson2Defaults()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.changeDefaultVisibility(vc -> vc
						.withVisibility(PropertyAccessor.ALL, Visibility.NONE)
						.withVisibility(PropertyAccessor.SETTER, Visibility.ANY)
						.withVisibility(PropertyAccessor.CREATOR, Visibility.ANY)
						.withVisibility(PropertyAccessor.GETTER, Visibility.ANY)
						.withVisibility(PropertyAccessor.IS_GETTER, Visibility.ANY))
				.build();
	}

	/**
	 * Build a log message with objectId prefix.
	 */
	public String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}
}
