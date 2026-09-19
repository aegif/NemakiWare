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
package jp.aegif.nemaki.custody.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the trap that {@code CustodyReceiptAssemblerTest} stubs out.
 *
 * <h2>Why this file has to exist</h2>
 *
 * <p>The assembler's tests replace this class wholesale, which is right for checking the
 * comparison and the vocabulary — but it leaves <b>which URL is fetched and what is read out of
 * it</b> untested. That is not a detail: design §13.2's whole point is that on both receivers
 * the value nearest to hand is the wrong one. Swapping {@code extract_file} for
 * {@code pointer_file} would put the AIP's 7z digest into {@code sipDigest} — exactly what §2
 * refuses — and every assembler test would stay green.
 *
 * <p>So these run against a stub {@link HttpClient}: no receiver, no WireMock, but the real URL
 * building, the real streaming hash, and the real manifest parsing.
 *
 * <p><b>What they cannot establish:</b> that a live receiver answers these URLs. That was done
 * separately, by hand, against RODA 6.3.0 and Archivematica 1.18.0 (design §16 and §17) — these
 * tests are not that, and staying green here says nothing about a receiver. Note the two things
 * a live fetch needs that are switched OFF here: {@code AdapterHttpClient} refuses loopback and
 * private addresses unless {@code -Dnemaki.ingest.allowLocalhost=true} (the live runs passed
 * it), and it never follows redirects.
 */
class SubmittedDigestRecoveryTest {

    // The receivers this layer talks to were both measured on localhost, and AdapterHttpClient
    // refuses loopback unless told otherwise. Using a made-up public hostname instead would
    // make these tests depend on DNS -- and on this machine that fails with "Invalid URL",
    // which is a different refusal from the one being exercised.
    //
    // WHAT THIS TURNS OFF, stated so nobody reads these as network-guard coverage: with the
    // property set, sendWithRetry's pinRequestToValidatedAddress returns the request untouched
    // ("Test mode (WireMock): bypass pinning + validation"). So the DNS-rebinding re-check and
    // the HTTP-to-IP-literal rewrite are NOT exercised here, and the URI the stub records is
    // the one this class built rather than a pinned one. Retry-on-429/503 is not exercised
    // either -- these answer 200 and 404. What IS exercised is the part with the traps in it:
    // which URL, what gets hashed, which manifest line.
    @org.junit.jupiter.api.BeforeAll
    static void allowLoopback() {
        System.setProperty("nemaki.ingest.allowLocalhost", "true");
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreLoopbackRule() {
        System.clearProperty("nemaki.ingest.allowLocalhost");
    }

    private static final byte[] PAYLOAD = "the package that was submitted".getBytes(StandardCharsets.UTF_8);

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Records the whole request, not just the URI — the header was a hole once already. */
    private static final class Recorder extends HttpClient {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> asked = new ArrayList<>();
        private final Function<String, Object> answers;
        private final int status;

        Recorder(int status, Function<String, Object> answers) {
            this.status = status;
            this.answers = answers;
        }

        String header(String name) {
            return requests.get(0).headers().firstValue(name).orElse(null);
        }

        String method() {
            return requests.get(0).method();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            asked.add(request.uri().toString());
            Object body = answers.apply(request.uri().toString());
            // A String answer becomes a stream, because BOTH recoveries now read bytes: the
            // manifest fetch was switched off ofString() so an unbounded receiver response
            // cannot be buffered whole. Adapting here rather than rewriting every case keeps
            // the cases readable, and the wire was always bytes anyway.
            if (body instanceof String text) {
                body = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            }
            return (HttpResponse<T>) new StubResponse(request.uri(), status, body);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public HttpClient.Redirect followRedirects() { return HttpClient.Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    /** Reports whether it was closed — the only way to see a leaked connection from here. */
    private static final class ClosingStream extends java.io.ByteArrayInputStream {
        private boolean closed;

        ClosingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }

    private record StubResponse(URI uri, int code, Object body) implements HttpResponse<Object> {
        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<Object>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public Object body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return uri; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    // ------------------------------------------------------------------ RODA

    @Test
    @DisplayName("RODA: the transferred resource is fetched and hashed here")
    void rodaFetchesTheSubmittedBytes() throws Exception {
        Recorder http = new Recorder(200, uri -> new ByteArrayInputStream(PAYLOAD));

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromRodaTransfer("http://localhost:18080/", "tr-uuid-1", "Basic abc");

        assertEquals(1, http.asked.size());
        assertEquals("http://localhost:18080/api/v2/transfers/tr-uuid-1/download",
                http.asked.get(0),
                "the URL changed. This endpoint is the one that serves the bytes the receiver "
                        + "still holds; a response-field read would give no digest at all");
        assertTrue(recovered.present(), recovered.unavailable());
        assertEquals(sha256(PAYLOAD), recovered.sha256Hex(),
                "the recovered value is not the digest of what the receiver returned, so it is "
                        + "not evidence about the package at all");
        // Both receivers need credentials -- Archivematica's Storage Service requires
        // "Authorization: ApiKey user:key" and answers 401 without it. Dropping withAuth(), or
        // misspelling the header, used to leave every test here green and every live fetch 401.
        assertEquals("Basic abc", http.header("Authorization"),
                "the Authorization header did not reach the request");
        assertEquals("GET", http.method(), "the recovery is not a GET");
    }

    @Test
    @DisplayName("no credentials means no header, not an empty one")
    void noAuthorizationSendsNoHeader() {
        Recorder http = new Recorder(200, uri -> new ByteArrayInputStream(PAYLOAD));

        new SubmittedDigestRecovery(http).fromRodaTransfer("http://localhost:18080", "u", null);

        assertNull(http.header("Authorization"),
                "an empty Authorization header was sent, which some receivers reject "
                        + "differently from no header at all");
    }

    @Test
    @DisplayName("AM: the manifest line must match the payload NAME, not merely end with it")
    void archivematicaDoesNotAcceptASuffixMatch() {
        // "data/evil-nemaki-sip.zip" ends with "nemaki-sip.zip". Taking it would attest a
        // different file that happens to sit in the same bag -- and it sorts first here, so a
        // suffix match would prefer it.
        String manifest = "9f".repeat(32) + "  data/evil-nemaki-sip.zip\n"
                + "ab".repeat(32) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> manifest);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertEquals("ab".repeat(32), recovered.sha256Hex(),
                "a suffix match took the wrong line, so the receipt would attest a file this "
                        + "transfer never sent");
    }

    @Test
    @DisplayName("AM: a non-200 body is closed, not left pinning its connection")
    void archivematicaClosesTheBodyOfARefusedResponse() {
        // ofString() consumed the body; ofInputStream() does not. Switching handlers to bound
        // the read turned every 403/404 into a held connection — and this method treats 404 as
        // the EXPECTED case for a package sent as zipfile, so it is the common path, not a rare
        // one. (Found by review of the fix, not by the fix.)
        ClosingStream body = new ClosingStream("nope".getBytes(StandardCharsets.UTF_8));
        Recorder http = new Recorder(404, uri -> body);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present());
        assertTrue(body.closed,
                "the body of a refused response was never closed, so its connection stays "
                        + "pinned; a receiver answering 404 in a loop exhausts the pool");
    }

    @Test
    @DisplayName("AM: a receiver that streams forever is cut off, not buffered whole")
    void archivematicaManifestIsBounded() {
        // The SSRF guard says WHICH host may be reached. It says nothing about HOW MUCH that
        // host sends. The RODA path was streamed against a byte limit from the start and this
        // one used ofString(), so the same external read had two different answers to the same
        // question -- and this is the one an operator points at a receiver they do not run.
        String huge = "0".repeat(2 * 1024 * 1024) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> huge);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present(),
                "an oversized response was read and parsed, so a receiver can decide how much "
                        + "of this JVM's heap to take");
        assertTrue(recovered.unavailable().contains("more than"), recovered.unavailable());
    }

    @Test
    @DisplayName("AM: a manifest of ordinary size is still read — the control")
    void anOrdinaryManifestIsNotRefusedAsOversized() {
        // Without this, a limit of zero would satisfy the test above and recover nothing ever.
        // The one measured against a live Archivematica was 123 bytes (design §17).
        String manifest = "ab".repeat(32) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> manifest);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertEquals("ab".repeat(32), recovered.sha256Hex(), recovered.unavailable());
    }

    @Test
    @DisplayName("AM: a 403 is not reported as 'you probably used zipfile'")
    void archivematicaDistinguishesItsFailures() {
        // The API allowlist returning 403 is a recorded trap (roadmap §9-4). Telling an operator
        // their package was sent as zipfile sends them to rewrite a connector that is fine.
        Recorder http = new Recorder(403, uri -> "");

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present());
        assertTrue(recovered.unavailable().contains("403"), recovered.unavailable());
        assertFalse(recovered.unavailable().contains("zipfile"),
                "a 403 was explained as a zipfile transfer: " + recovered.unavailable());
    }

    @Test
    @DisplayName("RODA: a non-200 is unavailable, and its body is closed")
    void rodaNonOkIsUnavailable() {
        // The close was written on both paths and locked on only one, so deleting hashOf's
        // stayed green while its Archivematica twin was covered. Same leak, same cost.
        ClosingStream body = new ClosingStream(new byte[0]);
        Recorder http = new Recorder(404, uri -> body);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromRodaTransfer("http://localhost:18080", "tr-uuid-1", null);

        assertFalse(recovered.present(),
                "a 404 produced a digest -- of nothing, which would then be compared and "
                        + "refused for the wrong reason");
        assertTrue(recovered.unavailable().contains("404"), recovered.unavailable());
        assertTrue(body.closed,
                "the body of a refused download was never closed, so its connection stays "
                        + "pinned");
    }

    // --------------------------------------------------------- Archivematica

    @Test
    @DisplayName("AM: extract_file is used — NOT pointer_file, which is the AIP's own digest")
    void archivematicaReadsTheShippedManifest() {
        // This is the assertion the assembler's tests cannot make. Swapping this call for
        // pointer_file would put the AIP 7z's PREMIS messageDigest into sipDigest -- an
        // artefact this product has never seen -- and nothing else in the build would notice.
        String manifest = "9f".repeat(32) + "  data/other.zip\n"
                + "ab".repeat(32) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> manifest);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", "ApiKey test:test");

        String asked = http.asked.get(0);
        assertTrue(asked.startsWith("http://localhost:62081/api/v2/file/aip-uuid-1/extract_file/"),
                "the fetch is no longer extract_file: " + asked);
        // withAuth is called at TWO sites -- hashOf for RODA and bodyOf here -- and only the
        // first was asserted. Archivematica's Storage Service answers 401 without this header,
        // so dropping it from this branch alone used to break every live fetch silently.
        assertEquals("ApiKey test:test", http.header("Authorization"),
                "the Authorization header did not reach Archivematica's request");
        assertEquals("GET", http.method());
        // And the path VALUE, not just the parameter name: the stub answers regardless of it,
        // so encoding the wrong thing here would 404 live and stay green.
        assertTrue(asked.contains(java.net.URLEncoder.encode(
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        StandardCharsets.UTF_8)),
                "the path inside the AIP is not what was asked for: " + asked);
        assertFalse(asked.contains("pointer_file"),
                "pointer_file was fetched. Its PREMIS messageDigest is the AIP's 7z, not the "
                        + "package this product sent -- design §13.2: " + asked);
        assertTrue(asked.contains("relative_path_to_file="), asked);

        assertTrue(recovered.present(), recovered.unavailable());
        assertEquals("ab".repeat(32), recovered.sha256Hex(),
                "the wrong manifest line was taken, so the receipt would be about a different "
                        + "file that happens to sit in the same bag");
    }

    @Test
    @DisplayName("AM: a manifest with no line for our payload recovers nothing")
    void archivematicaWithoutOurLineIsUnavailable() {
        Recorder http = new Recorder(200, uri -> "9f".repeat(32) + "  data/someone-else.zip\n");

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present(),
                "a manifest that does not mention our payload still produced a digest");
        assertTrue(recovered.unavailable().contains("nemaki-sip.zip"), recovered.unavailable());
    }

    @Test
    @DisplayName("AM: no manifest at all says so, and says why it might not be there")
    void archivematicaMissingManifestExplains() {
        Recorder http = new Recorder(404, uri -> "");

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present());
        // The likeliest cause is that the package went in as zipfile rather than as a bag, and
        // an operator reading "not found" without that hint will look in the wrong place.
        assertTrue(recovered.unavailable().contains("zipfile"), recovered.unavailable());
    }

    @Test
    @DisplayName("AM: a refused URL is not explained as a zipfile transfer either")
    void archivematicaUrlRefusalIsNotBlamedOnZipfile() {
        // The first obstacle the next increment will hit is the network guard, not the transfer
        // type: both receivers were measured on localhost, which AdapterHttpClient refuses by
        // default. Telling the operator "you probably sent a zipfile" points at the one thing
        // that is fine.
        Recorder http = new Recorder(200, uri -> "");

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("ftp://localhost", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt",
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present());
        assertTrue(recovered.unavailable().contains("refused"), recovered.unavailable());
        assertFalse(recovered.unavailable().contains("zipfile"),
                "a refused URL was explained as a zipfile transfer: " + recovered.unavailable());
        assertTrue(http.asked.isEmpty(), "the request was sent despite the URL being refused");
    }

    @Test
    @DisplayName("AM: no path inside the AIP is a reason, not a NullPointerException")
    void archivematicaWithoutAPathIsAReason() {
        // §14 keeps this argument caller-supplied precisely because the path's middle segment
        // was never confirmed, which makes an absent one the realistic first input. URLEncoder
        // has no null guard and assemble() has no catch, so this used to escape as an NPE --
        // against both this class's contract and Assembled's.
        Recorder http = new Recorder(200, uri -> "");

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1", null,
                        "nemaki-sip.zip", null);

        assertFalse(recovered.present());
        assertTrue(recovered.unavailable().contains("nowhere to look"), recovered.unavailable());
        assertTrue(http.asked.isEmpty());
    }

    @Test
    @DisplayName("a URL the SSRF guard refuses is unavailable, not an exception")
    void aRefusedUrlIsUnavailable() {
        // Both measured receivers were on localhost, which AdapterHttpClient refuses unless
        // -Dnemaki.ingest.allowLocalhost=true. That has to arrive as a recorded reason rather
        // than as a stack trace, because it is the first thing the next increment will hit.
        Recorder http = new Recorder(200, uri -> new ByteArrayInputStream(PAYLOAD));

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromRodaTransfer("ftp://localhost", "tr-uuid-1", null);

        assertFalse(recovered.present());
        assertTrue(recovered.unavailable().contains("refused"), recovered.unavailable());
        assertTrue(http.asked.isEmpty(), "the request was sent despite the URL being refused");
    }

    @Test
    @DisplayName("AM: no payload name is 'the caller passed nothing', not a receiver finding")
    void archivematicaSaysWhenTheCallerGaveNoPayloadName() {
        // equals(null) is false for every line, so an UNASKED question came out as the
        // receiver's answer: "the manifest has no line for null, so it does not describe the
        // package this transfer sent" — a finding about the far end, delivered after a real
        // GET, that reads like the receiver ingested something else. The assembler wraps it
        // with "no receipt is built", so an operator opens a conversation with another
        // organisation over an argument their own caller forgot to pass.
        //
        // The sibling argument was already guarded ("no path inside the AIP was given, so
        // there is nowhere to look"). Of the three, this was the arm that was not.
        String manifest = "ab".repeat(32) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> manifest);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-sha256.txt", null, null);

        assertNull(recovered.sha256Hex(), "a digest was recovered for a payload nobody named");
        assertTrue(recovered.unavailable().contains("no payload name was given"),
                "the refusal blames the receiver for an argument the caller did not pass: "
                        + recovered.unavailable());
        assertFalse(recovered.unavailable().contains("does not describe the package"),
                "the refusal still reads as a finding about what the receiver holds: "
                        + recovered.unavailable());
    }

    @Test
    @DisplayName("AM: a line that is not a SHA-256 is refused, not returned as one")
    void archivematicaRefusesADigestThatIsNotASha256() {
        // The path into the AIP is caller-supplied by design, so pointing at manifest-md5.txt
        // is an ordinary slip. Unchecked, a 32-digit MD5 came back AS a sha256Hex and lost the
        // comparison downstream — where the refusal blames the RECEIVER for holding a different
        // package. A wrong file of ours, reported as their mistake.
        String md5Manifest = "ab".repeat(16) + "  data/nemaki-sip.zip\n";
        Recorder http = new Recorder(200, uri -> md5Manifest);

        SubmittedDigestRecovery.Recovered recovered = new SubmittedDigestRecovery(http)
                .fromArchivematicaManifest("http://localhost:62081", "aip-uuid-1",
                        "data/objects/metadata/transfers/t-1/manifest-md5.txt",
                        "nemaki-sip.zip", null);

        assertNull(recovered.sha256Hex(),
                "a 32-digit digest was returned as a SHA-256: " + recovered.sha256Hex());
        assertTrue(recovered.unavailable().contains("not carry a SHA-256"),
                "the refusal does not say the file is the wrong kind: " + recovered.unavailable());
        assertTrue(recovered.unavailable().contains("manifest-md5.txt"),
                "the refusal names a file nobody opened instead of the one that was read: "
                        + recovered.unavailable());
    }

    /** A body that sends one byte and then blocks until someone closes it. */
    private static java.io.InputStream stallingBody() {
        return new java.io.InputStream() {
            private final Object lock = new Object();
            private boolean closed;
            private boolean sentOne;

            @Override
            public int read() throws java.io.IOException {
                if (!sentOne) {
                    sentOne = true;
                    return 'x';
                }
                synchronized (lock) {
                    while (!closed) {
                        try {
                            lock.wait(java.time.Duration.ofMinutes(5).toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("interrupted");
                        }
                    }
                    throw new java.io.IOException("stream closed under the reader");
                }
            }

            @Override
            public void close() {
                synchronized (lock) {
                    closed = true;
                    lock.notifyAll();
                }
            }
        };
    }

    @Test
    @DisplayName("RODA: a receiver that answers and then stops says so, and does not hang")
    void rodaSaysWhenTheReceiverWentQuiet() {
        // Through the REAL hashOf, not the watchdog in isolation. The diagnostic arm — the
        // sentence that separates "the receiver stopped talking" from "the receiver could not
        // be read" — was reachable by no test: deleting both catch blocks left every existing
        // test green, and an operator would have been sent to the network for a receiver that
        // answered and went quiet.
        Recorder http = new Recorder(200, uri -> stallingBody());
        SubmittedDigestRecovery recovery = new SubmittedDigestRecovery(http);
        recovery.setBodyBudgetForTest(java.time.Duration.ofMillis(200));

        long started = System.nanoTime();
        SubmittedDigestRecovery.Recovered recovered =
                recovery.fromRodaTransfer("http://localhost:18080", "u", null);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNull(recovered.sha256Hex(), "a digest was computed from a body that never arrived");
        assertTrue(recovered.unavailable().contains("started answering"),
                "a stalled receiver is reported as an ordinary read failure, which sends an "
                        + "operator to the network: " + recovered.unavailable());
        assertTrue(elapsedMs < 30_000,
                "the read took " + elapsedMs + "ms, so the budget is not what ended it");
    }

    @Test
    @DisplayName("AM: the manifest read has the same diagnostic, not just the same budget")
    void archivematicaSaysWhenTheReceiverWentQuiet() {
        // The sibling arm. These two reads have each been corrected once for something the
        // other already had, so naming both.
        Recorder http = new Recorder(200, uri -> stallingBody());
        SubmittedDigestRecovery recovery = new SubmittedDigestRecovery(http);
        recovery.setBodyBudgetForTest(java.time.Duration.ofMillis(200));

        SubmittedDigestRecovery.Recovered recovered = recovery.fromArchivematicaManifest(
                "http://localhost:62081", "aip-uuid-1",
                "data/objects/metadata/transfers/t-1/manifest-sha256.txt", "nemaki-sip.zip", null);

        assertNull(recovered.sha256Hex());
        assertTrue(recovered.unavailable().contains("started answering"),
                "the manifest read reports a stalled receiver as an ordinary failure: "
                        + recovered.unavailable());
    }
}
