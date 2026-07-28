package jp.aegif.nemaki.model.couch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.epoch.AclEpochState;
import jp.aegif.nemaki.epoch.ContentIncarnation;
import jp.aegif.nemaki.model.Document;

/**
 * §11.1 (increment 12 step 0): the ACL-epoch fields must survive the MODEL round-trip.
 *
 * <p>The erasure mechanism under test is the real one: {@code ContentDaoServiceImpl.update} builds a
 * FRESH {@code CouchDocument} from the model and {@code CloudantClientWrapper.update} serializes it
 * with {@code ObjectMapper.convertValue(document, Map.class)} — so this test drives exactly that
 * pipeline: stored JSON map → {@code CouchDocument(Map)} → {@code convert()} → model mutation (a
 * rename) → {@code new CouchDocument(model)} → {@code convertValue} → assert the emitted map.
 *
 * <p>Two directions, both required (review note on the design):
 * <ul>
 *   <li>a document CARRYING the fields keeps them VERBATIM through a rename, and</li>
 *   <li>a document WITHOUT them still has none afterwards — nothing may MINT a marker, or a
 *       rename would spontaneously create epoch state the outbox never asked for.</li>
 * </ul>
 * {@code contentIncarnation} is the deliberate contrast: assign-once (minted when absent — create /
 * legacy lazy fill), verbatim when present. Before this increment the model never carried it, so
 * EVERY update re-minted it and each rename silently started a new content "lifetime".
 */
public class CouchContentEpochRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> storedDoc(boolean withEpochFields, Object quarantineMarker,
            String incarnation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_id", "doc-1");
        m.put("_rev", "3-abc");
        m.put("type", "cmis:document");
        m.put("objectType", "cmis:document");
        m.put("name", "original-name");
        m.put("parentId", "folder-1");
        m.put("aclInherited", true);
        if (incarnation != null) {
            m.put(ContentIncarnation.FIELD, incarnation);
        }
        if (withEpochFields) {
            m.put(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
            m.put(AclEpochState.FIELD_MUTATION_ID, "mid-123");
            m.put(AclEpochState.FIELD_SOURCE_EPOCH, 5);
            m.put(AclEpochState.FIELD_QUARANTINED, quarantineMarker);
        }
        return m;
    }

    /** The full store→model→rename→store pipeline, returning the map the wrapper would PUT. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> renameRoundTrip(Map<String, Object> stored) {
        CouchDocument read = new CouchDocument(stored);
        Document model = read.convert();
        model.setName("renamed");                       // the unrelated mutation
        CouchDocument toWrite = new CouchDocument(model);
        return MAPPER.convertValue(toWrite, Map.class);
    }

    @Test
    public void aRenameKeepsEveryEpochFieldVERBATIM() {
        Map<String, Object> out = renameRoundTrip(storedDoc(true, Boolean.TRUE, "inc-1"));

        assertEquals("renamed", out.get("name"), "the rename itself must land");
        assertEquals(AclEpochState.PENDING_EPOCH, out.get(AclEpochState.FIELD_STATE),
                "erasing the PENDING marker loses the mutation — the Q0 loss through the side door");
        assertEquals("mid-123", out.get(AclEpochState.FIELD_MUTATION_ID));
        assertEquals(5, ((Number) out.get(AclEpochState.FIELD_SOURCE_EPOCH)).intValue());
        assertEquals(Boolean.TRUE, out.get(AclEpochState.FIELD_QUARANTINED),
                "erasing a TRUE quarantine un-isolates a corrupt document behind the scanner's back");
        assertEquals("inc-1", out.get(ContentIncarnation.FIELD),
                "the incarnation must be PRESERVED, not re-minted — before this fix every update "
                        + "started a new lifetime and the content fence saw a restore");
    }

    @Test
    public void aRenameOfADocumentWITHOUTEpochFieldsStillHasNone() {
        Map<String, Object> out = renameRoundTrip(storedDoc(false, null, "inc-1"));

        assertEquals("renamed", out.get("name"));
        for (String key : new String[] { AclEpochState.FIELD_STATE, AclEpochState.FIELD_MUTATION_ID,
                AclEpochState.FIELD_SOURCE_EPOCH, AclEpochState.FIELD_QUARANTINED }) {
            assertFalse(out.containsKey(key),
                    "NOTHING may mint '" + key + "' — a rename must not spontaneously grow a marker");
        }
        assertEquals("inc-1", out.get(ContentIncarnation.FIELD));
    }

    /**
     * An EXPLICIT-null marker is PRESENT (the 2e containsKey contract) and must stay an explicit
     * null — degrading it to absent would let a rename clean up a corrupt marker shape that the
     * scanner is supposed to see and normalize.
     */
    @Test
    public void anExplicitNullMarkerStaysPresentNull() {
        Map<String, Object> out = renameRoundTrip(storedDoc(true, null, "inc-1"));

        assertTrue(out.containsKey(AclEpochState.FIELD_QUARANTINED),
                "present-null must not degrade to absent");
        assertNull(out.get(AclEpochState.FIELD_QUARANTINED));
    }

    /** Corrupt shapes are the SCANNER's to judge; the carrier must not coerce or drop them. */
    @Test
    public void corruptShapesRoundTripUNINTERPRETED() {
        Map<String, Object> stored = storedDoc(false, null, "inc-1");
        stored.put(AclEpochState.FIELD_STATE, 7);            // non-String state
        stored.put(AclEpochState.FIELD_SOURCE_EPOCH, "NaN"); // non-Number epoch

        Map<String, Object> out = renameRoundTrip(stored);
        assertEquals(7, ((Number) out.get(AclEpochState.FIELD_STATE)).intValue());
        assertEquals("NaN", out.get(AclEpochState.FIELD_SOURCE_EPOCH));
    }

    /** The contrast case: NO incarnation stored (create / legacy) → minted exactly once. */
    @Test
    public void aMissingIncarnationIsMintedOnce_theOneAssignOnceException() {
        Map<String, Object> out = renameRoundTrip(storedDoc(false, null, null));

        Object minted = out.get(ContentIncarnation.FIELD);
        assertNotNull(minted, "a legacy document is lazily assigned an incarnation on first update");
        assertTrue(minted instanceof String && !((String) minted).isBlank());

        // ...and once assigned it is stable: run the SAME document through a second update.
        Map<String, Object> second = renameRoundTrip(out);
        assertEquals(minted, second.get(ContentIncarnation.FIELD),
                "the second update must PRESERVE, not re-mint");
    }

    /** The typed model readers used by the re-drive terminus are lenient, never throwing. */
    @Test
    public void modelReadersAreLenientOnCorruption() {
        CouchDocument read = new CouchDocument(storedDoc(true, Boolean.TRUE, "inc-1"));
        Document model = read.convert();
        assertEquals(AclEpochState.PENDING_EPOCH, model.aclEpochFieldAsString(AclEpochState.FIELD_STATE));
        assertEquals(Long.valueOf(5L), model.aclEpochFieldAsLong(AclEpochState.FIELD_SOURCE_EPOCH));

        CouchDocument corrupt = new CouchDocument(storedDoc(false, null, "inc-1"));
        Document m2 = corrupt.convert();
        assertNull(m2.aclEpochFieldAsString(AclEpochState.FIELD_STATE), "absent → null, no exception");
    }
}
