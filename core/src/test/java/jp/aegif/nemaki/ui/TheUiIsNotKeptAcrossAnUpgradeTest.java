package jp.aegif.nemaki.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A browser that read one version of the UI moves to the next after an upgrade (R348, 3.4.2).
 *
 * <p>Driven through Tomcat's own default servlet and the SPA fallback, wired as the shipped web.xml wires them — the
 * servlets named {@code default} and {@code react-spa-fallback} with their init-params and every mapping, and every
 * filter mapped under {@code /ui}, read from {@code src/main/webapp/WEB-INF/web.xml} — over a document base whose files
 * all carry the WAR's fixed date (1980-02-01). The two versions' {@code index.html} have the same length, as the
 * measured ones did (503 bytes): Tomcat's default weak tag, length and date, is then the same for both, which is the
 * defect; a lock with versions of different lengths would be green with it.
 *
 * <p>R351: the fixed date validates no request for what carries no hash — {@code If-Unmodified-Since} as well as
 * {@code If-Modified-Since}, and a {@code Range} beside either of them, or beside an {@code If-Range} that is not
 * exactly one strong tag, is answered with the whole current content. Those locks read the answer off the socket
 * (the headers sent as written, the body byte for byte). Every request here is a GET.
 */
class TheUiIsNotKeptAcrossAnUpgradeTest {

    /** The date every entry of the WAR carries. */
    private static final long WAR_DATE = Instant.parse("1980-02-01T00:00:00Z").toEpochMilli();

    private static final String V1 = "<!doctype html><script src=\"/core/ui/assets/main-AAAAAAAA.js\"></script>";
    private static final String V2 = "<!doctype html><script src=\"/core/ui/assets/main-BBBBBBBB.js\"></script>";

    /** Everything under /ui that does not carry a hash of its content, each by the path a browser asks. */
    private static final List<String> NOT_HASHED = List.of(
            "/core/ui/", "/core/ui/index.html", "/core/ui/documents/some-folder", "/core/ui/oidc-callback.html",
            "/core/ui/saml-callback.html", "/core/ui/silent-callback.html", "/core/ui/auth-popup.html",
            "/core/ui/auth-popup-entry.js", "/core/ui/pdf-worker/pdf.worker.min.mjs", "/core/ui/logo1.png",
            "/core/ui/help-images/one.png");

    @TempDir
    Path work;

    private Tomcat tomcat;
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    // ---- the locks ----

    @Test
    void anUpgradedIndexOfTheSameLengthIsSentInFullToTheTagsOfTheVersionBefore() throws Exception {
        assertEquals(V1.length(), V2.length(), "the versions must have one length, or the lock does not discriminate");
        deploy("v1", V1);
        String v1Tag = get("/core/ui/", Map.of()).headers().firstValue("ETag").orElse("");
        deploy("v2", V2);
        String weakBefore = "W/\"" + V1.length() + "-" + WAR_DATE + "\"";
        for (String sent : List.of(weakBefore, v1Tag)) {
            for (String path : List.of("/core/ui/", "/core/ui/index.html", "/core/ui/documents/some-folder")) {
                HttpResponse<String> answer = get(path, Map.of("If-None-Match", sent));
                assertEquals(200, answer.statusCode(), path + " with the tag " + sent + " of the version before");
                assertEquals(V2, answer.body(), path + " answers the upgraded index");
                String tag = answer.headers().firstValue("ETag").orElse("");
                assertFalse(tag.startsWith("W/"), path + " sends a strong tag, not " + tag);
                assertNotEquals(sent, tag, path + " sends the upgraded index's own tag");
            }
        }
    }

    @Test
    void theSameIndexIsAnswered304WithItsTagAndIsStillRevalidated() throws Exception {
        deploy("v2", V2);
        String tag = get("/core/ui/", Map.of()).headers().firstValue("ETag").orElse("");
        for (String path : List.of("/core/ui/", "/core/ui/index.html", "/core/ui/documents/some-folder")) {
            HttpResponse<String> answer = get(path, Map.of("If-None-Match", tag));
            assertEquals(304, answer.statusCode(), path + " with its own tag");
            assertEquals(Optional.of(tag), answer.headers().firstValue("ETag"), path + " names its tag in the 304");
            assertEquals(Optional.of(UiCacheControlFilter.REVALIDATE), answer.headers().firstValue("Cache-Control"),
                    path + " stays no-cache in the 304");
        }
    }

    @Test
    void theFixedDateAloneValidatesNothingThatIsNotHashed() throws Exception {
        deploy("v2", V2);
        String after = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(WAR_DATE + 86_400_000L));
        for (String path : NOT_HASHED) {
            HttpResponse<String> answer = get(path, Map.of("If-Modified-Since", after));
            assertEquals(200, answer.statusCode(), path + " with only a date after the WAR's");
        }
    }

    @Test
    void everythingNotHashedIsRevalidatedAndSendsNoDate() throws Exception {
        deploy("v2", V2);
        for (String path : NOT_HASHED) {
            HttpResponse<String> answer = get(path, Map.of());
            assertEquals(200, answer.statusCode(), path);
            assertEquals(Optional.of(UiCacheControlFilter.REVALIDATE), answer.headers().firstValue("Cache-Control"), path);
            assertEquals(Optional.empty(), answer.headers().firstValue("Last-Modified"), path + " sends the fixed date");
            assertFalse(answer.headers().firstValue("ETag").orElse("W/").startsWith("W/"), path + " sends a strong tag");
        }
    }

    @Test
    void aHashedAssetIsImmutableAnsweredOr304() throws Exception {
        deploy("v2", V2);
        HttpResponse<String> answer = get("/core/ui/assets/main-BBBBBBBB.js", Map.of());
        assertEquals(200, answer.statusCode());
        assertEquals(Optional.of(UiCacheControlFilter.IMMUTABLE), answer.headers().firstValue("Cache-Control"));
        String tag = answer.headers().firstValue("ETag").orElse("");
        HttpResponse<String> again = get("/core/ui/assets/main-BBBBBBBB.js", Map.of("If-None-Match", tag));
        assertEquals(304, again.statusCode());
        assertEquals(Optional.of(UiCacheControlFilter.IMMUTABLE), again.headers().firstValue("Cache-Control"));
    }

    @Test
    void aHashedAssetThatIsMissingIsNotKeptForAYear() throws Exception {
        deploy("v2", V2);
        HttpResponse<String> answer = get("/core/ui/assets/main-CCCCCCCC.js", Map.of());
        assertEquals(404, answer.statusCode());
        String cacheControl = answer.headers().firstValue("Cache-Control").orElse("");
        assertFalse(cacheControl.contains("immutable") || cacheControl.contains("max-age"),
                "a missing asset is answered " + cacheControl);
    }

    @Test
    void aDateInIfRangeSendsTheWholeIndexWhileATagStillSendsTheRange() throws Exception {
        deploy("v2", V2);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(WAR_DATE));
        HttpResponse<String> byDate = get("/core/ui/index.html", Map.of("Range", "bytes=0-9", "If-Range", date));
        assertEquals(200, byDate.statusCode(), "a Range validated by the fixed date");
        assertEquals(V2, byDate.body());
        String tag = get("/core/ui/index.html", Map.of()).headers().firstValue("ETag").orElse("");
        HttpResponse<String> byTag = get("/core/ui/index.html", Map.of("Range", "bytes=0-9", "If-Range", tag));
        assertEquals(206, byTag.statusCode(), "a Range validated by the strong tag");
        assertEquals(V2.substring(0, 10), byTag.body());
    }

    // ---- R351: the fixed date validates no request ----

    @Test
    void anIfUnmodifiedSinceAloneIsAnsweredTheWholeCurrentIndex() throws Exception {
        deploy("v2", V2);
        for (String path : INDEX_PATHS) {
            for (String date : List.of(httpDate(WAR_DATE), httpDate(BEFORE_THE_WAR), httpDate(AFTER_THE_WAR))) {
                assertWhole(send(path, "If-Unmodified-Since", date), bytes(V2), path + " with If-Unmodified-Since " + date);
            }
        }
    }

    @Test
    void aRangeWithADateConditionIsAnsweredTheWholeCurrentIndex() throws Exception {
        deploy("v2", V2);
        List<String[]> conditions = List.of(
                new String[] {"If-Modified-Since", httpDate(AFTER_THE_WAR)},
                new String[] {"If-Modified-Since", httpDate(BEFORE_THE_WAR)},
                new String[] {"If-Unmodified-Since", httpDate(WAR_DATE)},
                new String[] {"If-Unmodified-Since", httpDate(AFTER_THE_WAR)},
                new String[] {"If-Range", httpDate(WAR_DATE)},
                new String[] {"If-Range", httpDate(AFTER_THE_WAR)});
        for (String path : INDEX_PATHS) {
            for (String[] condition : conditions) {
                assertWhole(send(path, "Range", "bytes=10-19", condition[0], condition[1]), bytes(V2),
                        path + " with a Range and " + condition[0] + " " + condition[1]);
            }
        }
    }

    @Test
    void aRangeWithAnIfRangeThatIsNotTheCurrentStrongTagIsAnsweredTheWholeIndex() throws Exception {
        deploy("v1", V1);
        String v1Tag = send("/core/ui/index.html").header("ETag").orElse("");
        deploy("v2", V2);
        String v2Tag = send("/core/ui/index.html").header("ETag").orElse("");
        assertTrue(v1Tag.startsWith("\"") && v2Tag.startsWith("\"") && !v1Tag.equals(v2Tag),
                "the two versions carry different strong tags: " + v1Tag + " " + v2Tag);
        List<String> notTheCurrentStrongTag = List.of(
                "W/\"" + V1.length() + "-" + WAR_DATE + "\"", "W/" + v2Tag, v1Tag,
                "garbage", "\"unterminated", "\"a\" \"b\"", "\"\"", v2Tag.substring(0, v2Tag.length() - 1));
        for (String path : INDEX_PATHS) {
            for (String ifRange : notTheCurrentStrongTag) {
                assertWhole(send(path, "Range", "bytes=10-19", "If-Range", ifRange), bytes(V2),
                        path + " with a Range and If-Range " + ifRange);
            }
            assertWhole(send(path, "Range", "bytes=10-19", "If-Range", v2Tag, "If-Range", v2Tag), bytes(V2),
                    path + " with a Range and the current tag in two If-Range");
        }
    }

    @Test
    void aRangeWithTheCurrentStrongTagIsAnsweredThatRange() throws Exception {
        deploy("v2", V2);
        String tag = send("/core/ui/index.html").header("ETag").orElse("");
        for (String path : INDEX_PATHS) {
            for (String[] request : List.of(new String[] {"Range", "bytes=10-19", "If-Range", tag},
                    new String[] {"Range", "bytes=10-19"})) {
                Answer answer = send(path, request);
                assertEquals(206, answer.status(), path + " with " + String.join(" ", request));
                assertEquals(Optional.of("bytes 10-19/" + V2.length()), answer.header("Content-Range"), path);
                assertArrayEquals(bytes(V2.substring(10, 20)), answer.body(), path + " sends the range asked");
            }
        }
    }

    @Test
    void theEntityTagConditionsKeepTheirAnswersBesideADateAndARange() throws Exception {
        deploy("v1", V1);
        String v1Tag = send("/core/ui/index.html").header("ETag").orElse("");
        deploy("v2", V2);
        String v2Tag = send("/core/ui/index.html").header("ETag").orElse("");
        for (String path : INDEX_PATHS) {
            for (String[] date : List.of(new String[] {"If-Unmodified-Since", httpDate(WAR_DATE)},
                    new String[] {"If-Modified-Since", httpDate(AFTER_THE_WAR)})) {
                Answer notModified = send(path, "If-None-Match", v2Tag, date[0], date[1], "Range", "bytes=10-19");
                assertEquals(304, notModified.status(), path + " with the current tag in If-None-Match and " + date[0]);
                assertEquals(Optional.of(v2Tag), notModified.header("ETag"), path + " names its tag in the 304");
                Answer failed = send(path, "If-Match", v1Tag, date[0], date[1], "Range", "bytes=10-19");
                assertEquals(412, failed.status(), path + " with the version before's tag in If-Match and " + date[0]);
            }
            for (String[] request : List.of(new String[] {"If-Match", v2Tag, "Range", "bytes=10-19"},
                    new String[] {"If-Match", v2Tag, "If-Range", v2Tag, "Range", "bytes=10-19"})) {
                Answer ranged = send(path, request);
                assertEquals(206, ranged.status(), path + " with " + String.join(" ", request));
                assertArrayEquals(bytes(V2.substring(10, 20)), ranged.body(), path + " sends the range asked");
            }
        }
    }

    @Test
    void aClientThatKeptTheStartOfTheVersionBeforeCannotJoinItToTheNext() throws Exception {
        assertEquals(V1.length(), V2.length(), "the versions must have one length, or the lock does not discriminate");
        int cut = V1.indexOf("AAAAAAAA") + 4;
        byte[] head = bytes(V1.substring(0, cut));
        byte[] joined = join(head, bytes(V2.substring(cut)));
        assertFalse(Arrays.equals(joined, bytes(V1)) || Arrays.equals(joined, bytes(V2)),
                "the start of one version and the rest of the other must be neither, or the lock does not discriminate");
        deploy("v1", V1);
        Answer before = send("/core/ui/index.html");
        assertArrayEquals(bytes(V1), before.body(), "the client read the version before");
        String v1Tag = before.header("ETag").orElse("");
        deploy("v2", V2);
        List<String[]> resumptions = List.of(
                new String[] {"If-Unmodified-Since", httpDate(WAR_DATE)},
                new String[] {"If-Range", httpDate(WAR_DATE)},
                new String[] {"If-Range", v1Tag},
                new String[] {"If-Range", "W/\"" + V1.length() + "-" + WAR_DATE + "\""},
                new String[] {"If-Modified-Since", httpDate(WAR_DATE)});
        for (String path : INDEX_PATHS) {
            for (String[] resumption : resumptions) {
                Answer rest = send(path, "Range", "bytes=" + cut + "-", resumption[0], resumption[1]);
                byte[] kept = rest.status() == 206 ? join(head, rest.body()) : rest.body();
                String what = path + " resumed after " + cut + " bytes with " + resumption[0] + " " + resumption[1];
                assertFalse(Arrays.equals(joined, kept), what + " joins the start of the version before to the rest of the next");
                assertWhole(rest, bytes(V2), what);
            }
        }
    }

    @Test
    void everyFileThatIsNotHashedIsAnsweredByTheSameRules() throws Exception {
        Map<String, byte[]> shipped = deployEveryFileThatIsNotHashed();
        for (Map.Entry<String, byte[]> file : shipped.entrySet()) {
            String path = "/core/ui/" + file.getKey();
            Answer ranged = send(path, "If-Unmodified-Since", httpDate(WAR_DATE), "Range", "bytes=1-2");
            assertWhole(ranged, file.getValue(), path + " with a Range and If-Unmodified-Since");
            assertEquals(Optional.of(UiCacheControlFilter.REVALIDATE), ranged.header("Cache-Control"), path);
            assertTrue(ranged.header("ETag").orElse("W/").startsWith("\""), path + " sends a strong tag");
            assertWhole(send(path, "If-Modified-Since", httpDate(AFTER_THE_WAR)), file.getValue(),
                    path + " with only a date after the WAR's");
        }
    }

    @Test
    void aHashedAssetKeepsItsRangesItsTagAndItsDate() throws Exception {
        deploy("v2", V2);
        String asset = "/core/ui/assets/main-BBBBBBBB.js";
        Answer whole = send(asset);
        assertEquals(200, whole.status());
        assertEquals(Optional.of(UiCacheControlFilter.IMMUTABLE), whole.header("Cache-Control"));
        assertTrue(whole.header("Last-Modified").isPresent(), "a hashed asset keeps the default servlet's date");
        String tag = whole.header("ETag").orElse("");
        for (String[] request : List.of(new String[] {"Range", "bytes=1-3"},
                new String[] {"Range", "bytes=1-3", "If-Unmodified-Since", httpDate(WAR_DATE)},
                new String[] {"Range", "bytes=1-3", "If-Range", tag})) {
            Answer ranged = send(asset, request);
            assertEquals(206, ranged.status(), asset + " with " + String.join(" ", request));
            assertArrayEquals(Arrays.copyOfRange(whole.body(), 1, 4), ranged.body(), asset + " sends the range asked");
            assertEquals(Optional.of(UiCacheControlFilter.IMMUTABLE), ranged.header("Cache-Control"));
        }
        Answer notModified = send(asset, "If-None-Match", tag);
        assertEquals(304, notModified.status());
        assertEquals(Optional.of(UiCacheControlFilter.IMMUTABLE), notModified.header("Cache-Control"));
    }

    @Test
    void theRulesHoldWhateverTheCaseOrTheNumberOfTheHeaders() throws Exception {
        deploy("v2", V2);
        String tag = send("/core/ui/index.html").header("ETag").orElse("");
        List<String[]> requests = List.of(
                new String[] {"if-unmodified-since", httpDate(BEFORE_THE_WAR)},
                new String[] {"IF-UNMODIFIED-SINCE", httpDate(BEFORE_THE_WAR), "If-Unmodified-Since", httpDate(BEFORE_THE_WAR)},
                new String[] {"RANGE", "bytes=10-19", "IF-MODIFIED-SINCE", httpDate(AFTER_THE_WAR)},
                new String[] {"range", "bytes=10-19", "if-range", httpDate(WAR_DATE)},
                new String[] {"rAnGe", "bytes=10-19", "iF-rAnGe", "W/" + tag},
                new String[] {"Range", "bytes=10-19", "Range", "bytes=20-29", "if-unmodified-since", httpDate(WAR_DATE)},
                new String[] {"Range", "bytes=10-19", "If-Range", tag, "if-range", tag},
                new String[] {"Range", "bytes=10-19", "If-Unmodified-Since", httpDate(WAR_DATE),
                        "If-Unmodified-Since", httpDate(WAR_DATE)});
        for (String path : INDEX_PATHS) {
            for (String[] request : requests) {
                assertWhole(send(path, request), bytes(V2), path + " with " + String.join(" ", request));
            }
        }
    }

    @Test
    void everyWayOfReadingAHeaderSeesTheSameOnesHidden() {
        Map<String, List<String>> sent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sent.put("If-Modified-Since", List.of(httpDate(AFTER_THE_WAR)));
        sent.put("If-Unmodified-Since", List.of(httpDate(WAR_DATE)));
        sent.put("Range", List.of("bytes=10-19"));
        sent.put("If-Range", List.of(httpDate(WAR_DATE)));
        sent.put("If-None-Match", List.of("\"current\""));
        sent.put("If-Match", List.of("\"current\""));
        HttpServletRequest asSent = mock(HttpServletRequest.class);
        when(asSent.getHeader(anyString())).thenAnswer(call -> {
            List<String> values = sent.get(call.<String>getArgument(0));
            return values == null ? null : values.get(0);
        });
        when(asSent.getHeaders(anyString())).thenAnswer(
                call -> Collections.enumeration(sent.getOrDefault(call.<String>getArgument(0), List.of())));
        when(asSent.getDateHeader(anyString())).thenAnswer(call -> sent.containsKey(call.<String>getArgument(0)) ? WAR_DATE : -1L);
        when(asSent.getHeaderNames()).thenAnswer(call -> Collections.enumeration(new ArrayList<>(sent.keySet())));
        HttpServletRequest seen = new UiCacheControlFilter.NoDateValidatorRequest(asSent);
        for (String name : List.of("If-Modified-Since", "if-modified-since", "If-Unmodified-Since", "IF-UNMODIFIED-SINCE",
                "Range", "range", "If-Range", "iF-rAnGe")) {
            assertNull(seen.getHeader(name), name + " is shown by getHeader");
            assertFalse(seen.getHeaders(name).hasMoreElements(), name + " is shown by getHeaders");
            assertEquals(-1L, seen.getDateHeader(name), name + " is shown by getDateHeader");
        }
        List<String> names = Collections.list(seen.getHeaderNames());
        assertEquals(List.of("If-Match", "If-None-Match"), names, "getHeaderNames names what is hidden, or hides what is not");
        for (String name : List.of("If-None-Match", "if-match")) {
            assertEquals("\"current\"", seen.getHeader(name), name + " is hidden by getHeader");
            assertTrue(seen.getHeaders(name).hasMoreElements(), name + " is hidden by getHeaders");
        }
    }

    // ---- the server, as the shipped web.xml wires it ----

    private void deploy(String version, String index) throws Exception {
        stop();
        tomcat = null;
        Path base = work.resolve(version);
        Path ui = base.resolve("ui");
        write(ui.resolve("index.html"), index);
        write(ui.resolve("oidc-callback.html"), index.replace("doctype", "DOCTYPE"));
        write(ui.resolve("saml-callback.html"), index.replace("doctype", "DOCTYPE"));
        write(ui.resolve("silent-callback.html"), "<!doctype html>silent");
        write(ui.resolve("auth-popup.html"), "<!doctype html>popup");
        write(ui.resolve("auth-popup-entry.js"), "console.log('popup " + version + "');");
        write(ui.resolve("pdf-worker/pdf.worker.min.mjs"), "export {}; // " + version);
        write(ui.resolve("logo1.png"), "png " + version);
        write(ui.resolve("help-images/one.png"), "png " + version);
        write(ui.resolve("assets/main-" + (version.equals("v1") ? "AAAAAAAA" : "BBBBBBBB") + ".js"), "main " + version);
        start(base, version);
    }

    private void start(Path base, String label) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(work.resolve("catalina-" + label).toString());
        tomcat.setPort(0);
        tomcat.setSilent(true);
        Context context = tomcat.addContext("/core", base.toString());
        wireAsTheShippedWebXmlDoes(context);
        tomcat.getConnector();
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    private static void write(Path file, String text) throws IOException {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(WAR_DATE));
    }

    private static void wireAsTheShippedWebXmlDoes(Context context) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document webXml = factory.newDocumentBuilder().parse(Path.of("src/main/webapp/WEB-INF/web.xml").toFile());
        Element root = webXml.getDocumentElement();
        List<String> servlets = List.of("default", "react-spa-fallback");
        int wired = 0;
        for (Element servlet : children(root, "servlet")) {
            String name = text(servlet, "servlet-name");
            if (!servlets.contains(name)) {
                continue;
            }
            Wrapper wrapper = Tomcat.addServlet(context, name, text(servlet, "servlet-class"));
            for (Element param : children(servlet, "init-param")) {
                wrapper.addInitParameter(text(param, "param-name"), text(param, "param-value"));
            }
            wired++;
        }
        assertEquals(2, wired, "web.xml declares the default servlet and the SPA fallback");
        for (Element mapping : children(root, "servlet-mapping")) {
            String name = text(mapping, "servlet-name");
            if (servlets.contains(name)) {
                for (Element pattern : children(mapping, "url-pattern")) {
                    context.addServletMappingDecoded(pattern.getTextContent().trim(), name);
                }
            }
        }
        Map<String, String> filterClasses = new LinkedHashMap<>();
        for (Element filter : children(root, "filter")) {
            filterClasses.put(text(filter, "filter-name"), text(filter, "filter-class"));
        }
        for (Element mapping : children(root, "filter-mapping")) {
            String name = text(mapping, "filter-name");
            for (Element pattern : children(mapping, "url-pattern")) {
                String url = pattern.getTextContent().trim();
                if (!url.startsWith("/ui")) {
                    continue;
                }
                if (context.findFilterDef(name) == null) {
                    FilterDef def = new FilterDef();
                    def.setFilterName(name);
                    def.setFilterClass(filterClasses.get(name));
                    context.addFilterDef(def);
                }
                FilterMap map = new FilterMap();
                map.setFilterName(name);
                map.addURLPatternDecoded(url);
                context.addFilterMap(map);
            }
        }
        for (Element welcome : children(root, "welcome-file-list")) {
            for (Element file : children(welcome, "welcome-file")) {
                context.addWelcomeFile(file.getTextContent().trim());
            }
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && tag.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element parent, String tag) {
        List<Element> found = children(parent, tag);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- R351: every file that carries no hash, as the build ships it ----

    /**
     * Every file the build puts under /ui that carries no hash: what {@code public/} holds (copied as it is), the
     * index, the callbacks vite.config.ts generates from it, and the entries it names without a hash.
     */
    private Map<String, byte[]> deployEveryFileThatIsNotHashed() throws Exception {
        Path source = Path.of("src/main/webapp/ui");
        Path pub = source.resolve("public");
        List<String> copied = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pub)) {
            walk.filter(Files::isRegularFile).forEach(file -> copied.add(pub.relativize(file).toString().replace('\\', '/')));
        }
        for (String expected : List.of("auth-popup.html", "silent-callback.html", "pdf-worker/pdf.worker.min.mjs",
                "logo1.png", "help-images/00-login.png")) {
            assertTrue(copied.contains(expected), "public/ no longer holds " + expected + "; the sweep does not read the build");
        }
        assertTrue(copied.stream().noneMatch(name -> name.startsWith("assets/")),
                "public/ puts a file under assets/, where everything is kept for a year");
        String vite = Files.readString(source.resolve("vite.config.ts"));
        List<String> generated = matches(vite, "fileName: '([^'/]+\\.html)'");
        assertEquals(List.of("oidc-callback.html", "saml-callback.html"), generated, "the callbacks vite.config.ts generates");
        List<String> entries = matches(vite, "return '([^'/]+\\.js)';");
        assertEquals(List.of("auth-popup-entry.js"), entries, "the entries vite.config.ts names without a hash");
        stop();
        tomcat = null;
        Path base = work.resolve("shipped");
        Path ui = base.resolve("ui");
        Map<String, byte[]> shipped = new TreeMap<>();
        for (String name : copied) {
            byte[] content = Files.readAllBytes(pub.resolve(name));
            write(ui.resolve(name), content);
            shipped.put(name, content);
        }
        for (String name : concat(List.of("index.html"), concat(generated, entries))) {
            byte[] content = bytes("<!-- " + name + " --> " + V2);
            write(ui.resolve(name), content);
            shipped.put(name, content);
        }
        write(ui.resolve("assets/main-BBBBBBBB.js"), bytes("main shipped"));
        start(base, "shipped");
        return shipped;
    }

    private static List<String> matches(String text, String pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    // ---- R351: what a client gets, byte for byte ----

    /** Before every date the WAR carries (400 days before it): a condition compared to the fixed date fails on it. */
    private static final long BEFORE_THE_WAR = WAR_DATE - 400L * 86_400_000L;

    private static final long AFTER_THE_WAR = WAR_DATE + 86_400_000L;

    private static final List<String> INDEX_PATHS = List.of("/core/ui/", "/core/ui/index.html", "/core/ui/documents/some-folder");

    private static String httpDate(long millis) {
        return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(millis));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /** The whole current content, as a 200 says it: no part, no range, no date. */
    private static void assertWhole(Answer answer, byte[] content, String what) {
        assertEquals(200, answer.status(), what + " is answered in full");
        assertEquals(Optional.empty(), answer.header("Content-Range"), what + " sends a Content-Range");
        assertArrayEquals(content, answer.body(), what + " sends the whole current content, byte for byte");
        assertEquals(Optional.of(String.valueOf(content.length)), answer.header("Content-Length"), what + " says the whole length");
        assertEquals(Optional.empty(), answer.header("Last-Modified"), what + " sends the fixed date");
    }

    /** An answer read off the socket. */
    record Answer(int status, Map<String, List<String>> headers, byte[] body) {
        Optional<String> header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
        }
    }

    /** One GET, the headers sent as written: their case, their order, a name sent more than once. */
    private Answer send(String path, String... nameThenValue) throws IOException {
        StringBuilder head = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n");
        for (int i = 0; i < nameThenValue.length; i += 2) {
            head.append(nameThenValue[i]).append(": ").append(nameThenValue[i + 1]).append("\r\n");
        }
        head.append("\r\n");
        byte[] all;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            all = socket.getInputStream().readAllBytes();
        }
        int end = -1;
        for (int i = 0; i + 3 < all.length; i++) {
            if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IOException("no end of the head in " + all.length + " bytes from " + path);
        }
        String[] lines = new String(all, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.computeIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).trim());
        }
        byte[] body = Arrays.copyOfRange(all, end + 4, all.length);
        if (headers.getOrDefault("transfer-encoding", List.of()).contains("chunked")) {
            body = dechunk(body);
        }
        return new Answer(status, headers, body);
    }

    private static byte[] dechunk(byte[] chunked) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int at = 0;
        while (true) {
            int lineEnd = at;
            while (lineEnd + 1 < chunked.length && !(chunked[lineEnd] == '\r' && chunked[lineEnd + 1] == '\n')) {
                lineEnd++;
            }
            if (lineEnd + 1 >= chunked.length) {
                throw new IOException("a chunk size line does not end");
            }
            int size = Integer.parseInt(new String(chunked, at, lineEnd - at, StandardCharsets.ISO_8859_1).split(";")[0].trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(chunked, lineEnd + 2, size);
            at = lineEnd + 2 + size + 2;
        }
    }
}
