/*
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
package jp.aegif.nemaki.rest.controller;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-domain settings the anchor scheduler acts on, read from and written to nemaki_conf.
 *
 * <p>Every lock on the scheduler and on the settings endpoint replaced these two methods with a
 * mock, so the arms that keep "could not ask" from reading as "nothing saved" were measured by
 * nothing, and neither was the shape the writer stores (subagent, c41, P2). A partial answer read
 * as whole drops a saved "disabled", and the scheduler then acts on a start-up "enabled".
 */
class RepositorySettingsAreReadFailClosedTest {

    private static final String DOMAIN = "record-content";
    private static final List<String> KEYS = List.of("anchor.schedule.enabled",
            "anchor.schedule.interval-minutes");

    private final List<PostFindOptions> finds = new ArrayList<>();
    private final List<PostDocumentOptions> posts = new ArrayList<>();

    /** A service over a nemaki_conf whose every {@code _find} answers {@code docs}. */
    @SuppressWarnings("unchecked")
    private IntegrationSettingsService serviceAnswering(List<Document> docs) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        Cloudant client = mock(Cloudant.class);
        ServiceCall<FindResult> call = mock(ServiceCall.class);
        Response<FindResult> response = mock(Response.class);
        FindResult result = mock(FindResult.class);
        ServiceCall<DocumentResult> postCall = mock(ServiceCall.class);
        Response<DocumentResult> postResponse = mock(Response.class);
        DocumentResult posted = mock(DocumentResult.class);

        when(pool.getClient(anyString())).thenReturn(wrapper);
        when(wrapper.getDatabaseName()).thenReturn(SystemConst.NEMAKI_CONF_DB);
        when(wrapper.getClient()).thenReturn(client);
        when(client.postFind(any(PostFindOptions.class))).thenAnswer(inv -> {
            finds.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(response);
        when(response.getResult()).thenReturn(result);
        when(result.getDocs()).thenReturn(docs);
        when(client.postDocument(any(PostDocumentOptions.class))).thenAnswer(inv -> {
            posts.add(inv.getArgument(0));
            return postCall;
        });
        when(postCall.execute()).thenReturn(postResponse);
        when(postResponse.getResult()).thenReturn(posted);
        when(posted.isOk()).thenReturn(true);

        IntegrationSettingsService service = new IntegrationSettingsService();
        service.setConnectorPool(pool);
        return service;
    }

    private static Document stored(String key, String value) {
        Document doc = new Document();
        doc.setProperties(Map.of("type", "configuration", "key", key, "value", value,
                "repositoryId", DOMAIN));
        return doc;
    }

    @Test
    @DisplayName("an answer without a document list is not 'nothing saved'")
    void anAnswerWithoutDocumentsIsNotNothingSaved() {
        IntegrationSettingsService service = serviceAnswering(null);

        // The type AND the reason: a loop over a null list throws too, and "it threw" would be
        // satisfied by that sibling with the refusal gone.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.readRepositorySettings(DOMAIN, KEYS));
        assertTrue(refused.getMessage().contains("without documents"), refused.getMessage());
    }

    @Test
    @DisplayName("a full page is not the whole answer — and one short of it is")
    void aFullPageIsNotTheWholeAnswer() {
        // The limit the reader asked for, read from its own request rather than copied here.
        serviceAnswering(List.of(stored(KEYS.get(0), "false"))).readRepositorySettings(DOMAIN, KEYS);
        int limit = finds.get(0).limit().intValue();

        List<Document> full = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            full.add(stored(KEYS.get(0), "false"));
        }
        IntegrationSettingsService atTheLimit = serviceAnswering(full);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> atTheLimit.readRepositorySettings(DOMAIN, KEYS),
                "a page of " + limit + " documents was read as every document there is; a saved "
                        + "'disabled' beyond it would read as absent");
        assertTrue(refused.getMessage().contains("full page"), refused.getMessage());

        // The over-refusal side: fewer than the limit is an answer.
        Map<String, String> read = serviceAnswering(full.subList(0, limit - 1))
                .readRepositorySettings(DOMAIN, KEYS);
        assertEquals("false", read.get(KEYS.get(0)));
    }

    @Test
    @DisplayName("what the writer stores is what the reader asks for, and reads back")
    void whatTheWriterStoresIsWhatTheReaderReads() {
        serviceAnswering(List.of()).writeRepositorySettings(DOMAIN, Map.of(KEYS.get(0), "true"));
        assertEquals(1, posts.size(), "the writer stored nothing");
        Document written = posts.get(0).document();
        Map<String, Object> shape = written.getProperties();
        assertEquals("configuration", shape.get("type"), String.valueOf(shape));
        assertEquals(KEYS.get(0), shape.get("key"), String.valueOf(shape));
        assertEquals("true", shape.get("value"), String.valueOf(shape));
        assertEquals(DOMAIN, shape.get("repositoryId"),
                "a value stored without its domain is one the domain's reader never asks for: " + shape);

        finds.clear();
        Map<String, String> read = serviceAnswering(List.of(written)).readRepositorySettings(DOMAIN, KEYS);
        Map<String, Object> asked = finds.get(0).selector();
        assertEquals("configuration", asked.get("type"), String.valueOf(asked));
        assertEquals(DOMAIN, asked.get("repositoryId"), String.valueOf(asked));
        assertEquals("true", read.get(KEYS.get(0)), String.valueOf(read));
    }
}
