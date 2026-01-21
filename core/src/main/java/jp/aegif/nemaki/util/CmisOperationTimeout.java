/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Utility class for executing CMIS operations with timeout protection.
 * Prevents indefinite hanging of long-running operations.
 */
public class CmisOperationTimeout {
    
    private static final Log log = LogFactory.getLog(CmisOperationTimeout.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    
    /**
     * Execute a CMIS operation with specified timeout.
     * 
     * @param operation The operation to execute
     * @param timeoutSeconds Timeout in seconds
     * @return Result of the operation
     * @throws Exception If operation fails or times out
     */
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
    
    /**
     * Execute a CMIS operation with default timeout (30 seconds).
     * 
     * @param operation The operation to execute
     * @return Result of the operation
     * @throws Exception If operation fails or times out
     */
    public static <T> T executeWithTimeout(Callable<T> operation) throws Exception {
        return executeWithTimeout(operation, DEFAULT_TIMEOUT_SECONDS);
    }
}