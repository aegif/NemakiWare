package jp.aegif.nemaki.rest.purview.client;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PurviewHttpRetryHandler {

    private static final Logger logger = LoggerFactory.getLogger(PurviewHttpRetryHandler.class);

    private static final long BASE_RETRY_DELAY_MS = 1000L;
    private static final long MAX_RETRY_DELAY_MS = 300000L;
    private static final int MAX_RETRIES = 3;
    /** Symmetric jitter applied to every backoff delay, as a fraction of that delay. */
    private static final double JITTER_FRACTION = 0.1;

    /**
     * How many attempts follow the first one.
     *
     * <p>Exposed so that anything budgeting a request reads the retry policy this handler
     * actually applies, rather than a second copy of the number that can drift away from it.
     * The lineage subject fence depends on that: a budget computed from a stale constant would
     * claim a safety margin the running code does not respect.
     */
    public static int maxRetries() {
        return MAX_RETRIES;
    }

    /**
     * The worst-case total sleep across every retry of one request.
     *
     * <p>Jitter is symmetric, so the worst case takes the positive side of it. Computed from the
     * same {@link #calculateBackoffDelay} shape the handler runs, minus the randomness.
     *
     * <p>This stays the ceiling even now that {@code Retry-After} is honoured: a server-supplied
     * delay is only slept when it fits under {@link #worstDelayForAttempt} for that attempt, and
     * anything larger ends the retry sequence instead of sleeping. The budget that reads this
     * number therefore remains truthful by construction, not by hoping servers are polite.
     */
    public static long worstCaseBackoffTotalMs() {
        long total = 0L;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            total += worstDelayForAttempt(attempt);
        }
        return total;
    }

    /** The most one attempt's backoff can sleep — the exponential step plus full jitter. */
    static long worstDelayForAttempt(int attempt) {
        long delay = Math.min(BASE_RETRY_DELAY_MS * (long) Math.pow(2, Math.max(attempt, 1) - 1),
                MAX_RETRY_DELAY_MS);
        return delay + (long) Math.ceil(delay * JITTER_FRACTION);
    }

    @FunctionalInterface
    public interface HttpOperation {
        HttpResponse<String> execute() throws IOException, InterruptedException, PurviewClientException;
    }

    public HttpResponse<String> sendWithRetry(
            HttpOperation operation,
            PurviewTokenCache tokenCache,
            Runnable tokenInvalidator) throws PurviewClientException {

        IOException lastIOException = null;
        HttpResponse<String> lastResponse = null;
        Long serverRequestedDelayMs = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = serverRequestedDelayMs != null ? serverRequestedDelayMs
                        : calculateBackoffDelay(attempt);
                serverRequestedDelayMs = null;
                logger.debug("Purview retry attempt {} after {}ms delay", attempt, delay);
                sleep(delay);
            }

            try {
                HttpResponse<String> response = operation.execute();
                lastResponse = response;
                int statusCode = response.statusCode();

                if (statusCode == 401 && tokenCache != null && tokenInvalidator != null && attempt == 0) {
                    logger.debug("Purview 401 response, invalidating token cache and retrying");
                    tokenInvalidator.run();
                    continue;
                }

                if (isRetryable(statusCode) && attempt < MAX_RETRIES) {
                    // Purview throttles per account and says when to come back (Retry-After on
                    // 429, sometimes on 503). Ignoring it and retrying on our own schedule was
                    // wrong in both directions at once: earlier than instructed re-hammers a
                    // throttled account and extends the throttle, and sleeping as instructed
                    // would put a server-chosen, unbounded wait inside a fenced section whose
                    // budget promised otherwise.
                    //
                    // So the instruction is honoured within the budget and obeyed-by-stopping
                    // beyond it: a Retry-After that fits under this attempt's budgeted worst
                    // is slept exactly; one that does not ends the retry sequence and hands
                    // the response back. The caller maps it to a retryable outcome, and the
                    // waiting happens at the obligation's own durable backoff — minutes,
                    // outside any fence or claim, which is where a server-mandated wait
                    // belongs.
                    Long retryAfterMs = retryAfterMs(response);
                    if (retryAfterMs != null) {
                        long budgetedWorst = worstDelayForAttempt(attempt + 1);
                        if (retryAfterMs > budgetedWorst) {
                            logger.debug("Purview HTTP {} asked for {}ms before retrying —"
                                    + " beyond the {}ms budgeted for this attempt; not"
                                    + " retrying in this pass", statusCode, retryAfterMs,
                                    budgetedWorst);
                            return response;
                        }
                        serverRequestedDelayMs = retryAfterMs;
                    }
                    logger.debug("Purview retryable HTTP {} on attempt {}", statusCode, attempt);
                    continue;
                }

                return response;

            } catch (IOException e) {
                lastIOException = e;
                if (attempt < MAX_RETRIES) {
                    logger.debug("Purview IOException on attempt {}: {}", attempt, e.getMessage());
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PurviewClientException("Purview request was interrupted", e);
            }
        }

        if (lastResponse != null) {
            return lastResponse;
        }

        throw new PurviewClientException(
                "Purview request failed after " + (MAX_RETRIES + 1) + " attempts: "
                        + (lastIOException != null ? lastIOException.getMessage() : "unknown error"),
                lastIOException);
    }

    public long calculateBackoffDelay(int attemptNumber) {
        if (attemptNumber < 1) {
            attemptNumber = 1;
        }

        long delay = BASE_RETRY_DELAY_MS * (long) Math.pow(2, attemptNumber - 1);
        delay = Math.min(delay, MAX_RETRY_DELAY_MS);

        // Jitter ±JITTER_FRACTION
        long jitter = (long) (delay * JITTER_FRACTION * (2 * Math.random() - 1));
        delay += jitter;

        return Math.max(delay, 0);
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * The {@code Retry-After} header as milliseconds, or null when there is nothing usable.
     *
     * <p>Delta-seconds only. The header's other legal form is an HTTP-date, and honouring one
     * would turn clock skew into a sleep of arbitrary length — the exact unbounded wait the
     * budget forbids — so a date, like garbage, reads as "no instruction" and the ordinary
     * backoff applies. Length-bounded before parsing so a hostile header cannot overflow.
     */
    private static Long retryAfterMs(HttpResponse<String> response) {
        if (response == null || response.headers() == null) {
            return null;
        }
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 7 || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(trimmed) * 1000L;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
