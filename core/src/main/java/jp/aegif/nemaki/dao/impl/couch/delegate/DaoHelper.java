package jp.aegif.nemaki.dao.impl.couch.delegate;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.GregorianCalendar;
import java.util.TimeZone;

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
				.addModule(storedTimestampsModule())
				.changeDefaultVisibility(vc -> vc
						.withVisibility(PropertyAccessor.ALL, Visibility.NONE)
						.withVisibility(PropertyAccessor.SETTER, Visibility.ANY)
						.withVisibility(PropertyAccessor.CREATOR, Visibility.ANY)
						.withVisibility(PropertyAccessor.GETTER, Visibility.ANY)
						.withVisibility(PropertyAccessor.IS_GETTER, Visibility.ANY))
				.build();
	}

	/**
	 * Lets a stored timestamp be read back after the Cloudant SDK has widened it.
	 *
	 * <p>This mapper WRITES a {@code GregorianCalendar} as epoch millis, and CouchDB stores that
	 * as a JSON integer — verified on a live archive row ({@code archivedAt: 1786536650704}).
	 * But the SDK hands a document back as {@code Map<String, Object>}
	 * ({@code Document#getProperties}, {@code ViewResultRow#getValue}), and every JSON number in
	 * such a map arrives as a {@code Double}. Re-serialising that map writes
	 * {@code 1.786536650704E12}, and Jackson's own calendar deserialiser accepts
	 * {@code VALUE_NUMBER_INT} only: the read then failed with "Cannot deserialize value of type
	 * java.util.GregorianCalendar from Floating-point value". The same mapper could write the
	 * row and not read it back.
	 *
	 * <p>What that cost: every archive row decoded through those maps counted as unreadable. The
	 * trash listing served an empty page (before this branch made it refuse) or a 503 (after) —
	 * for a database whose rows are intact. Measured on a tree holding 716,821 archive rows.
	 *
	 * <p>This only WIDENS what is accepted — an integer, a string and a null are handled exactly
	 * as before, by the deserialiser this one delegates the remaining shapes to. A float that is
	 * not a whole number of milliseconds is still not a timestamp we wrote, and is refused.
	 */
	private static SimpleModule storedTimestampsModule() {
		SimpleModule module = new SimpleModule("nemaki-stored-timestamps");
		module.addDeserializer(GregorianCalendar.class, new ValueDeserializer<GregorianCalendar>() {
			@Override
			public GregorianCalendar deserialize(JsonParser p, DeserializationContext ctxt) {
				if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
					double widened = p.getDoubleValue();
					if (widened != Math.floor(widened) || Double.isInfinite(widened)) {
						// Not a whole millisecond: this is not a timestamp this code wrote, and
						// guessing one would put a made-up instant on a record.
						return (GregorianCalendar) ctxt.handleUnexpectedToken(
								GregorianCalendar.class, p);
					}
					GregorianCalendar restored = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
					restored.setTimeInMillis((long) widened);
					return restored;
				}
				// Every other shape keeps Jackson's own behaviour, including the refusals.
				return ctxt.readValue(p, java.util.Calendar.class) instanceof GregorianCalendar gc
						? gc : null;
			}
		});
		return module;
	}

	/**
	 * Build a log message with objectId prefix.
	 */
	public String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}
}
