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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * The DAO's refusal is only worth having if the wrapper under it refuses too.
 *
 * <h2>A closed door with an open one behind it</h2>
 *
 * <p>{@code getAttachmentActualSize} was made to refuse rather than answer "no measurable
 * size", because a null there is read one layer up as "use the length the document claims" —
 * the very number a fixity check is trying to corroborate. But the DAO only ever sees the
 * wrapper's return value, and the wrapper answered {@code null} for a failed measurement as
 * well as for a document with no attachment. So the failure walked past the new refusal
 * without touching it, and the fix was inert for the commonest failure.
 *
 * <p>Found by a sibling sweep: DAO closed, wrapper not.
 */
class AttachmentSizeRefusalReachesTheDaoTest {

    private static final String REPO = "bedroom";

    private static AttachmentDaoDelegate delegateOver(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        return new AttachmentDaoDelegate(pool, mock(DaoHelper.class));
    }

    @Test
    @DisplayName("a wrapper failure reaches the DAO as a refusal, not as 'no size'")
    void aWrapperFailureReachesTheDaoAsARefusal() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.getAttachmentSize(eq("att-1"), eq("content")))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
                        "the stored size of att-1/content could not be measured"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> delegateOver(client).getAttachmentActualSize(REPO, "att-1"),
                "the wrapper answered null for a measurement that failed, so the DAO's own "
                        + "refusal was never reached and the caller fell back to the length "
                        + "the document claims");
        assertTrue(refused.getMessage().contains("could not be read"),
                "refused by a different guard: " + refused.getMessage());
    }

    @Test
    @DisplayName("the wrapper itself refuses — the test above mocks it, so it cannot say so")
    void theWrapperRefusesAFailedMeasurement() throws Exception {
        // The behavioural test above stubs CloudantClientWrapper, so the wrapper's own code
        // never runs in it: sabotaging the wrapper left it green (the runner reported DID
        // NOT FIRE). Driving the real wrapper needs a live Cloudant client, so the property
        // is pinned in its source instead — the LAST catch of the size read, by body, not by
        // the absence of one spelling.
        String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/"
                                + "CloudantClientWrapper.java"));
        String body = jp.aegif.nemaki.util.test.JavaSource.methodBody(source,
                "public Long getAttachmentSize(String docId, String attachmentName)");
        int lastCatch = body.lastIndexOf("} catch (Exception e) {");
        assertTrue(lastCatch > 0, "the size read no longer has a general catch: " + body);
        String tail = body.substring(lastCatch);
        assertTrue(tail.contains("throw new"),
                "the wrapper answers null for a measurement that FAILED again, so the DAO's "
                        + "refusal above it is never reached: " + tail);
        assertTrue(body.contains("catch (NotFoundException e)") && body.contains("return null;"),
                "the NotFound arm must keep answering null — a document with no content "
                        + "attachment genuinely has no measurable size: " + body);
    }

    @Test
    @DisplayName("a document with no content attachment still has no measurable size")
    void aBodilessDocumentStillAnswersNull() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.getAttachmentSize(eq("att-2"), eq("content"))).thenReturn(null);

        assertNull(delegateOver(client).getAttachmentActualSize(REPO, "att-2"),
                "genuine absence of a content attachment was turned into a refusal");
    }
}
