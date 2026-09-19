package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.couch.CouchArchive;
import tools.jackson.databind.ObjectMapper;

/**
 * A stored timestamp must survive the round trip the SDK puts it through.
 *
 * <p>The mapper writes a {@code GregorianCalendar} as epoch millis and CouchDB stores a JSON
 * integer (verified on a live row: {@code archivedAt: 1786536650704}). The Cloudant SDK hands
 * documents back as {@code Map<String, Object>}, where every JSON number is a {@code Double};
 * re-serialising that map writes {@code 1.786536650704E12}. Jackson's own calendar deserialiser
 * takes {@code VALUE_NUMBER_INT} only, so the read failed — the same mapper could write the row
 * and not read it back, and every archive row decoded through such a map counted as unreadable
 * (R51, found by the 3.4 release gate on a tree holding 716,821 of them).
 */
class StoredTimestampsSurviveTheSdkTest {

	private static final long WHEN = 1786536650704L;

	private ObjectMapper mapper() {
		return new DaoHelper().createConfiguredObjectMapper();
	}

	/** A document as the SDK hands it over: every JSON number widened to Double. */
	private Map<String, Object> asTheSdkHandsItOver() {
		Map<String, Object> doc = new HashMap<>();
		doc.put("_id", "a1");
		doc.put("_rev", "1-abc");
		doc.put("type", "cmis:document");
		doc.put("name", "deleted.txt");
		doc.put("archivedAt", (double) WHEN);
		doc.put("created", (double) WHEN);
		doc.put("modified", (double) WHEN);
		return doc;
	}

	@Test
	@DisplayName("a timestamp the SDK widened to a float is read back as the instant we wrote")
	void aWidenedTimestampIsReadBack() {
		ObjectMapper mapper = mapper();
		String json = mapper.writeValueAsString(asTheSdkHandsItOver());

		// assertDoesNotThrow, not a bare readValue: without the module the read raises
		// Jackson's own exception, and a lock that lets it escape fails on the harness rather
		// than on its own assertion — the runner does not count that as a firing.
		CouchArchive decoded = assertDoesNotThrow(
				() -> mapper.readValue(json, CouchArchive.class),
				"a row whose timestamps the SDK widened could not be read back");

		assertNotNull(decoded, "the row did not decode at all");
		assertNotNull(decoded.getArchivedAt(), "archivedAt did not survive the round trip");
		assertEquals(WHEN, decoded.getArchivedAt().getTimeInMillis(),
				"the instant changed on the way back");
		// archivedAt only. created/modified reach the model through
		// CouchNodeBase.setCreated(Object), which has always parsed numbers itself — asserting
		// on them here would read as evidence about this module and is not. A review found the
		// claim. The properties this module actually decodes are archivedAt and coldArchivedAt.
	}

	@Test
	@DisplayName("an integer timestamp still reads exactly as it did")
	void anIntegerTimestampStillReads() {
		// The over-throw guard: the fix must only WIDEN what is accepted.
		ObjectMapper mapper = mapper();
		Map<String, Object> doc = asTheSdkHandsItOver();
		doc.put("archivedAt", WHEN);
		doc.put("created", WHEN);
		doc.put("modified", WHEN);

		String json = mapper.writeValueAsString(doc);
		CouchArchive decoded = assertDoesNotThrow(
				() -> mapper.readValue(json, CouchArchive.class),
				"an integer timestamp stopped being readable");

		assertEquals(WHEN, decoded.getArchivedAt().getTimeInMillis(),
				"an integer timestamp stopped reading the way it always has");
	}

	@Test
	@DisplayName("an absent timestamp is still absent, not an invented instant")
	void anAbsentTimestampStaysAbsent() {
		ObjectMapper mapper = mapper();
		Map<String, Object> doc = asTheSdkHandsItOver();
		doc.remove("archivedAt");

		CouchArchive decoded = mapper.readValue(mapper.writeValueAsString(doc), CouchArchive.class);

		assertNull(decoded.getArchivedAt(),
				"a row that names no archive time was given one");
	}

	@Test
	@DisplayName("a float that is not a whole millisecond is still refused")
	void aFractionalNumberIsStillRefused() {
		// The other half of the guard: accepting ANY float would turn a number that is not a
		// timestamp we wrote into a made-up instant on a record.
		ObjectMapper mapper = mapper();
		Map<String, Object> doc = asTheSdkHandsItOver();
		doc.put("archivedAt", 1786536650704.5);

		// The guard's own refusal, not "something went wrong": handleUnexpectedToken raises
		// MismatchedInputException, and a bare Exception would also pass if Jackson refused for
		// its own reasons — which is exactly what happens with the module removed.
		assertThrows(tools.jackson.databind.exc.MismatchedInputException.class,
				() -> mapper.readValue(mapper.writeValueAsString(doc), CouchArchive.class),
				"a fraction of a millisecond was accepted as an archive time");
	}

	@Test
	@DisplayName("a number too large for a double to hold exactly is refused, not saturated")
	void aNumberBeyondExactIntegersIsRefused() {
		// A review found this half: integrality alone is not enough. 1.0E20 is a whole number,
		// and (long) saturates it to Long.MAX_VALUE — so a value that names no instant we wrote
		// would have read as an ordinary date. Past 2^53 the SDK's widening has also already
		// dropped the last digits, so the number cannot name the stored instant either way.
		ObjectMapper mapper = mapper();
		Map<String, Object> doc = asTheSdkHandsItOver();
		doc.put("archivedAt", 1.0E20);

		assertThrows(tools.jackson.databind.exc.MismatchedInputException.class,
				() -> mapper.readValue(mapper.writeValueAsString(doc), CouchArchive.class),
				"a number a double cannot hold exactly was read as a date");
	}

	@Test
	@DisplayName("a timestamp just inside the exact range still reads")
	void aTimestampInsideTheExactRangeStillReads() {
		// The over-throw guard for the range check: the limit must not refuse real timestamps.
		// Epoch millis are ~1.8E12, four orders of magnitude below 2^53.
		ObjectMapper mapper = mapper();
		Map<String, Object> doc = asTheSdkHandsItOver();
		doc.put("archivedAt", 9007199254740992.0);

		String json = mapper.writeValueAsString(doc);
		CouchArchive decoded = assertDoesNotThrow(
				() -> mapper.readValue(json, CouchArchive.class),
				"the largest exactly-held integer was refused");
		assertEquals(9007199254740992L, decoded.getArchivedAt().getTimeInMillis(),
				"the instant changed at the edge of the exact range");
	}
}
