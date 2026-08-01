package jp.aegif.nemaki.rest.purview.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * {@link CloudMetadataSnapshotFormat} — the rule that the cloud file URL is not in the cursor.
 *
 * <p>The snapshot is persisted verbatim as a cursor and served verbatim by the admin API, and a
 * drive URL's query string is where sharing tokens live. These tests pin both halves: no entry
 * ever carries one, and old stored cursors that do are mapped onto the clean shape everywhere
 * they are read.
 */
public class CloudMetadataSnapshotFormatTest {

    @Test
    public void anEntryHasFiveFieldsAndAnEmptyUrlSlot() {
        assertEquals("doc-1|google|file-1||2026-03-20T03:00:00.000+0000",
                CloudMetadataSnapshotFormat.entry("doc-1", "google", "file-1",
                        "2026-03-20T03:00:00.000+0000"));
    }

    @Test
    public void anEntryTurnsNullsIntoEmptyFields() {
        assertEquals("doc-1||||", CloudMetadataSnapshotFormat.entry("doc-1", null, null, null));
    }

    @Test
    public void normalizeStripsTheUrlFieldFromOldFormatLines() {
        String old = "doc-1|google|file-1|https://drive.example/d?authkey=SECRET|2026-03-20";
        String normalized = CloudMetadataSnapshotFormat.normalize(old);
        assertEquals("doc-1|google|file-1||2026-03-20", normalized);
        assertFalse(normalized.contains("SECRET"));
    }

    @Test
    public void normalizeIsIdempotent() {
        String clean = CloudMetadataSnapshotFormat.entry("doc-1", "google", "file-1", "2026-03-20");
        assertEquals(clean, CloudMetadataSnapshotFormat.normalize(clean));
        String old = "doc-1|google|file-1|https://drive.example/d|2026-03-20";
        assertEquals(CloudMetadataSnapshotFormat.normalize(old),
                CloudMetadataSnapshotFormat.normalize(CloudMetadataSnapshotFormat.normalize(old)));
    }

    @Test
    public void normalizeHandlesMultiLineSnapshots() {
        String snapshot = "doc-1|google|file-1|https://a.example/x?sig=S1|t1\n"
                + "doc-2|microsoft|file-2|https://b.example/y?sig=S2|t2";
        String normalized = CloudMetadataSnapshotFormat.normalize(snapshot);
        assertEquals("doc-1|google|file-1||t1\ndoc-2|microsoft|file-2||t2", normalized);
        assertFalse(normalized.contains("sig="));
    }

    /**
     * A line that is not in the five-field format passes through untouched. Guessing at an
     * unknown shape risks corrupting a cursor belonging to some other stream that happens to
     * share a pipe character.
     */
    @Test
    public void normalizeLeavesUnrecognisedLinesAlone() {
        assertEquals("not-a-snapshot-line", CloudMetadataSnapshotFormat.normalize("not-a-snapshot-line"));
        assertEquals("a|b|c", CloudMetadataSnapshotFormat.normalize("a|b|c"));
        assertEquals("a|b|c|d|e|f", CloudMetadataSnapshotFormat.normalize("a|b|c|d|e|f"));
    }

    @Test
    public void normalizeHandlesNullAndBlank() {
        assertEquals("", CloudMetadataSnapshotFormat.normalize(null));
        assertEquals("", CloudMetadataSnapshotFormat.normalize(""));
        assertEquals("  ", CloudMetadataSnapshotFormat.normalize("  "));
    }

    /** The stream-kind constant is what the admin controller keys its sanitisation on. */
    @Test
    public void theStreamKindMatchesTheSyncServicesConstant() {
        assertEquals("cloud-metadata-snapshot", CloudMetadataSnapshotFormat.STREAM_KIND);
    }
}
