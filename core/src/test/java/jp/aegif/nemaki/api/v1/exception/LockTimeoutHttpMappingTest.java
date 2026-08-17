/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.api.v1.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.util.lock.LockAcquisitionTimeoutException;

/**
 * A bounded-lock timeout must reach /api/v1 clients as a 503, whatever wrapped it on the way.
 *
 * <h2>Why an HTTP-level test</h2>
 *
 * <p>The exception's class hierarchy (extends the CMIS 503) was already pinned elsewhere — and
 * turned out to be insufficient: this mapper read {@code getCode()} (which is ZERO for that CMIS
 * type) and produced an internal-error 500, and the resources' broad catch-and-wrap buried the
 * type entirely. The signal the exception exists to carry — "temporary, retry" — died at exactly
 * this layer. So the assertion has to be on the RESPONSE, not on the exception.
 */
class LockTimeoutHttpMappingTest {

    private final ApiExceptionMapper mapper = new ApiExceptionMapper();

    private static LockAcquisitionTimeoutException timeout() {
        return new LockAcquisitionTimeoutException("could not take the lock",
                List.of("bedroom/obj-1"));
    }

    @Test
    @DisplayName("生の LockAcquisitionTimeoutException は 503 になる (getCode()==0 でも)")
    void aDirectTimeoutMapsTo503() {
        Response response = mapper.toResponse(timeout());
        assertEquals(503, response.getStatus(),
                "the CMIS unavailable type carries code ZERO, and a code-based mapping reads that"
                        + " as 'not a client error' = 500 — the TYPE is what carries the meaning");
    }

    @Test
    @DisplayName("リソース層が internalError(detail, cause) で包んでも 503 が復元される")
    void aWrappedTimeoutIsRestoredTo503() {
        Response response = mapper.toResponse(
                ApiException.internalError("Failed to invalidate cache", timeout()));
        assertEquals(503, response.getStatus(),
                "the resources catch broadly and wrap; the mapper must walk the cause chain or"
                        + " every one of those catch sites silently downgrades retryable to fatal");
    }

    @Test
    @DisplayName("cause が深くても・循環していてもマッパは有界に応答する")
    void aCyclicCauseChainDoesNotHangTheMapper() {
        // A malformed Throwable graph must degrade to "no match", not spin the exception mapper.
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Response response = mapper.toResponse(ApiException.internalError("wrapped", a));
            assertEquals(500, response.getStatus(), "no 503 in the chain — stays internal error");
        });
    }
}
