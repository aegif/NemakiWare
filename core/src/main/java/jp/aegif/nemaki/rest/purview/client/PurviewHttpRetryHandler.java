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

        // Jitter ±10%
        long jitter = (long) (delay * 0.1 * (2 * Math.random() - 1));
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
