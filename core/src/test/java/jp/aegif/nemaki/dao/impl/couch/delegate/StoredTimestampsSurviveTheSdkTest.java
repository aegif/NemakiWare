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
		assertEquals(WHEN, decoded.getCreated().getTimeInMillis(),
				"created changed on the way back");
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

		assertThrows(Exception.class,
				() -> mapper.readValue(mapper.writeValueAsString(doc), CouchArchive.class),
				"a fraction of a millisecond was accepted as an archive time");
	}
}
