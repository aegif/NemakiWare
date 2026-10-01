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
package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.internal.LazilyParsedNumber;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

/**
 * Listing a folder must not ask CouchDB for the same documents twice.
 *
 * <h2>The saving, and what it costs to get wrong</h2>
 *
 * <p>The {@code children} view is {@code emit(doc.parentId, doc)} — the value it stores <em>is</em>
 * the document. The DAO nevertheless passed {@code include_docs=true}, so CouchDB looked every
 * child up again by id and put a second copy in the response: on a 50-child folder that is 40 ms
 * and 93 KB where 5 ms and 49 KB would do, and folder listing was where 15 of 16 request threads
 * were waiting once authentication stopped dominating.
 *
 * <p>Reading the value instead means the row is no longer a typed {@code Document} but the raw
 * JSON the view emitted, which arrives with Gson's own number type. That is the part that can
 * break quietly: a mis-typed {@code created} does not throw, it produces a document whose dates
 * are wrong. So these tests feed the shape CouchDB actually returns — {@code LazilyParsedNumber}
 * included — and check the converted model, not just that something came back.
 */
class ChildrenViewValueTest {

    private static Content convert(Object viewValue) throws ReflectiveOperationException {
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        Method m = ContentDaoServiceImpl.class.getDeclaredMethod("convertViewValueToContent", Object.class);
        m.setAccessible(true);
        return (Content) m.invoke(dao, viewValue);
    }

    /** The value shape CouchDB emits for a document child, numbers as Gson parses them. */
    private static Map<String, Object> documentValue() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("_id", "614f4643d5c7eef37e94a5426113e2e2");
        v.put("_rev", "1-9f3b1c2d4e5f60718293a4b5c6d7e8f9");
        v.put("type", "cmis:document");
        v.put("objectType", "cmis:document");
        v.put("name", "報告書.txt");
        v.put("description", "");
        v.put("parentId", "4383c1a96093a7526774f8d2db12f00c");
        v.put("creator", "admin");
        v.put("modifier", "admin");
        v.put("created", new LazilyParsedNumber("1754584080123"));
        v.put("modified", new LazilyParsedNumber("1754584090456"));
        v.put("changeToken", "1");
        v.put("latestVersion", Boolean.TRUE);
        v.put("majorVersion", Boolean.TRUE);
        v.put("versionLabel", "1.0");
        v.put("versionSeriesId", "vs-1");
        v.put("aclInherited", Boolean.TRUE);
        return v;
    }

    @Test
    @DisplayName("view の value から document が正しく組み立つ (日付・名前・親)")
    void aDocumentIsBuiltFromTheEmittedValue() throws ReflectiveOperationException {
        Content c = convert(documentValue());

        assertNotNull(c, "the emitted value carries everything the document path had");
        assertInstanceOf(Document.class, c, "cmis:document must map to the document model");
        assertEquals("614f4643d5c7eef37e94a5426113e2e2", c.getId());
        assertEquals("報告書.txt", c.getName(), "a non-ASCII name must survive the value path");
        assertEquals("4383c1a96093a7526774f8d2db12f00c", c.getParentId());
        assertEquals("admin", c.getCreator());
        assertNotNull(c.getCreated(), "created must parse — a wrong date fails silently otherwise");
        assertEquals(1754584080123L, c.getCreated().getTimeInMillis());
        assertEquals(1754584090456L, c.getModified().getTimeInMillis());
    }

    @Test
    @DisplayName("folder の value は folder になる")
    void aFolderIsBuiltFromTheEmittedValue() throws ReflectiveOperationException {
        Map<String, Object> v = documentValue();
        v.put("type", "cmis:folder");
        v.put("objectType", "cmis:folder");
        v.put("name", "bench-folder-00");

        Content c = convert(v);
        assertInstanceOf(Folder.class, c, "cmis:folder must map to the folder model");
        assertEquals("bench-folder-00", c.getName());
    }

    @Test
    @DisplayName("value が無い・型違いなら null を返して落ちない")
    void aMissingOrUnexpectedValueIsNotFatal() throws ReflectiveOperationException {
        assertNull(convert(null));
        assertNull(convert("not a document"), "a scalar value must not blow up the listing");
    }

    /**
     * The reason the value may be trusted at all is that these queries use CouchDB's default
     * freshness. Under {@code stale=ok} / {@code update=false} the index can lag the document,
     * and the emitted value would then be a snapshot rather than the current revision.
     */
    @Test
    @DisplayName("children を読む問い合わせが stale 指定を持ち込んでいない")
    void theChildQueriesDoNotAskForAStaleIndex() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java"),
                StandardCharsets.UTF_8);
        for (String method : List.of("public List<Content> getChildren(String repositoryId, String parentId) {",
                "public List<Content> getChildrenPaged(String repositoryId, String parentId, int skip, int limit) {")) {
            String body = stripComments(bodyOf(src, method));
            // Omitting the key is not enough: CloudantClientWrapper.queryView defaults
            // include_docs to true when the caller does not set it, so the only way to stop the
            // second copy is to say false out loud. A version of this test that merely asserted
            // the token was ABSENT passed against a build that still fetched every document.
            assertTrue(body.contains("queryParams.put(\"include_docs\", false)"),
                    method + " must set include_docs=false explicitly; omitting it leaves the"
                            + " wrapper's default of true in place and the fix is a no-op");
            assertFalse(body.contains("include_docs\", true"),
                    method + " re-fetches documents the view already emitted");
            assertFalse(body.contains("stale") || body.contains("\"update\""),
                    method + " reads a possibly stale index, where the emitted value may no longer"
                            + " match the document — go back to include_docs if this is intended");
        }
    }

    /** Comments explain the very tokens being banned, so they must not count as uses. */
    private static String stripComments(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            int slashes = line.indexOf("//");
            out.append(slashes >= 0 ? line.substring(0, slashes) : line).append('\n');
        }
        return out.toString().replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String bodyOf(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found — the test needs updating: " + signature);
        int i = source.indexOf('{', start);
        int depth = 0;
        for (int j = i; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(i, j + 1);
                }
            }
        }
        throw new jp.aegif.nemaki.util.test.HarnessBroken(
                "unbalanced braces after " + signature);
    }
}
