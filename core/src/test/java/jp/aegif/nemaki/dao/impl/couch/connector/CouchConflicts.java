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
package jp.aegif.nemaki.dao.impl.couch.connector;

import com.ibm.cloud.sdk.core.service.exception.ConflictException;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A REAL CouchDB conflict, for tests that need one to travel.
 *
 * <h2>Why not {@code mock(ConflictException.class)}</h2>
 *
 * <p>A mocked exception returns null from {@code getStackTrace()}. When one escapes a test —
 * which is exactly what the conflict tests are for — surefire tries to record that stack and
 * dies with {@code NullPointerException: Cannot read the array length}. The class then reports
 * one class-level ERROR instead of naming the assertion that failed, so a negative control turns
 * the suite red for a reason nobody can read. That is barely better than not measuring.
 *
 * <p>The SDK's constructor takes an {@code okhttp3.Response}, so a genuine 409 is a few lines.
 */
public final class CouchConflicts {

    private CouchConflicts() {
    }

    /** A 409 as the SDK would raise it. */
    public static ConflictException conflict() {
        Request request = new Request.Builder().url("http://couchdb:5984/db/doc").build();
        Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(409)
                .message("Conflict")
                .body(ResponseBody.create("{\"error\":\"conflict\",\"reason\":\"Document update"
                        + " conflict.\"}", MediaType.parse("application/json")))
                .build();
        return new ConflictException(response);
    }
}
