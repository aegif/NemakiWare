package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.config.ObjectMapperFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The two authorisation stamps are written BY the gate, never read from the caller.
 *
 * <p>{@code authorizedProfileFingerprint} and {@code authorizedTargetFolderId} are how the
 * delegated gate tells the import which profile row and which folder it actually checked, so
 * that a row swapped in between the two reads is refused. If a caller could set them, it could
 * hand the import the identity of a row the gate never looked at.
 *
 * <p>Nothing measured that. CodeQL raised fifteen {@code java/user-controlled-bypass} alerts on
 * the comparisons that read these fields, and reading the model showed the protection is real —
 * {@code @JsonIgnore} on the field AND on the getter AND on the setter, three per property. But
 * a protection nobody measures is one line (here: three) from being deleted by someone who has
 * no way to find out what it was for. These are that measurement.
 *
 * <p>Both mappers, because the tree has two: the multipart door names
 * {@code createDefaultObjectMapper} itself, while the JSON door goes through Spring's own
 * converter and {@code spring-mvc-context.xml} does not pin one — so the deployed path is not
 * decided by this tree's code, and the honest thing is to hold BOTH to the same rule. The
 * Nemaki profile is the sharper case: it sets field visibility to {@code ANY}, which is exactly
 * the configuration that would defeat a field-only ignore.
 */
class AuthorizationStampsAreNotAcceptedFromTheWireTest {

    /** A body that names both stamps, plus a field that legitimately binds. */
    private static final String BODY = "{"
            + "\"profileId\":\"p-sentinel\","
            + "\"connectorId\":\"c-1\","
            + "\"sourceObjectId\":\"src-1\","
            + "\"sourceObjectType\":\"file\","
            + "\"authorizedProfileFingerprint\":\"forged-by-the-caller\","
            + "\"authorizedTargetFolderId\":\"F-forged\""
            + "}";

    private void assertTheWireCannotStamp(ObjectMapper mapper, String which) {
        ExternalIngestRequest request = mapper.readValue(BODY, ExternalIngestRequest.class);

        // The sentinel first. Without it, a mapper that bound NOTHING at all would pass the two
        // null checks below and report a protection that was never exercised.
        assertEquals("p-sentinel", request.getProfileId(),
                which + ": the body did not bind at all, so the nulls below prove nothing");

        assertNull(request.getAuthorizedProfileFingerprint(),
                which + ": a caller set the fingerprint the gate is supposed to write");
        assertNull(request.getAuthorizedTargetFolderId(),
                which + ": a caller set the folder id the gate is supposed to write");
    }

    @Test
    @DisplayName("the multipart door's own mapper does not take the stamps from the body")
    void theDefaultMapperDoesNotTakeTheStamps() {
        assertTheWireCannotStamp(ObjectMapperFactory.createDefaultObjectMapper(), "default mapper");
    }

    @Test
    @DisplayName("the field-visibility-ANY mapper does not take them either")
    void theNemakiMapperDoesNotTakeTheStamps() {
        // The one that would bind a private field if the ignores were not there.
        assertTheWireCannotStamp(ObjectMapperFactory.createNemakiObjectMapper(), "nemaki mapper");
    }

    @Test
    @DisplayName("neither mapper writes the stamps out, either")
    void theStampsDoNotTravelOutInJson() {
        // The other direction: the stamps say what this node authorised internally. A body that
        // carries them teaches a caller the exact string to send back.
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setProfileId("p-1");
        request.setAuthorizedProfileFingerprint("internal-fingerprint");
        request.setAuthorizedTargetFolderId("F-internal");

        for (ObjectMapper mapper : new ObjectMapper[] {
                ObjectMapperFactory.createDefaultObjectMapper(),
                ObjectMapperFactory.createNemakiObjectMapper() }) {
            String json = mapper.writeValueAsString(request);
            assertFalse(json.contains("internal-fingerprint"),
                    "the fingerprint was serialised out: " + json);
            assertFalse(json.contains("F-internal"),
                    "the authorised folder id was serialised out: " + json);
        }
    }
}
