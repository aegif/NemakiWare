package jp.aegif.nemaki.dao.impl.couch.delegate;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
		mapper.setVisibility(PropertyAccessor.SETTER, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.CREATOR, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.GETTER, Visibility.ANY);
		mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.ANY);

		return mapper;
	}

	/**
	 * Build a log message with objectId prefix.
	 */
	public String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}
}
