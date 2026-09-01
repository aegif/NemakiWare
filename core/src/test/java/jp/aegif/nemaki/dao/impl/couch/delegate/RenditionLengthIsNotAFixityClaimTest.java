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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * What the rendition length is, and what it is not.
 *
 * <h2>The arm that was left open on purpose, and the ground for leaving it</h2>
 *
 * <p>{@code AttachmentDaoDelegate.getRendition} corrects a rendition's recorded length with a
 * measurement of the stored bytes, and when that measurement fails it keeps the recorded value
 * and logs. Everywhere else in this batch that shape was closed, because falling back to the
 * document's own claim is exactly what makes a fixity check compare a number with itself. It
 * was kept here, and the ground is that a rendition length is not a fixity claim:
 *
 * <ul>
 *   <li>The CMIS rendition stream does not use it at all — {@code ObjectServiceImpl} builds the
 *       {@code ContentStream} with {@code -1} deliberately, because CouchDB reports the
 *       COMPRESSED size and the SDK returns decompressed bytes, so a real length there
 *       truncates the response.</li>
 *   <li>The remaining readers are display listings.</li>
 *   <li>A rendition is a preview this product derived; its recorded length is this product's
 *       own record, not a third party's assertion about a record.</li>
 * </ul>
 *
 * <p>The first of those three is the one that could stop being true by accident, so it is what
 * this pins. If the stream ever starts claiming the recorded length, the fallback above turns
 * into a size assertion about bytes nobody measured — and the ground for keeping it is gone.
 */
class RenditionLengthIsNotAFixityClaimTest {

    @Test
    @DisplayName("the CMIS rendition stream does not claim a length")
    void theRenditionStreamDoesNotClaimALength() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java"));
        String body = JavaSource.methodBody(source,
                "private ContentStream getRenditionStream(String repositoryId, Content content, String streamId)");

        assertTrue(body.contains("BigInteger.valueOf(-1)"),
                "the rendition stream now states a length. Two things follow: CouchDB reports "
                        + "the COMPRESSED size, so the response truncates; and the recorded "
                        + "length may be a value no measurement confirmed, which the delegate's "
                        + "size fallback is only acceptable while nothing asserts it: " + body);
    }
}
