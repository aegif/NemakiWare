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

import jp.aegif.nemaki.rest.ingest.AdapterHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Getting the digest of what a receiver gives back about our package — which is never the
 * digest nearest to hand.
 *
 * <p><b>Not "what the RECEIVER holds", which is what this said.</b> RODA returns the submitted
 * bytes, so there it is true; Archivematica returns a line from a manifest this product wrote
 * and the receiver stored, which says what our payload hashed to WHEN WE SHIPPED IT and rests on
 * the receiver having run {@code Verify bag} to mean anything more.
 *
 * <h2>Why this needs its own class</h2>
 *
 * <p>{@code CustodyReceipt.sipDigest} exists so a receipt names <b>our</b> package (P3-4 §2). On
 * both receivers measured, the obvious value is the wrong one:
 *
 * <ul>
 *   <li><b>RODA</b> puts no checksum in any response field. A connector that filled
 *       {@code sipDigest} from our own record would make the later comparison our value against
 *       itself — a check that cannot fail, which is the same as no check.</li>
 *   <li><b>Archivematica</b> offers the pointer file's PREMIS {@code messageDigest}. That is the
 *       digest of the AIP's 7z: an artefact this product has never seen. Putting it in
 *       {@code sipDigest} is precisely the "AIP checksum only" receipt §2 refuses.</li>
 * </ul>
 *
 * <p>So the digest has to be fetched, and the two receivers need different fetches.
 *
 * <h2>The two shapes</h2>
 *
 * <table>
 *   <caption>What is recovered, per receiver</caption>
 *   <tr><th></th><th>Fetch</th><th>Recovered value</th></tr>
 *   <tr><td>RODA</td>
 *       <td>{@code GET /api/v2/transfers/{uuid}/download}</td>
 *       <td>the submitted BYTES, hashed here</td></tr>
 *   <tr><td>Archivematica</td>
 *       <td>{@code GET /api/v2/file/{uuid}/extract_file/?relative_path_to_file=…}</td>
 *       <td>a LINE from the bag's {@code manifest-sha256.txt}, kept inside the AIP</td></tr>
 * </table>
 *
 * <p>The asymmetry is not a style choice. Archivematica's {@code automated} processing config
 * <b>extracts and discards the payload zip</b> (measured — §12), so the submitted bytes are not
 * there to fetch; what survives is a copy of the manifest this product shipped. That is also why
 * this only works for the {@code zipped bag} route: an E-ARK SIP sent as {@code zipfile} ingests
 * perfectly well and leaves no manifest behind, so there is nothing to recover.
 *
 * <h2>What a recovered value is for</h2>
 *
 * <p><b>It is compared, not copied.</b> A recovery that does not match the digest of the package
 * this product sent is a refusal, not a receipt: an ingest of something else is not evidence
 * about this handover. {@link CustodyReceiptAssembler} enforces that; nothing here decides it.
 *
 * <p><b>Measured on both receivers</b> (design §16 RODA, §17 Archivematica). RODA returns the
 * submitted bytes byte-for-byte; Archivematica keeps the shipped manifest line intact, at
 * {@code {name}-{AIP uuid}/data/objects/metadata/transfers/{name}-{TRANSFER uuid}/}. Both
 * recovered values matched what was sent, and both negative controls — a transfer that sent
 * something else, and Archivematica's OWN manifest at the AIP root — were refused.
 *
 * <p><b>Still unmeasured:</b> other versions of either receiver, Archivematica's {@code default}
 * processing configuration, and how long a receiver keeps the thing being read (RODA's
 * transferred resource was still there after ingest, measured within one job only).
 */
// Not final: the two recovery methods are the seam a test replaces, so that the assembler's
// rules -- which are the part with the traps in them -- can be checked without a receiver. The
// alternative (an interface with one implementation) buys nothing here.
public class SubmittedDigestRecovery {

    private static final Logger logger = LoggerFactory.getLogger(SubmittedDigestRecovery.class);

    /**
     * How long the receiver has to start answering.
     *
     * <p>THIS ONLY COVERS THE HEADERS. {@code HttpRequest.timeout()} is satisfied the moment a
     * response line arrives, and with {@code BodyHandlers.ofInputStream()} the body is read
     * afterwards, by us, outside any timer the client is keeping. Measured on Temurin 21: with a
     * 2-second request timeout the send returned in 23 ms, and a receiver that sent one byte and
     * then stopped left {@code read()} blocked with no exception until the JVM was killed.
     *
     * <p>So {@link #BODY_BUDGET} bounds the read separately. The earlier comment here said this
     * value was "short enough to fail rather than hang", which was true when this class used
     * {@code ofString()} and stopped being true when it was changed to stream — the same shape
     * design §19 records: swapping the means also swapped a contract nobody was looking at.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * How long the whole body may take, once it has started arriving.
     *
     * <p>Enforced by closing the stream from a watchdog rather than by checking the clock between
     * reads: a receiver that stalls blocks INSIDE {@code read()}, so a loop that tests a deadline
     * at the top of each iteration never gets to test it. Closing the response stream makes the
     * blocked read fail, which is the only lever that works from outside.
     */
    private static final Duration BODY_BUDGET = Duration.ofMinutes(10);

    /**
     * The budget this instance uses. {@link #BODY_BUDGET} except in tests.
     *
     * <p>An instance field rather than a constant so the diagnostic arms can be driven through
     * the REAL {@code hashOf} / {@code bodyOf}. Without it the only affordable test was of
     * {@code BodyBudget} in isolation, which left both "the receiver started answering and then
     * stopped" messages — the whole point of distinguishing a stall from a dropped connection —
     * reachable by no test at all.
     */
    private Duration bodyBudget = BODY_BUDGET;

    /** Visible for tests, which cannot wait out ten minutes. */
    void setBodyBudgetForTest(Duration budget) {
        this.bodyBudget = budget;
    }

    /** What a SHA-256 looks like written out, so another digest cannot pass for one. */
    private static final java.util.regex.Pattern SHA256_HEX =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /**
     * Refuses a body big enough to be a denial of service rather than a package.
     *
     * <p>The receiver is trusted to the extent of being talked to, not to the extent of being
     * allowed to stream indefinitely into this JVM.
     */
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * How much of a manifest is read before giving up.
     *
     * <p>Far smaller than {@link #MAX_BYTES}, because the two reads are different things: that
     * one is a package, this one is a text file listing a bag's payload. The one measured against
     * a live Archivematica was 123 bytes (design §17) and Archivematica's own AIP manifest, in
     * the same AIP, was 4,888. A megabyte is generous for both and still small enough that a
     * receiver answering with something else cannot cost this JVM its heap.
     */
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final HttpClient client;

    public SubmittedDigestRecovery() {
        this(AdapterHttpClient.shared());
    }

    /** For tests: an injected client, so the rules can be checked without a receiver. */
    public SubmittedDigestRecovery(HttpClient client) {
        this.client = client;
    }

    /**
     * What was recovered, or why nothing was.
     *
     * @param sha256Hex lowercase hex, or null when unavailable
     * @param source a short human-readable note of where it came from — this ends up in the
     *        refusal message when the comparison fails, and "which of the two things did we
     *        fetch" is the first question anyone asks
     * @param unavailable non-null when nothing was recovered
     */
    public record Recovered(String sha256Hex, String source, String unavailable) {

        public boolean present() {
            return sha256Hex != null;
        }

        static Recovered of(String sha256Hex, String source) {
            return new Recovered(sha256Hex, source, null);
        }

        static Recovered unavailable(String why) {
            return new Recovered(null, null, why);
        }
    }

    /**
     * RODA: fetch the transferred resource the receiver still holds and hash it here.
     *
     * @param baseUrl the RODA base, e.g. {@code http://localhost:18080}
     * @param transferredResourceUuid the uuid returned when the package was deposited
     * @param authorization an {@code Authorization} header value, or null
     */
    public Recovered fromRodaTransfer(String baseUrl, String transferredResourceUuid,
            String authorization) {
        String url = trimTrailingSlash(baseUrl) + "/api/v2/transfers/"
                + AdapterHttpClient.encodePathSegment(transferredResourceUuid) + "/download";
        return hashOf(url, authorization,
                "the bytes RODA still holds for transferred resource " + transferredResourceUuid);
    }

    /**
     * Archivematica: pull the shipped bag's manifest line back out of the AIP.
     *
     * <p>The relative path is a parameter rather than something built here. It has now been read
     * off a live AIP (design §17), and the shape is:
     *
     * <pre>
     * {name}-{AIP uuid}/data/objects/metadata/transfers/{name}-{TRANSFER uuid}/manifest-sha256.txt
     * </pre>
     *
     * <p><b>The two uuids are different.</b> Building this from the AIP uuid alone — the one the
     * receipt is about, and the only one to hand — gives a path that 404s. That is why it stays a
     * parameter: one observation of a two-uuid path is not enough to make a constant out of, and
     * a constant that is wrong reads as a rule to whoever believes it.
     *
     * <p><b>There is a decoy, and it is the easier path to reach.</b> The AIP root also holds a
     * {@code manifest-sha256.txt} — Archivematica's own, describing Archivematica's AIP. It is a
     * perfectly valid BagIt manifest, so nothing about parsing it fails; it simply answers a
     * different question. Passing it here is refused, but for a reason that has nothing to do
     * with where it came from: the payload-name match below finds no line for our package. Design
     * §17 shot that as a negative control against a live AIP.
     *
     * <p><b>What comes back is the digest of the bag's PAYLOAD — the SIP zip — not of the bag.</b>
     * A manifest describes what it covers; it does not describe itself. So a transfer whose
     * {@code sipDigest} is the BAG's digest will never match here, while RODA (which returns the
     * submitted bytes, whatever they were) would match either way. Design §13.2.
     *
     * @param payloadName the name the payload has inside the bag, i.e. the line to look for
     */
    public Recovered fromArchivematicaManifest(String storageServiceBaseUrl, String aipUuid,
            String relativePathToManifest, String payloadName, String authorization) {
        if (relativePathToManifest == null || relativePathToManifest.isBlank()) {
            // Not an NPE out of URLEncoder. This argument is deliberately caller-supplied
            // because the path's middle segment was never confirmed (see above), so an absent
            // one is the realistic FIRST input -- and this class's contract is to return a
            // reason rather than throw.
            return Recovered.unavailable("no path inside the AIP was given, so there is nowhere "
                    + "to look for the manifest this transfer shipped");
        }
        String url = trimTrailingSlash(storageServiceBaseUrl) + "/api/v2/file/"
                + AdapterHttpClient.encodePathSegment(aipUuid) + "/extract_file/"
                + "?relative_path_to_file="
                + java.net.URLEncoder.encode(relativePathToManifest, StandardCharsets.UTF_8);
        Fetched fetched = bodyOf(url, authorization);
        if (fetched.body() == null) {
            // Say WHICH failure. "Not found" and "403 from the API allowlist" and "the storage
            // service is down" are three different jobs for whoever reads this, and collapsing
            // them into one sentence about zipfile sends all three to the wrong place.
            String hint = fetched.status() == 404
                    ? " A 404 here is what a package sent as zipfile looks like: it ingests, but"
                            + " leaves no manifest behind, so there is nothing to recover."
                    : "";
            return Recovered.unavailable("the manifest could not be read back from AIP "
                    + aipUuid + " at " + relativePathToManifest + " (" + fetched.why() + ")."
                    + hint);
        }
        if (payloadName == null || payloadName.isBlank()) {
            // Before the scan, not inside it. equals(null) is false for every line, so an
            // unasked question came out as the receiver's answer: "the manifest has no line for
            // null, so it does not describe the package this transfer sent" — a finding about
            // the far end, delivered after a real GET, that reads like the receiver ingested
            // something else. The caller simply passed nothing.
            //
            // The sibling argument is already guarded this way ("no path inside the AIP was
            // given, so there is nowhere to look"), and ReceivingSystem states the rule: a blank
            // argument says the CALLER passed nothing, not that the receiver said nothing. Of
            // the three arguments, this was the arm that did not get it.
            return Recovered.unavailable("no payload name was given, so there is no line to look "
                    + "for in the manifest recovered from AIP " + aipUuid + ". Nothing is "
                    + "established about what the receiver holds");
        }
        String manifest = fetched.body();
        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // BagIt manifest lines are "<digest><whitespace><path>". Compare the LAST PATH
            // SEGMENT for equality, not a suffix: "data/evil-nemaki-sip.zip" ends with
            // "nemaki-sip.zip", and taking that line would attest a different file that happens
            // to sit in the same bag.
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2 && lastSegment(parts[1]).equals(payloadName)) {
                String candidate = parts[0].toLowerCase(Locale.ROOT);
                if (!SHA256_HEX.matcher(candidate).matches()) {
                    // The path into the AIP is supplied by the caller (design §14), so pointing
                    // at manifest-md5.txt is an ordinary slip rather than an attack. Unchecked,
                    // a 32-digit MD5 came back AS a sha256Hex and lost the comparison
                    // downstream, where the refusal blames the receiver for holding a different
                    // package.
                    return Recovered.unavailable("the line for " + payloadName + " in "
                            + relativePathToManifest + " inside AIP " + aipUuid + " does not "
                            + "carry a SHA-256 (" + candidate.length() + " hex digits, not 64), "
                            + "so that file is not a SHA-256 manifest. Nothing is established "
                            + "about what the receiver holds");
                }
                // Names the file actually read. It used to say "manifest-sha256" whatever the
                // caller pointed at, so a refusal quoted a file nobody had opened.
                return Recovered.of(candidate, "the " + lastSegment(relativePathToManifest)
                        + " line Archivematica kept inside AIP " + aipUuid);
            }
        }
        return Recovered.unavailable("the manifest recovered from AIP " + aipUuid
                + " has no line for " + payloadName + ", so it does not describe the package "
                + "this transfer sent");
    }

    private Recovered hashOf(String url, String authorization, String source) {
        try {
            AdapterHttpClient.validateExternalUrl(url);
        } catch (RuntimeException e) {
            return Recovered.unavailable("the receiver's download URL was refused: "
                    + e.getMessage());
        }
        HttpRequest request = withAuth(HttpRequest.newBuilder(URI.create(url)).GET()
                .timeout(TIMEOUT), authorization).build();
        try {
            HttpResponse<InputStream> response = AdapterHttpClient.sendWithRetry(client, request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                return Recovered.unavailable("the receiver answered " + response.statusCode()
                        + " for " + url);
            }
            try (InputStream in = response.body(); BodyBudget budget = new BodyBudget(in, bodyBudget)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                try {
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) {
                            return Recovered.unavailable("the receiver's response exceeded "
                                    + MAX_BYTES + " bytes, which is not a package this product "
                                    + "sent");
                        }
                        digest.update(buffer, 0, read);
                    }
                } catch (IOException e) {
                    // Asked here rather than at the outer catch: from there the two are the same
                    // IOException, and "the receiver could not be read" sends an operator to the
                    // network for a receiver that answered and then went quiet.
                    if (budget.fired()) {
                        // "Did not finish", not "stopped": the watchdog bounds TOTAL time
                        // from the first byte, so a receiver that streams slowly but steadily
                        // trips it too, and calling that a stall would send an operator
                        // looking for a hang that never happened.
                        return Recovered.unavailable("the receiver started answering for " + url
                                + " but the body did not finish within " + bodyBudget
                                + ", so the read was abandoned. Nothing is established "
                                + "about what it holds");
                    }
                    throw e;
                }
                return Recovered.of(HexFormat.of().formatHex(digest.digest()), source);
            }
        } catch (NoSuchAlgorithmException e) {
            // Not folded into the generic failure: a JVM without SHA-256 is a deployment problem
            // with a specific fix, and "could not reach the receiver" sends the reader to the
            // network.
            return Recovered.unavailable("this JVM does not provide SHA-256 (" + e.getMessage()
                    + "), so nothing could be hashed");
        } catch (IOException e) {
            return Recovered.unavailable("the receiver could not be read: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Recovered.unavailable("the read was interrupted");
        } catch (RuntimeException e) {
            // sendWithRetry re-validates and IP-pins at send time, and throws SecurityException
            // when the host now resolves somewhere blocked. This class's contract is to RETURN a
            // reason rather than throw -- an unreachable receiver is a finding about the
            // handover, and assemble() has no catch of its own.
            return Recovered.unavailable("the receiver could not be reached: " + e.getMessage());
        }
    }

    /** A body, or the reason there is none — with the status, because the status is the triage. */
    private record Fetched(String body, int status, String why) {
        static Fetched ok(String body) {
            return new Fetched(body, 200, "200");
        }

        static Fetched failed(int status, String why) {
            return new Fetched(null, status, why);
        }
    }

    private Fetched bodyOf(String url, String authorization) {
        try {
            AdapterHttpClient.validateExternalUrl(url);
        } catch (RuntimeException e) {
            logger.warn("The receiver's extract URL was refused: {}", e.getMessage());
            return Fetched.failed(0, "the URL was refused: " + e.getMessage());
        }
        HttpRequest request = withAuth(HttpRequest.newBuilder(URI.create(url)).GET()
                .timeout(TIMEOUT), authorization).build();
        try {
            // Streamed and bounded, NOT ofString(). The SSRF guard says which host may be
            // reached; it says nothing about how much that host sends. A manifest is a few
            // hundred bytes, so a response that will not fit in MAX_MANIFEST_BYTES is not one,
            // and buffering it whole to find that out hands a misconfigured or hostile receiver
            // this JVM's heap. The RODA path was streamed against MAX_BYTES from the start and
            // this one was not — same external read, two different answers to the same question.
            HttpResponse<java.io.InputStream> response = AdapterHttpClient.sendWithRetry(client,
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                logger.info("The receiver answered {} for {}", response.statusCode(), url);
                // Closed even though nothing is read from it. ofString() consumed the body for
                // us; ofInputStream() does not, and an unconsumed stream pins its connection —
                // so switching handlers turned every 403/404 into a leaked connection. The 404
                // is not exotic here: this method treats it as the expected "sent as zipfile"
                // case. hashOf() and AdapterHttpClient.sendWithRetry both already do this.
                closeQuietly(response.body());
                return Fetched.failed(response.statusCode(),
                        "the receiver answered " + response.statusCode());
            }
            java.io.ByteArrayOutputStream buffered = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream body = response.body();
                    BodyBudget budget = new BodyBudget(body, bodyBudget)) {
                byte[] chunk = new byte[8192];
                int read;
                try {
                    while ((read = body.read(chunk)) != -1) {
                        if (buffered.size() + read > MAX_MANIFEST_BYTES) {
                            logger.warn("The receiver's manifest at {} exceeded {} bytes", url,
                                    MAX_MANIFEST_BYTES);
                            return Fetched.failed(0, "the receiver sent more than "
                                    + MAX_MANIFEST_BYTES + " bytes for what should be a "
                                    + "manifest, so it was not read");
                        }
                        buffered.write(chunk, 0, read);
                    }
                } catch (IOException e) {
                    if (budget.fired()) {
                        return Fetched.failed(0, "the receiver started answering but the "
                                + "manifest did not finish arriving within " + bodyBudget
                                + ", so it was not read");
                    }
                    throw e;
                }
            }
            return Fetched.ok(buffered.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("The receiver could not be read: {}", e.getMessage());
            return Fetched.failed(0, "the receiver could not be read: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fetched.failed(0, "the read was interrupted");
        } catch (RuntimeException e) {
            // As in hashOf: send-time re-validation throws, and this class returns reasons.
            return Fetched.failed(0, "the receiver could not be reached: " + e.getMessage());
        }
    }

    /**
     * Closes a stalled body read after {@link #BODY_BUDGET}, so a receiver cannot park a thread.
     *
     * <p>A daemon thread per read. Not free, but this path runs once per custody transfer rather
     * than once per request, and the alternative is a thread parked for ever.
     *
     * <p>Closing is the lever, not a clock check: a receiver that stalls blocks INSIDE
     * {@code read()}, so a loop testing a deadline at the top of each iteration never reaches
     * the test. Closing the response stream makes the blocked read throw.
     */
    static final class BodyBudget implements AutoCloseable {

        private final Thread watchdog;
        private final java.util.concurrent.atomic.AtomicBoolean fired =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        BodyBudget(java.io.InputStream body) {
            this(body, BODY_BUDGET);
        }

        /** Visible for tests, which cannot wait out {@link #BODY_BUDGET}. */
        BodyBudget(java.io.InputStream body, Duration budget) {
            this.watchdog = new Thread(() -> {
                try {
                    Thread.sleep(budget.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                fired.set(true);
                closeQuietly(body);
            }, "custody-body-budget");
            this.watchdog.setDaemon(true);
            this.watchdog.start();
        }

        /**
         * Whether the budget ran out.
         *
         * <p>Asked AFTER the read fails, because what the reader sees is an ordinary
         * {@code IOException} from a stream someone else closed — indistinguishable, at the
         * catch site, from the receiver dropping the connection. Reporting the wrong one sends
         * an operator to the network for a problem that is a stalled receiver, or the reverse.
         */
        boolean fired() {
            return fired.get();
        }

        @Override
        public void close() {
            watchdog.interrupt();
        }
    }

    /** The part after the last {@code /}, or the whole string when there is none. */
    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static HttpRequest.Builder withAuth(HttpRequest.Builder builder, String authorization) {
        return authorization == null || authorization.isBlank()
                ? builder
                : builder.header("Authorization", authorization);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the interesting failure is the status code above.
        }
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }
}
