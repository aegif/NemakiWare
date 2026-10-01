package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authorisation stamps have to reach the writes that are DERIVED from an authorised one.
 *
 * <p>A raw {@code .eml} child, a mail attachment and a note attachment all land in the same
 * folder as the object they came from, so they inherit the same authorisation — but they are
 * built as fresh requests, and the stamps were not copied. For note's {@code files_only}
 * default the parent never reaches {@code execute()} at all, so every write for that archetype
 * was an unstamped one. A review enumerated the three sites.
 */
class AuthorizationStampsTravelWithDerivedWritesTest {

    @Test
    @DisplayName("both stamps are copied onto a derived request")
    void bothStampsAreCopied() {
        ExternalIngestRequest parent = new ExternalIngestRequest();
        parent.setAuthorizedProfileFingerprint("fingerprint-of-the-authorised-row");
        parent.setAuthorizedTargetFolderId("folder-that-was-authorized");
        ExternalIngestRequest child = new ExternalIngestRequest();

        parent.copyAuthorizationStampsTo(child);

        assertEquals("fingerprint-of-the-authorised-row",
                child.getAuthorizedProfileFingerprint(),
                "the derived write does not carry the row that was authorised");
        assertEquals("folder-that-was-authorized", child.getAuthorizedTargetFolderId(),
                "the derived write does not carry the folder that was authorised");
    }

    @Test
    @DisplayName("every derived request in the import service is stamped")
    void everyDerivedRequestIsStamped() throws Exception {
        // A source lock, and it is one on purpose: the unit test above proves the copier
        // copies, not that the three construction sites call it. Those sites are inside a mail
        // and a note import that a unit fixture cannot reach without a full attachment
        // pipeline. What this cannot see is a FOURTH site added later in another class.
        String source = Files.readString(Path.of(
                "src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java"));
        int constructed = source.split("new ExternalIngestRequest\\(\\)", -1).length - 1;
        int stamped = source.split("copyAuthorizationStampsTo\\(", -1).length - 1;
        assertTrue(constructed > 0, "the derived-request sites moved; this lock is measuring nothing");
        assertEquals(constructed, stamped,
                "a request derived inside the import service is built without the authorisation"
                        + " stamps: " + constructed + " constructed, " + stamped + " stamped");
    }
}
