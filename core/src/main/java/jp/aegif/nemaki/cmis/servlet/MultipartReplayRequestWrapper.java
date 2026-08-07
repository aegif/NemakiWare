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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

/**
 * A multipart request whose body can be read again after the container has parsed it.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>A servlet body is single-use. This servlet must read request PARAMETERS before it can
 * route a Browser Binding POST — {@code cmisaction} decides between checkOut, applyACL, type
 * operations and the ordinary CMIS pipeline, and the Browser Binding sends it in the BODY.
 * Asking the container for a parameter makes it parse (and consume) the multipart body, so by
 * the time OpenCMIS's own {@code MultipartParser} runs there is nothing left to read and it
 * fails the request with "Invalid multipart request!".
 *
 * <p>OpenCMIS 1.1.0-nemakiware carried a fork-local patch for this: its parser noticed the
 * drained stream and fell back to the Servlet Parts API. That patch does not exist in the
 * 2.0.0 line, and depending on a fork-local behaviour for a problem this side creates is the
 * wrong way round anyway. So the request is repaired here instead: the container parses once,
 * and this wrapper hands OpenCMIS a faithful REPLAY of the same body.
 *
 * <h2>What "faithful" means</h2>
 *
 * <p>Every part is re-emitted with ALL of its original headers, in order, so
 * {@code Content-Disposition} (including any {@code filename*} encoding) and
 * {@code Content-Type} reach OpenCMIS exactly as the client sent them. Only the boundary
 * changes — it must, since the original boundary is not recoverable from the parsed parts —
 * and {@link #getContentType()} announces the new one. Bodies stream from the container's own
 * part storage (memory below the configured threshold, a temp file above it), so a 2GB upload
 * is never materialised in the heap.
 */
public class MultipartReplayRequestWrapper extends HttpServletRequestWrapper {

    private static final byte[] CRLF = {'\r', '\n'};

    private final String boundary;
    private final List<Part> parts;
    private final java.util.Map<String, String> synthetic = new java.util.LinkedHashMap<>();
    private ServletInputStream stream;

    /**
     * @throws ServletException if the container refuses the multipart body (a malformed part
     *         filename surfaces here, which is where the caller translates it to a 400)
     */
    public MultipartReplayRequestWrapper(HttpServletRequest request)
            throws IOException, ServletException {
        super(request);
        // The container parse happens HERE, once, deliberately — everything downstream reads
        // the replay instead of the (now drained) socket.
        this.parts = new ArrayList<>(request.getParts());
        this.boundary = "NemakiWareReplay" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Add a field to the replayed body that the client did not send.
     *
     * <p>Used for exactly one thing: the {@code folderId} → {@code objectId} mapping this
     * servlet has always applied for CMIS clients (the TCK included) that name the parent
     * with {@code folderId}. That mapping used to be a request wrapper overriding
     * {@code getParameter}, which OpenCMIS never sees on a multipart request — its own
     * wrapper answers parameters from the multipart fields IT parsed, so an injected
     * parameter is shadowed and the create fails with "folderId must be set". Putting the
     * field in the BODY puts it where OpenCMIS actually looks.
     *
     * @throws IllegalStateException if the body has already been handed out
     */
    public void addSyntheticField(String name, String value) {
        if (stream != null) {
            throw new IllegalStateException(
                    "the replayed body was already read; '" + name + "' would not be in it");
        }
        synthetic.put(name, value);
    }

    /** True when this request is a multipart form the container is able to parse. */
    public static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, 10);
    }

    @Override
    public String getContentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    @Override
    public int getContentLength() {
        long length = computeContentLength();
        return length > Integer.MAX_VALUE ? -1 : (int) length;
    }

    @Override
    public long getContentLengthLong() {
        return computeContentLength();
    }

    @Override
    public String getHeader(String name) {
        if ("content-type".equalsIgnoreCase(name)) {
            return getContentType();
        }
        if ("content-length".equalsIgnoreCase(name)) {
            return Long.toString(computeContentLength());
        }
        // The original body was consumed by the container; a Transfer-Encoding of the socket
        // says nothing about the replay, and leaving it would contradict our Content-Length.
        if ("transfer-encoding".equalsIgnoreCase(name)) {
            return null;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if ("content-type".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of(getContentType()));
        }
        if ("content-length".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of(Long.toString(computeContentLength())));
        }
        if ("transfer-encoding".equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.emptyList());
        }
        return super.getHeaders(name);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new ReplayStream(buildBody());
        }
        return stream;
    }

    /** Reading the body as characters is not part of the Browser Binding's contract. */
    @Override
    public java.io.BufferedReader getReader() throws IOException {
        return new java.io.BufferedReader(
                new java.io.InputStreamReader(getInputStream(), StandardCharsets.ISO_8859_1));
    }

    // ------------------------------------------------------------------ body reassembly

    /** Header block for one part: the boundary line, the part's own headers, a blank line. */
    private byte[] partHeader(Part part) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        boolean sawDisposition = false;
        for (String name : part.getHeaderNames()) {
            if ("content-disposition".equalsIgnoreCase(name)) {
                sawDisposition = true;
            }
            for (String value : part.getHeaders(name)) {
                sb.append(name).append(": ").append(value).append("\r\n");
            }
        }
        if (!sawDisposition) {
            // Every multipart/form-data part is required to carry one; synthesise the minimum
            // rather than emit a part OpenCMIS would not be able to name.
            sb.append("Content-Disposition: form-data; name=\"").append(part.getName()).append('"');
            if (part.getSubmittedFileName() != null) {
                sb.append("; filename=\"").append(part.getSubmittedFileName()).append('"');
            }
            sb.append("\r\n");
        }
        sb.append("\r\n");
        // HTTP header bytes are ISO-8859-1; using anything else would change their length and
        // therefore the Content-Length computed from it.
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private byte[] closingBoundary() {
        return ("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Header + body + CRLF for a field this side adds. */
    private byte[] syntheticPart(String name, String value) {
        String text = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private long computeContentLength() {
        long total = 0;
        for (Part part : parts) {
            total += partHeader(part).length;
            total += part.getSize();
            total += CRLF.length;
        }
        for (java.util.Map.Entry<String, String> e : synthetic.entrySet()) {
            total += syntheticPart(e.getKey(), e.getValue()).length;
        }
        return total + closingBoundary().length;
    }

    /** The parts, lazily: each body streams from the container's own storage. */
    private InputStream buildBody() throws IOException {
        List<InputStream> segments = new ArrayList<>(parts.size() * 3 + 1);
        for (Part part : parts) {
            segments.add(new ByteArrayInputStream(partHeader(part)));
            segments.add(part.getInputStream());
            segments.add(new ByteArrayInputStream(CRLF));
        }
        for (java.util.Map.Entry<String, String> e : synthetic.entrySet()) {
            segments.add(new ByteArrayInputStream(syntheticPart(e.getKey(), e.getValue())));
        }
        segments.add(new ByteArrayInputStream(closingBoundary()));
        return new SequenceInputStream(Collections.enumeration(segments));
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return parts;
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    /** A ServletInputStream over the reassembled body. */
    private static final class ReplayStream extends ServletInputStream {

        private final InputStream delegate;
        private boolean finished;

        private ReplayStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b < 0) {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n < 0) {
                finished = true;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("the replayed body is read synchronously");
        }
    }
}
