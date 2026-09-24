package jp.aegif.nemaki.rest.ingest.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MailImportWarnings} reads the mail import's warnings by the words the import writes. The
 * two are in different classes: a reworded warning in the import would make the mail connectors
 * pass a missing part as a complete mail, and every connector lock would stay green — they return
 * the old words themselves. This reads the import's own string literals.
 */
class MailImportWarningsNameTheImportsOwnWordingTest {

    private static final Path IMPORT = Path.of("src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java");

    @Test
    @DisplayName("every missing-part wording the mail connectors recognise is one the mail import writes")
    void everyMissingPartWordingIsTheImportsOwn() throws Exception {
        assertTrue(Files.exists(IMPORT), "this lock reads " + IMPORT + ", which is not there");
        String source = Files.readString(IMPORT, StandardCharsets.UTF_8);
        for (String start : List.of("Attachment '", "Raw .eml preservation", "Relationship failed", "the relationship was not created")) {
            assertTrue(source.contains("\"" + start), "the mail import no longer writes a warning starting \"" + start + "\"");
            assertTrue(MailImportWarnings.saysAPartIsMissing(start + " x"), "\"" + start + "\" is not read as a missing part");
        }
    }

    @Test
    @DisplayName("an attachment's own warning about evidence, merged into the mail's, is not a missing part")
    void anAttachmentsEvidenceWarningIsNotAMissingPart() {
        assertFalse(MailImportWarnings.saysAPartIsMissing("attachment 'a.pdf': Provenance was NOT recorded for this document (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing("Provenance was NOT recorded for this document (x)"));
        assertFalse(MailImportWarnings.saysAPartIsMissing(null));
    }
}
