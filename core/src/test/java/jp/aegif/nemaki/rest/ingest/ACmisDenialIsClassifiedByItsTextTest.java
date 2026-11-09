package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;

/**
 * How {@link ExternalIngestController#classifyErrorStatus} tells a CMIS permission denial in an import's error (R286 — 3.4.2).
 *
 * <p>The product's denial is one text ({@link ExceptionServiceImpl#PERMISSION_DENIED_MESSAGE}), and the import puts its verdict
 * and, on the archetype paths, a prefix IN FRONT of the exception's message. The classifier says two things about that text the
 * locks that drive an import do not measure, because every denial an import produces today is marked {@code [permanent] } and
 * is the whole tail of the error: a denial is asked before the retryable arm, so a denial marked as a retry is still a denial;
 * and the denial is matched at the END of the error, so a failure that merely carries the words is answered as that failure.
 * Both reviewers of the R286 batch found the first unmeasured; one found the second.
 *
 * <p>Each case is paired with the same failure without the denial, read by the same classifier, so a 403 here is the denial
 * arm's answer and not another arm's.
 */
class ACmisDenialIsClassifiedByItsTextTest {

    private static final String DENIAL = ExceptionServiceImpl.PERMISSION_DENIED_MESSAGE;

    private static HttpStatus classify(String error) {
        return ExternalIngestController.classifyErrorStatus(ExternalIngestResult.error("r286-classify", error));
    }

    @Test
    void aDenialMarkedAsARetryIsStillADenial() {
        for (String prefix : List.of("", "Mail import failed: ", "Chat context import failed: ")) {
            String retry = "[transient] " + prefix + "Read timed out";
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, classify(retry),
                    "the same failure without the denial is not a retry, so this case does not measure the order: " + retry);
            String denial = "[transient] " + prefix + DENIAL;
            assertEquals(HttpStatus.FORBIDDEN, classify(denial),
                    "a denial marked as a retry was answered as a retry — the denial arm is not asked first: " + denial);
        }
    }

    @Test
    void theDenialTextInsideAnotherFailureIsThatFailure() {
        String retry = "[permanent] whether request 'k-" + DENIAL + "-1' has already been completed could not be established"
                + " (the store did not answer); retry shortly";
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, classify(retry.replace(DENIAL, "x")),
                "the same failure without the words is not a retry, so this case measures nothing");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, classify(retry),
                "a retryable failure that carries the denial's words in an id was answered as a denial: " + retry);
        String absent = "[permanent] Import profile not found: p-" + DENIAL + "-x";
        assertEquals(HttpStatus.NOT_FOUND, classify(absent.replace(DENIAL, "x")),
                "the same failure without the words is not an absence, so this case measures nothing");
        assertEquals(HttpStatus.NOT_FOUND, classify(absent),
                "an absence that carries the denial's words in an id was answered as a denial: " + absent);
    }
}
