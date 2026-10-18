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
package jp.aegif.nemaki.cmis.servlet;

import jp.aegif.nemaki.util.test.HarnessBroken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replayed body is the same multipart request the client sent.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>A servlet body can be read once. This servlet has to read request parameters to route a
 * Browser Binding POST, which makes the container consume the body — and then OpenCMIS's
 * parser finds nothing and rejects every upload with "Invalid multipart request!". The wrapper
 * repairs that by re-emitting the container's parsed parts as a fresh multipart body.
 *
 * <p>"Re-emitting" is where a subtle bug would live: a dropped header loses the part's name or
 * its content type, a mis-computed length truncates the last part, a CRLF in the wrong place
 * makes the whole body unparseable. So this parses the replay back and compares it to the
 * input, rather than asserting on fragments of the generated text.
 */
class MultipartReplayRequestWrapperTest {

    /** A minimal in-memory Part, standing in for the container's own. */
    private static Part part(String name, String filename, String contentType, byte[] body) {
        Part p = mock(Part.class);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        StringBuilder disposition = new StringBuilder("form-data; name=\"" + name + "\"");
        if (filename != null) {
            disposition.append("; filename=\"").append(filename).append('"');
        }
        headers.put("Content-Disposition", List.of(disposition.toString()));
        if (contentType != null) {
            headers.put("Content-Type", List.of(contentType));
        }
        when(p.getName()).thenReturn(name);
        when(p.getSubmittedFileName()).thenReturn(filename);
        when(p.getContentType()).thenReturn(contentType);
        when(p.getSize()).thenReturn((long) body.length);
        when(p.getHeaderNames()).thenReturn(headers.keySet());
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            when(p.getHeaders(e.getKey())).thenReturn(e.getValue());
        }
        try {
            when(p.getInputStream()).thenAnswer(i -> new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new HarnessBroken(e);
        }
        return p;
    }

    private static MultipartReplayRequestWrapper wrap(List<Part> parts) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=originalBoundary");
        when(request.getParts()).thenAnswer(i -> parts);
        return new MultipartReplayRequestWrapper(request);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** A deliberately small multipart parser: enough to prove the replay round-trips. */
    private static List<Map<String, Object>> parse(byte[] body, String boundary) {
        List<Map<String, Object>> out = new ArrayList<>();
        String text = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        assertTrue(text.endsWith(delimiter + "--\r\n"), "the body must end with the closing boundary");
        // Split on the delimiter itself: each following segment opens with CRLF (another part)
        // or with "--" (the close). Splitting on delimiter+CRLF instead would let a part whose
        // CONTENT contains CRLF swallow the closing marker — which is exactly the case here.
        String[] segments = text.split(java.util.regex.Pattern.quote(delimiter), -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.startsWith("--")) {
                break; // closing boundary
            }
            assertTrue(segment.startsWith("\r\n"), "a part must follow its boundary with CRLF");
            segment = segment.substring(2);
            int split = segment.indexOf("\r\n\r\n");
            assertTrue(split > 0, "each part needs a header block terminated by a blank line");
            Map<String, Object> part = new LinkedHashMap<>();
            for (String header : segment.substring(0, split).split("\r\n")) {
                int colon = header.indexOf(':');
                part.put(header.substring(0, colon).toLowerCase(), header.substring(colon + 2));
            }
            String rest = segment.substring(split + 4);
            assertTrue(rest.endsWith("\r\n"), "a part body is terminated by CRLF before the boundary");
            part.put("body", rest.substring(0, rest.length() - 2)
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.add(part);
        }
        return out;
    }

    @Test
    @DisplayName("再生した body を parse し直すと、元の part と一致する")
    void theReplayRoundTrips() throws Exception {
        // The NUL is the point of this fixture (the replay must be binary-safe), but it is
        // written as an escape: a literal 0x00 in the source makes git classify this file
        // as binary and its diffs vanish. See SourceTreeNulByteTest.
        byte[] content = "hello \r\n binary \0 bytes".getBytes(StandardCharsets.ISO_8859_1);
        List<Part> parts = List.of(
                part("cmisaction", null, null, "createDocument".getBytes(StandardCharsets.ISO_8859_1)),
                part("propertyId[0]", null, null, "cmis:name".getBytes(StandardCharsets.ISO_8859_1)),
                part("content", "upload.txt", "text/plain", content));

        MultipartReplayRequestWrapper wrapper = wrap(parts);
        String boundary = wrapper.getContentType().replaceAll(".*boundary=", "");
        List<Map<String, Object>> replayed = parse(readAll(wrapper.getInputStream()), boundary);

        // The leading part is the _charset_ declaration this wrapper always emits.
        assertEquals(4, replayed.size(), "every part must survive the replay");
        assertEquals("form-data; name=\"cmisaction\"", replayed.get(1).get("content-disposition"));
        assertEquals("createDocument",
                new String((byte[]) replayed.get(1).get("body"), StandardCharsets.ISO_8859_1));
        assertEquals("form-data; name=\"content\"; filename=\"upload.txt\"",
                replayed.get(3).get("content-disposition"),
                "the filename must reach OpenCMIS exactly as the client sent it");
        assertEquals("text/plain", replayed.get(3).get("content-type"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(content,
                (byte[]) replayed.get(3).get("body"),
                "content bytes must be byte-identical — including CRLF and NUL");
    }

    /**
     * Content-Length matches the bytes actually produced.
     *
     * <p>A length one byte short truncates the closing boundary and the parser rejects the
     * whole request; one byte long and it blocks waiting for data that never comes. Neither
     * shows up in a test that only inspects the generated text.
     */
    @Test
    @DisplayName("Content-Length が実際に生成される byte 数と一致する")
    void theDeclaredLengthMatchesTheBody() throws Exception {
        List<Part> parts = List.of(
                part("cmisaction", null, null, "createDocument".getBytes(StandardCharsets.ISO_8859_1)),
                part("content", "a.bin", "application/octet-stream", new byte[4096]));

        MultipartReplayRequestWrapper wrapper = wrap(parts);

        assertEquals(readAll(wrapper.getInputStream()).length, wrapper.getContentLengthLong());
        assertEquals(Long.toString(wrapper.getContentLengthLong()), wrapper.getHeader("Content-Length"));
    }

    /**
     * The announced boundary is the one the body actually uses.
     *
     * <p>The original boundary cannot be recovered from parsed parts, so a new one is minted —
     * which is only safe while {@code getContentType()} reports it. Reporting the original
     * would leave OpenCMIS scanning for a delimiter that never appears.
     */
    @Test
    @DisplayName("announce する boundary と body 中の区切りが一致する (元の値は復元不能)")
    void theBoundaryIsTheOneInTheBody() throws Exception {
        MultipartReplayRequestWrapper wrapper = wrap(List.of(
                part("cmisaction", null, null, "query".getBytes(StandardCharsets.ISO_8859_1))));

        String contentType = wrapper.getContentType();
        String boundary = contentType.replaceAll(".*boundary=", "");
        String body = new String(readAll(wrapper.getInputStream()), StandardCharsets.ISO_8859_1);

        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);
        assertTrue(body.startsWith("--" + boundary + "\r\n"), "body must open with the announced boundary");
        assertTrue(body.endsWith("--" + boundary + "--\r\n"), "body must close with it too");
        assertEquals(contentType, wrapper.getHeader("Content-Type"),
                "the header view must agree with getContentType()");
    }

    /**
     * A synthetic field lands in the BODY, where OpenCMIS looks.
     *
     * <p>This is how the {@code folderId} → {@code objectId} mapping reaches OpenCMIS. It used
     * to be a {@code getParameter} override, which OpenCMIS never consults on a multipart
     * request — it answers from the fields its own parser read — so createDocument failed with
     * "folderId must be set" even though folderId was right there in the request.
     */
    @Test
    @DisplayName("追加したフィールドが body に入り、Content-Length にも算入される")
    void aSyntheticFieldIsPartOfTheBody() throws Exception {
        MultipartReplayRequestWrapper wrapper = wrap(List.of(
                part("cmisaction", null, null, "createDocument".getBytes(StandardCharsets.ISO_8859_1)),
                part("folderId", null, null, "folder-1".getBytes(StandardCharsets.ISO_8859_1))));

        wrapper.addSyntheticField("objectId", "folder-1");

        String boundary = wrapper.getContentType().replaceAll(".*boundary=", "");
        byte[] body = readAll(wrapper.getInputStream());
        List<Map<String, Object>> replayed = parse(body, boundary);

        assertEquals(4, replayed.size(), "the synthetic field must be a part like any other");
        assertEquals("form-data; name=\"objectId\"", replayed.get(1).get("content-disposition"));
        assertEquals("folder-1",
                new String((byte[]) replayed.get(1).get("body"), StandardCharsets.ISO_8859_1));
        assertEquals(body.length, wrapper.getContentLengthLong(),
                "a synthetic part that is not counted truncates the body");
    }

    /**
     * The replay declares which charset its field VALUES are in.
     *
     * <p>Values are replayed as raw bytes, so without this OpenCMIS decodes them as
     * ISO-8859-1 and a Japanese file name arrives as its UTF-8 bytes read one at a time.
     * {@code _charset_} is the multipart convention OpenCMIS implements — and it must LEAD the
     * body, because the parser applies it only to the fields it reads after it.
     */
    @Test
    @DisplayName("_charset_ が body の先頭に来る — 後ろだと後続フィールドにしか効かない")
    void theCharsetIsDeclaredFirst() throws Exception {
        MultipartReplayRequestWrapper wrapper = wrap(List.of(
                part("cmisaction", null, null, "createDocument".getBytes(StandardCharsets.ISO_8859_1)),
                part("propertyValue[0]", null, null,
                        "日本語.txt".getBytes(StandardCharsets.UTF_8))));

        String boundary = wrapper.getContentType().replaceAll(".*boundary=", "");
        List<Map<String, Object>> replayed = parse(readAll(wrapper.getInputStream()), boundary);

        assertEquals("form-data; name=\"_charset_\"", replayed.get(0).get("content-disposition"),
                "_charset_ must be the FIRST part or the fields before it decode as ISO-8859-1");
        assertEquals("UTF-8",
                new String((byte[]) replayed.get(0).get("body"), StandardCharsets.ISO_8859_1));
        // The value itself is passed through untouched — it is the DECLARATION that fixes it.
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                "日本語.txt".getBytes(StandardCharsets.UTF_8),
                (byte[]) replayed.get(2).get("body"));
    }

    /** Adding a field after the body was handed out would silently not appear in it. */
    @Test
    @DisplayName("body を渡した後の追加は例外 — 黙って落ちるより早く落ちる")
    void addingAfterTheBodyWasReadIsRefused() throws Exception {
        MultipartReplayRequestWrapper wrapper = wrap(List.of(
                part("cmisaction", null, null, "query".getBytes(StandardCharsets.ISO_8859_1))));
        wrapper.getInputStream();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> wrapper.addSyntheticField("objectId", "too-late"));
    }

    /**
     * Transfer-Encoding from the original socket must not survive.
     *
     * <p>The replay is a fixed-length body. Leaving a chunked Transfer-Encoding header beside
     * our Content-Length would describe two different framings of the same request.
     */
    @Test
    @DisplayName("元の Transfer-Encoding は引き継がない (再生は固定長)")
    void chunkedFramingIsNotCarriedOver() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=b");
        when(request.getHeader("Transfer-Encoding")).thenReturn("chunked");
        when(request.getParts()).thenAnswer(i -> List.of(
                part("cmisaction", null, null, "query".getBytes(StandardCharsets.ISO_8859_1))));

        MultipartReplayRequestWrapper wrapper = new MultipartReplayRequestWrapper(request);

        org.junit.jupiter.api.Assertions.assertNull(wrapper.getHeader("Transfer-Encoding"));
    }
}
