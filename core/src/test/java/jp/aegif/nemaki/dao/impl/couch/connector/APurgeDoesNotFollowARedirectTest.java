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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.AllDocsResult;
import com.ibm.cloud.cloudant.v1.model.DocsResultRow;
import com.ibm.cloud.cloudant.v1.model.DocsResultRowValue;
import com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * The tombstone purge does not follow a redirect (3.4.2).
 *
 * <p>{@code purgeTombstone} POSTs to CouchDB's {@code _purge} with the CouchDB admin's credentials, on
 * an {@code HttpURLConnection} of its own (not the Cloudant client's). That connection buffered its
 * body and followed redirects: on JDK 21 a 307 re-sends the POST and its body to the address in
 * {@code Location}, and a 302 follows as a GET — an address nobody configured; the Authorization
 * header is dropped when the host or port changes (measured 2026-11-05), but that address's 200 was
 * reported as the purge.
 * The stand-in CouchDB answers the purge with a 307 to a second server; the second server must hear
 * nothing, the stand-in must hear the purge (so this cannot pass by never getting there), and the
 * purge is reported as not done.
 */
class APurgeDoesNotFollowARedirectTest {

    @Test
    @DisplayName("a 307 from CouchDB's _purge is not followed, and the purge is reported as not done")
    void aRedirectedPurgeIsNotFollowed() throws Exception {
        List<String> elsewhereHeard = Collections.synchronizedList(new ArrayList<>());
        HttpServer elsewhere = server(elsewhereHeard, 200, null);
        List<String> couchHeard = Collections.synchronizedList(new ArrayList<>());
        HttpServer couch = server(couchHeard, 307,
                "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/bedroom/_purge");
        try {
            Cloudant client = mock(Cloudant.class);
            when(client.getServiceUrl()).thenReturn("http://127.0.0.1:" + couch.getAddress().getPort());
            DocsResultRowValue value = mock(DocsResultRowValue.class);
            when(value.getRev()).thenReturn("2-deleted");
            when(value.isDeleted()).thenReturn(Boolean.TRUE);
            DocsResultRow row = mock(DocsResultRow.class);
            when(row.getId()).thenReturn("doc-1");
            when(row.getValue()).thenReturn(value);
            AllDocsResult result = mock(AllDocsResult.class);
            when(result.getRows()).thenReturn(List.of(row));
            @SuppressWarnings("unchecked")
            Response<AllDocsResult> response = mock(Response.class);
            when(response.getResult()).thenReturn(result);
            @SuppressWarnings("unchecked")
            ServiceCall<AllDocsResult> call = mock(ServiceCall.class);
            when(call.execute()).thenReturn(response);
            when(client.postAllDocs(any(PostAllDocsOptions.class))).thenReturn(call);

            CloudantClientWrapper wrapper = new CloudantClientWrapper(client, "bedroom",
                    jp.aegif.nemaki.config.ObjectMapperFactory.createDefaultObjectMapper());
            wrapper.setAuthCredentials("admin", "fixture");

            boolean purged = wrapper.purgeTombstone("doc-1");

            assertEquals(List.of("POST /bedroom/_purge"), couchHeard, "the stand-in CouchDB was asked");
            assertEquals(List.of(), elsewhereHeard, "the purge followed the redirect");
            assertFalse(purged, "a redirected purge was reported as done");
        } finally {
            couch.stop(0);
            elsewhere.stop(0);
        }
    }

    /** A loopback server that records what reaches it and answers every request with {@code status}. */
    private static HttpServer server(List<String> heard, int status, String location) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            heard.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] out = "{\"purge_seq\":null,\"purged\":{\"doc-1\":[\"2-deleted\"]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }
}
