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
     */
    public static long worstCaseBackoffTotalMs() {
        long total = 0L;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long delay = Math.min(BASE_RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1),
                    MAX_RETRY_DELAY_MS);
            total += delay + (long) Math.ceil(delay * JITTER_FRACTION);
        }
        return total;
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

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = calculateBackoffDelay(attempt);
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
