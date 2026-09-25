package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.ingest.mail.MailImportWarnings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A result's warnings and errors are its own. The mail import made its result from the list it
 * had been adding to, and the result handed that list out as it was: a wrapper filtering it in
 * place — {@code result.warnings().removeIf(…)} — would drop the warning that says an attachment
 * is missing, and a connector would checkpoint an incomplete mail as a complete one, with every
 * lock that reads the import's source green (review, P1). Each property has its own test, so a
 * copy that stays changeable and a view that follows its source are each caught.
 */
class ExternalIngestResultKeepsItsOwnListsTest {

    private static final String MISSING = "Attachment 'a.pdf' was not imported: the store refused it";

    private static ExternalIngestResult madeFrom(List<String> errors, List<String> warnings) {
        return new ExternalIngestResult("request", "object", null, false, false, false, null, null,
                errors, warnings, true);
    }

    @Test
    @DisplayName("a result's warnings cannot be filtered through it")
    void itsWarningsCannotBeChangedThroughIt() {
        ExternalIngestResult result = madeFrom(List.of(), new ArrayList<>(List.of(MISSING)));
        assertThrows(UnsupportedOperationException.class,
                () -> result.warnings().removeIf(MailImportWarnings::saysAPartIsMissing),
                "a result's warnings were filtered through it");
        assertEquals(List.of(MISSING), result.warnings());
    }

    @Test
    @DisplayName("a result's warnings do not follow the list it was made from")
    void itsWarningsDoNotFollowTheListItWasMadeFrom() {
        List<String> kept = new ArrayList<>(List.of(MISSING));
        ExternalIngestResult result = madeFrom(List.of(), kept);
        kept.clear();
        assertEquals(List.of(MISSING), result.warnings(), "a result's warnings changed with the list its maker kept");
    }

    @Test
    @DisplayName("a result's errors cannot be cleared through it")
    void itsErrorsCannotBeChangedThroughIt() {
        ExternalIngestResult result = madeFrom(new ArrayList<>(List.of("the store refused the document")), List.of());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().clear(),
                "a result's errors were cleared through it");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("a result's errors do not follow the list it was made from")
    void itsErrorsDoNotFollowTheListItWasMadeFrom() {
        List<String> kept = new ArrayList<>(List.of("the store refused the document"));
        ExternalIngestResult result = madeFrom(kept, List.of());
        kept.clear();
        assertFalse(result.isSuccess(), "a failed result read as a success once its maker's list was cleared");
    }
}
