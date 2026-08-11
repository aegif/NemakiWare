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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * How many times an attachment read opens the binary body.
 *
 * <h2>What this exists to stop</h2>
 *
 * <p>{@code CloudantClientWrapper.getAttachment} returns a live HTTP connection whose ownership
 * passes to the caller. An {@code InputStream} that is opened and then dropped is not collected
 * promptly — it is held until GC runs, and until then it occupies a connection from the pool. So
 * "how many opens" and "how many closes" are the whole of this defect.
 *
 * <p>Two ways this was getting it wrong at once. {@code CouchAttachmentNode.convert()} opens the
 * body itself, searching every repository for the id; {@code getAttachment} then opened it a
 * SECOND time and assigned over the first reference without closing it. Every single attachment
 * read leaked exactly one connection and downloaded the attachment twice to do so. Separately,
 * callers that wanted nothing but a length or a null check were calling {@code getAttachment} —
 * paying a full download, and leaking, to read a number that was already in the document.
 *
 * <p>Measured on the dev stack during a full reindex before the fix: established connections went
 * 3 → 1,289 for 2,510 documents, and stayed for roughly ninety seconds after the reindex finished.
 *
 * <h2>Why the assertion is a call count</h2>
 *
 * <p>A leak has no return value. Nothing about the {@code AttachmentNode} that comes back
 * distinguishes one open from two — both hand you one usable stream. The only observable
 * difference is at the client, so that is where this counts.
 *
 * <h2>Why the Spring context is installed</h2>
 *
 * <p>{@code convert()} does not reach CouchDB through the delegate's injected pool: it looks the
 * pool up from {@link SpringContext} itself. Without a context installed, that lookup throws, the
 * broad catch inside {@code convert()} swallows it, and the second open never reaches a mocked
 * client — so a test that mocks only the delegate's pool passes against the BROKEN code. That was
 * verified, not assumed: this test was first written without the context, and the deliberately
 * reverted double-open version passed it four for four. The context is what makes the defect
 * visible.
 */
class AttachmentBodyOpenCountTest {

    private CloudantClientWrapper client;
    private AttachmentDaoDelegate delegate;
    private ApplicationContext previousContext;

    private static final String REPO = "bedroom";
    private static final String ID = "attachment-1";

    @BeforeEach
    void setUp() throws Exception {
        client = mock(CloudantClientWrapper.class);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(anyString())).thenReturn(client);

        // The static context is global to the JVM; saved and restored so this cannot bleed into
        // whatever else surefire runs in the same fork.
        previousContext = SpringContext.getApplicationContext();
        ApplicationContext ctx = mock(ApplicationContext.class);
        lenient().when(ctx.getBean(eq("connectorPool"), eq(CloudantClientPool.class)))
                .thenReturn(pool);
        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        lenient().when(repoMap.getMainRepositoryKeys()).thenReturn(List.of(REPO));
        lenient().when(ctx.getBean(eq("repositoryInfoMap"), eq(RepositoryInfoMap.class)))
                .thenReturn(repoMap);
        new SpringContext().setApplicationContext(ctx);

        Map<String, Object> props = new HashMap<>();
        props.put("_id", ID);
        props.put("type", "attachment");
        props.put("name", "report.pdf");
        props.put("mimeType", "application/pdf");
        props.put("length", 4096L);
        CouchAttachmentNode node = new CouchAttachmentNode(props);
        lenient().when(client.get(eq(CouchAttachmentNode.class), eq(ID))).thenReturn(node);

        lenient().when(client.getAttachment(eq(ID), eq("content")))
                .thenAnswer(inv -> body());

        delegate = new AttachmentDaoDelegate(pool, mock(DaoHelper.class));
    }

    @AfterEach
    void tearDown() {
        new SpringContext().setApplicationContext(previousContext);
    }

    private static InputStream body() {
        return new ByteArrayInputStream("pdf bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ref はボディを一切開かない")
    void aRefOpensNoBodyAtAll() throws Exception {
        AttachmentNode ref = delegate.getAttachmentRef(REPO, ID);

        assertNotNull(ref);
        verify(client, never()).getAttachment(anyString(), anyString());
        assertNull(ref.getInputStream(),
                "a ref that carries a stream is a ref its callers will not close");
    }

    @Test
    @DisplayName("ref でも length と mimeType は本物 (ドキュメント側にある)")
    void aRefStillCarriesTheMetadataItsCallersWant() {
        AttachmentNode ref = delegate.getAttachmentRef(REPO, ID);

        assertEquals(4096L, ref.getLength(),
                "length lives in the document; needing the body for it is what made the callers"
                        + " reach for getAttachment in the first place");
        assertEquals("application/pdf", ref.getMimeType());
        assertEquals("report.pdf", ref.getName());
    }

    @Test
    @DisplayName("getAttachment のボディ取得はちょうど 1 回 (2 回目が旧リークの正体)")
    void aFullReadOpensTheBodyExactlyOnce() throws Exception {
        AttachmentNode full = delegate.getAttachment(REPO, ID);

        assertNotNull(full.getInputStream(), "a full read must still deliver a usable stream");
        verify(client, times(1)).getAttachment(eq(ID), eq("content"));
    }

    @Test
    @DisplayName("ボディ取得が失敗しても ref 相当のメタデータは返る")
    void aFailedBodyStillReturnsTheMetadata() throws Exception {
        when(client.getAttachment(eq(ID), eq("content")))
                .thenThrow(new RuntimeException("connection reset"));

        AttachmentNode full = delegate.getAttachment(REPO, ID);

        assertNotNull(full, "the metadata was already read; losing the body must not lose it too");
        assertEquals(4096L, full.getLength());
        assertNull(full.getInputStream());
    }
}
