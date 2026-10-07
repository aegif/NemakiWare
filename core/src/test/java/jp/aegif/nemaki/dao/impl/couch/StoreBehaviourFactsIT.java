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

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Attachment;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.GetAttachmentOptions;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.IndexDefinition;
import com.ibm.cloud.cloudant.v1.model.IndexField;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.PostIndexOptions;
import com.ibm.cloud.cloudant.v1.model.PutAttachmentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jp.aegif.nemaki.dao.impl.couch.StoreBehaviourFacts.Fact;
import jp.aegif.nemaki.dao.impl.couch.StoreBehaviourFacts.Line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures {@link StoreBehaviourFacts} against the LIVE CouchDB it is pointed at (R7, plan A-7).
 *
 * <h2>Why this is an IT and not a unit test</h2>
 *
 * <p>Every fact here is a property of CouchDB and of the SDK's wire handling. A mock would answer
 * whatever the author expected, which is precisely the state R7 recorded: the premise was written
 * down and never asked of a store.
 *
 * <h2>Why CI runs it more than once</h2>
 *
 * <p>The product's floor accepts every line in {@link StoreBehaviourFacts#SUPPORTED_LINES}, so one
 * run on one version measures one of them. The workflow runs this job once per supported line
 * against a bare {@code couchdb:&lt;tag&gt;} container — no NemakiWare, no Setup Wizard, because
 * nothing here needs the product to be running. {@code EverySupportedCouchDbIsMeasuredTest} keeps
 * the matrix and the table in step in the ordinary unit suite.
 *
 * <h2>It does not skip quietly when it matters</h2>
 *
 * <p>Unreachable store → skipped locally, HARD FAILURE under
 * {@code -Dnemaki.test.couchdb.required=true} (the same gate the other ITs use). A store whose
 * version the table does not declare is a FAILURE either way: that is a question nobody asked, and
 * reporting it as "no problems" is the defect this branch exists to remove.
 *
 * <pre>mvn -o test -Dtest=StoreBehaviourFactsIT -f core/pom.xml -Pdevelopment
 *   -Dnemaki.test.couchdb.url=http://localhost:15984
 *   -Dnemaki.test.couchdb.user=admin -Dnemaki.test.couchdb.password=password</pre>
 */
public class StoreBehaviourFactsIT {

    private static final String ATTACHMENT_NAME = "content";
    private static final byte[] BINARY = "the binary this document is for".getBytes(StandardCharsets.UTF_8);

    private static Cloudant cloudant;
    private static String db;
    private static boolean available;
    private static String reportedVersion;

    /** What the store actually did, filled in by {@link #measure()}. */
    private static Map<Fact, Boolean> observed;
    /** How each observation was arrived at, quoted in every failure message. */
    private static Map<Fact, String> howObserved;

    private static String cfg(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @BeforeAll
    static void connectAndMeasure() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        db = "nemaki_store_facts_" + UUID.randomUUID().toString().replace("-", "");
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            reportedVersion = cloudant.getServerInformation().execute().getResult().getVersion();
            cloudant.putDatabase(new PutDatabaseOptions.Builder().db(db).build()).execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available) {
            if (Boolean.parseBoolean(cfg("nemaki.test.couchdb.required",
                    "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
                throw new IllegalStateException("nemaki.test.couchdb.required=true but no CouchDB "
                        + "answered at " + url + " — the store-behaviour facts cannot be measured, "
                        + "and an unmeasured premise must not pass as a measured one (R7)");
            }
            return;
        }
        measure();
    }

    @AfterAll
    static void dropDatabase() {
        if (!available) {
            return;
        }
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(db).build()).execute();
        } catch (Exception ignored) {
            // A leftover throwaway database is noise, not a finding.
        }
    }

    // ── the measurements ───────────────────────────────────────────

    private static void measure() {
        observed = new LinkedHashMap<>();
        howObserved = new LinkedHashMap<>();

        String marker = "facts-" + UUID.randomUUID();
        String keptId = "kept-" + UUID.randomUUID();
        String strippedId = "stripped-" + UUID.randomUUID();
        String keptRev = documentWithAnAttachment(keptId, marker);
        String strippedRev = documentWithAnAttachment(strippedId, marker);

        measureFindRowStubs(marker);
        Document fetched = measurePlainGetStubs(keptId);
        measurePutBackKeepsTheBinary(keptId, fetched);
        measurePutWithoutAttachmentsDeletesIt(strippedId, strippedRev, marker);
        measureMissingPinnedIndex(marker);
        measureAllowFallback(marker);

        // keptRev is the rev before the attachment; kept only so the setup reads as a pair.
        if (keptRev == null) {
            throw new IllegalStateException("could not seed " + keptId);
        }
    }

    /** PUT a document, then PUT a binary onto it. Returns the rev BEFORE the attachment. */
    private static String documentWithAnAttachment(String docId, String marker) {
        Document doc = new Document();
        doc.setId(docId);
        doc.put("marker", marker);
        DocumentResult created = cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(db).docId(docId).document(doc).build()).execute().getResult();
        cloudant.putAttachment(new PutAttachmentOptions.Builder()
                .db(db).docId(docId).attachmentName(ATTACHMENT_NAME)
                .contentType("application/octet-stream")
                .attachment(new ByteArrayInputStream(BINARY))
                .rev(created.getRev()).build()).execute().getResult();
        return created.getRev();
    }

    private static void measureFindRowStubs(String marker) {
        FindResult r = cloudant.postFind(new PostFindOptions.Builder()
                .db(db).selector(Map.of("marker", Map.of("$eq", marker))).limit(10).build())
                .execute().getResult();
        List<Document> docs = r.getDocs();
        if (docs == null || docs.isEmpty()) {
            throw new IllegalStateException("the seeded documents are not findable — the "
                    + "measurement cannot proceed, and an empty answer is not evidence about stubs");
        }
        Map<String, Attachment> attachments = docs.get(0).getAttachments();
        boolean carries = attachments != null && !attachments.isEmpty();
        observed.put(Fact.FIND_ROW_CARRIES_ATTACHMENT_STUBS, carries);
        howObserved.put(Fact.FIND_ROW_CARRIES_ATTACHMENT_STUBS,
                "_find returned " + docs.size() + " row(s); the first row's _attachments is "
                        + describe(attachments));
    }

    private static Document measurePlainGetStubs(String docId) {
        Document fetched = cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(db).docId(docId).build()).execute().getResult();
        Map<String, Attachment> attachments = fetched.getAttachments();
        boolean carries = attachments != null && !attachments.isEmpty();
        observed.put(Fact.PLAIN_GET_CARRIES_ATTACHMENT_STUBS, carries);
        howObserved.put(Fact.PLAIN_GET_CARRIES_ATTACHMENT_STUBS,
                "a plain GET of " + docId + " (no attachments=true) returned _attachments "
                        + describe(attachments));
        return fetched;
    }

    /** The finalizer's exact move: mutate the fetched object in place and PUT it back. */
    private static void measurePutBackKeepsTheBinary(String docId, Document fetched) {
        Map<String, Object> props = fetched.getProperties();
        props.put("touchedByTheFinalizer", Boolean.TRUE);
        fetched.setProperties(props);
        String outcome;
        boolean kept;
        try {
            cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(db).docId(docId).document(fetched).build()).execute().getResult();
            BinaryRead after = readAttachment(docId);
            kept = after.bytes() != null && java.util.Arrays.equals(BINARY, after.bytes());
            outcome = "after the PUT, " + after.how() + " (seeded " + BINARY.length + ")";
        } catch (RuntimeException e) {
            kept = false;
            outcome = "the PUT itself failed: " + e;
        }
        observed.put(Fact.PUT_BACK_OF_A_PLAIN_GET_KEEPS_THE_BINARY, kept);
        howObserved.put(Fact.PUT_BACK_OF_A_PLAIN_GET_KEEPS_THE_BINARY, outcome);
    }

    /** The hazard: a body built from a POJO, carrying no {@code _attachments} at all. */
    private static void measurePutWithoutAttachmentsDeletesIt(String docId, String seedRev,
            String marker) {
        String currentRev = cloudant.getDocument(new GetDocumentOptions.Builder()
                .db(db).docId(docId).build()).execute().getResult().getRev();
        Document stripped = new Document();
        stripped.setId(docId);
        stripped.setRev(currentRev);
        stripped.put("marker", marker);
        cloudant.putDocument(new PutDocumentOptions.Builder()
                .db(db).docId(docId).document(stripped).build()).execute().getResult();
        BinaryRead after = readAttachment(docId);
        // gone(), not "bytes == null". Only a 404 is the store telling us the binary went.
        observed.put(Fact.PUT_WITHOUT_ATTACHMENTS_DELETES_THE_BINARY, after.gone());
        howObserved.put(Fact.PUT_WITHOUT_ATTACHMENTS_DELETES_THE_BINARY,
                "after a PUT with no _attachments (seed rev " + seedRev + ", written at "
                        + currentRev + "): " + after.how());
    }

    /**
     * A pinned index that does not exist. The fact is the pair: the store answers rather than
     * erroring, AND the epoch scan's own guard recognises the warning it answers with.
     */
    private static void measureMissingPinnedIndex(String marker) {
        boolean answered;
        String warning = null;
        FindResult r = null;
        try {
            r = cloudant.postFind(new PostFindOptions.Builder()
                    .db(db).selector(Map.of("marker", Map.of("$eq", marker))).limit(10)
                    .useIndex(List.of("_design/no-such-ddoc", "no-such-index")).build())
                    .execute().getResult();
            answered = true;
            warning = r.getWarning();
        } catch (RuntimeException e) {
            answered = false;
            warning = "the query was REFUSED: " + e;
        }
        boolean guardRefuses = answered && guardRefuses(r);
        observed.put(Fact.A_MISSING_PINNED_INDEX_FALLS_BACK_SILENTLY, answered && guardRefuses);
        howObserved.put(Fact.A_MISSING_PINNED_INDEX_FALLS_BACK_SILENTLY,
                (answered ? "the store ANSWERED (no error) with warning [" + warning + "]"
                        : warning)
                        + "; AclEpochFinalizationService#requireIndexServed "
                        + (guardRefuses ? "refuses it" : "lets it through"));
    }

    /**
     * Ask the product's own guard, rather than re-stating its phrases here. A second copy of the
     * phrase list would pass while the real matcher no longer matched anything.
     */
    private static boolean guardRefuses(FindResult r) {
        try {
            java.lang.reflect.Method guard = jp.aegif.nemaki.epoch.AclEpochFinalizationService.class
                    .getDeclaredMethod("requireIndexServed", FindResult.class, String.class, List.class);
            guard.setAccessible(true);
            guard.invoke(null, r, db, List.of("_design/no-such-ddoc", "no-such-index"));
            return false;
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            return thrown.getCause() instanceof IllegalStateException;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("requireIndexServed is not where this test expects it; "
                    + "the guard cannot be asked, and a guard that cannot be asked has not been "
                    + "measured", e);
        }
    }

    /**
     * {@code allow_fallback=false} is sent WITH a missing {@code use_index} on purpose.
     *
     * <p>That is the only shape that tells the three outcomes apart. The same query without the
     * parameter is a 200 with a fallback warning on every supported line (the fact above), so:
     * {@code invalid_key} means the store does not know the parameter; a refusal naming the
     * INDEX means it knew it and acted on it; and a 200 means it accepted the key and did
     * nothing, which is neither and must not be reported as either.
     *
     * <p>The classification is {@link StoreBehaviourFacts#classifyAllowFallbackRefusal}, which
     * reads the CouchDB ERROR CODE out of the SDK's debugging info and matches it exactly. Two
     * earlier versions of this javadoc described free-text rules — "any 400 that is not
     * invalid_key", then "any 400 mentioning index" — and both were wrong in both directions.
     * A maintainer following either sentence back into the code would reintroduce the fail-open
     * it describes.
     */
    private static void measureAllowFallback(String marker) {
        String outcome;
        boolean rejectedAsUnknown;
        boolean stoppedTheFallback;
        try {
            cloudant.postFind(new PostFindOptions.Builder()
                    .db(db).selector(Map.of("marker", Map.of("$eq", marker))).limit(10)
                    .useIndex(List.of("_design/no-such-ddoc", "no-such-index"))
                    .allowFallback(false).build()).execute().getResult();
            rejectedAsUnknown = false;
            stoppedTheFallback = false;
            outcome = "the store ANSWERED 200 — allow_fallback=false was neither rejected nor "
                    + "honoured (it full-scanned anyway, or the parameter was never sent)";
        } catch (RuntimeException thrown) {
            StoreBehaviourFacts.FallbackVerdict verdict =
                    StoreBehaviourFacts.classifyAllowFallbackRefusal(thrown);
            rejectedAsUnknown = verdict == StoreBehaviourFacts.FallbackVerdict.REJECTED_AS_UNKNOWN_KEY;
            stoppedTheFallback = verdict == StoreBehaviourFacts.FallbackVerdict.HONOURED;
            outcome = "the query came back " + verdict + ": " + thrown.getMessage();
        }
        observed.put(Fact.ALLOW_FALLBACK_FALSE_IS_REJECTED_AS_AN_UNKNOWN_KEY, rejectedAsUnknown);
        howObserved.put(Fact.ALLOW_FALLBACK_FALSE_IS_REJECTED_AS_AN_UNKNOWN_KEY, outcome);
        observed.put(Fact.ALLOW_FALLBACK_FALSE_STOPS_THE_FALLBACK, stoppedTheFallback);
        howObserved.put(Fact.ALLOW_FALLBACK_FALSE_STOPS_THE_FALLBACK, outcome);
    }

    /**
     * What the store said about a binary.
     *
     * @param bytes the content, when it was read
     * @param gone true ONLY where the store answered 404
     * @param how the observation, quoted in failure messages
     */
    private record BinaryRead(byte[] bytes, boolean gone, String how) {}

    /**
     * Read the attachment, keeping "the store says it is not there" apart from "the read failed".
     *
     * <p>This method used to answer {@code null} for both, and the deletion fact below read that
     * {@code null} as "the binary is gone" — a failed read reported as an answered nothing, in
     * the test written to catch exactly that (Codex review, P1). A read that did not happen now
     * leaves the fact FALSE, so it fails against its expectation and prints why.
     */
    private static BinaryRead readAttachment(String docId) {
        try (InputStream in = cloudant.getAttachment(new GetAttachmentOptions.Builder()
                .db(db).docId(docId).attachmentName(ATTACHMENT_NAME).build())
                .execute().getResult()) {
            if (in == null) {
                return new BinaryRead(null, false, "the SDK returned no stream and did not fail — "
                        + "neither an answer nor a refusal, so nothing is established");
            }
            byte[] bytes = in.readAllBytes();
            return new BinaryRead(bytes, false, bytes.length + " bytes");
        } catch (Exception thrown) {
            boolean gone = StoreBehaviourFacts.readEstablishesTheBinaryIsGone(thrown);
            return new BinaryRead(null, gone, gone
                    ? "the store ANSWERED 404: there is no attachment"
                    : "the attachment read FAILED (" + thrown + "), which says nothing about "
                            + "whether the binary is there");
        }
    }

    private static String describe(Map<String, Attachment> attachments) {
        if (attachments == null) {
            return "null";
        }
        if (attachments.isEmpty()) {
            return "an empty map";
        }
        return attachments.keySet().toString();
    }

    // ── the assertions ─────────────────────────────────────────────

    @Test
    @DisplayName("the running store is a version the table declares — never skipped past")
    void theRunningVersionIsDeclared() {
        assumeTrue(available, "no CouchDB reachable — skipping (CI sets required=true)");

        Line line = StoreBehaviourFacts.lineOf(reportedVersion);

        assertEquals(StoreBehaviourFacts.Fact.values().length,
                StoreBehaviourFacts.expectedOn(line).size(),
                "CouchDB " + reportedVersion + " (line " + line.key() + ") is missing an "
                        + "expectation for at least one fact");
    }

    @Test
    @DisplayName("every premise the code relies on is what the store actually does")
    void theStoreDoesWhatTheCodeAssumes() {
        assumeTrue(available, "no CouchDB reachable — skipping (CI sets required=true)");

        Line line = StoreBehaviourFacts.lineOf(reportedVersion);
        List<String> wrong = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            boolean expected = StoreBehaviourFacts.expect(line, fact);
            Boolean actual = observed.get(fact);
            if (actual == null) {
                wrong.add(fact + ": NOT MEASURED — the measurement did not run, which is not the "
                        + "same as the premise holding");
                continue;
            }
            if (actual != expected) {
                wrong.add(fact + ": the code assumes " + expected + " (" + fact.premise() + ", "
                        + fact.where() + ") but CouchDB " + reportedVersion + " gave " + actual
                        + " — " + howObserved.get(fact));
            }
        }

        assertTrue(wrong.isEmpty(), "CouchDB " + reportedVersion + " does not behave the way the "
                + "product's code says it does. Each line below is a defect in the code or in the "
                + "javadoc that states the premise — NOT a row to be edited to match the store:\n  "
                + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("the _find stub fact holds on the WIRE, not only through the SDK")
    void theFindStubFactIsNotAnSdkArtefact() {
        // R7 is a claim about CouchDB. Measuring it only through the Cloudant SDK would leave
        // open that the SDK synthesises `_attachments` from somewhere — and the canon said the
        // fact had been checked both ways while the tree only ever checked one (subagent review,
        // P3). This asks the HTTP API directly, with the SDK out of the path.
        assumeTrue(available, "no CouchDB reachable — skipping (CI sets required=true)");

        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL",
                "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD",
                "password");
        String auth = java.util.Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));

        String body = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url + "/" + db + "/_find"))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    // ofString, not ofInputStream: the request timeout covers the headers only,
                    // and a body handler that streams would let this block without bound.
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"selector\":{\"_id\":{\"$gt\":null}},\"limit\":50}"))
                    .build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        }, "the raw _find call did not complete, so it measured nothing");

        boolean expected = StoreBehaviourFacts.expect(
                StoreBehaviourFacts.lineOf(reportedVersion), Fact.FIND_ROW_CARRIES_ATTACHMENT_STUBS);
        assertEquals(expected, body.contains("\"_attachments\""),
                "the wire and the declared fact disagree about whether a _find row carries "
                        + "_attachments stubs. Raw response: " + body);
        if (expected) {
            assertTrue(body.contains("\"stub\":true"),
                    "the row carries an _attachments block that is not a stub — the shape the "
                            + "DLQ's carry-forward copies is not there: " + body);
        }
    }

    @Test
    @DisplayName("an index that DOES serve the query is not reported as a fallback")
    void theGuardDoesNotRefuseAnIndexServedQuery() {
        // The over-throw side. A guard that refused every query would satisfy the fallback fact
        // above and make the epoch scan impossible to run — and nothing else here would notice.
        assumeTrue(available, "no CouchDB reachable — skipping (CI sets required=true)");

        String ddoc = "facts-idx";
        String name = "by-marker";
        cloudant.postIndex(new PostIndexOptions.Builder()
                .db(db)
                .index(new IndexDefinition.Builder()
                        .fields(List.of(new IndexField.Builder().add("marker", "asc").build()))
                        .build())
                .name(name).ddoc(ddoc).type(PostIndexOptions.Type.JSON).build())
                .execute().getResult();

        FindResult served = cloudant.postFind(new PostFindOptions.Builder()
                .db(db).selector(Map.of("marker", Map.of("$gt", ""))).limit(10)
                .useIndex(List.of("_design/" + ddoc, name)).build()).execute().getResult();

        assertTrue(servedQueryPasses(served, ddoc, name),
                "the epoch scan's guard refused a query its pinned index DID serve (warning: "
                        + served.getWarning() + ") — over-refusal, and the scan could never run");
    }

    private static boolean servedQueryPasses(FindResult r, String ddoc, String name) {
        try {
            java.lang.reflect.Method guard = jp.aegif.nemaki.epoch.AclEpochFinalizationService.class
                    .getDeclaredMethod("requireIndexServed", FindResult.class, String.class, List.class);
            guard.setAccessible(true);
            guard.invoke(null, r, db, List.of("_design/" + ddoc, name));
            return true;
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("requireIndexServed is not where this test expects it", e);
        }
    }
}
