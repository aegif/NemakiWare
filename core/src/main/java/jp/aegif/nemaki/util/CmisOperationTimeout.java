package jp.aegif.nemaki.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CmisOperationTimeout {
    
    private static final Log log = LogFactory.getLog(CmisOperationTimeout.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    
    public static <T> T executeWithTimeout(Callable<T> operation, int timeoutSeconds) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = executor.submit(operation);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("CMIS operation timed out after " + timeoutSeconds + " seconds");
            throw new RuntimeException("CMIS operation timed out", e);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public static <T> T executeWithTimeout(Callable<T> operation) throws Exception {
        return executeWithTimeout(operation, DEFAULT_TIMEOUT_SECONDS);
    }
}
