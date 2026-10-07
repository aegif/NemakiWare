package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.init.DatabasePreInitializer;

/**
 * Nothing on the setup path follows a redirect.
 *
 * <p>Every request here goes to the CouchDB a setup request named, with the CouchDB admin's
 * credentials. {@code UrlValidator} checks that URL; a redirect from it is a second address
 * nobody checked. On JDK 21 an {@code HttpURLConnection} PUT follows 301 / 302 / 303 / 307 and
 * re-sends its body — only the Authorization header is dropped when the host changes. The GET
 * helper and the probes already refused redirects; the three admin and config PUTs, and
 * {@code DatabasePreInitializer} (which {@code /apply} drives with the same URL), did not.
 *
 * <p>Each behaviour test points the code at a stand-in CouchDB that answers with a redirect to a
 * second server, and asserts that the second server heard nothing — and that the stand-in did
 * hear the request, so the test cannot pass by never getting there. The last test reads the
 * source: every connection opened on this path refuses redirects before it is used, so a new
 * call site that forgets is caught even where no behaviour test reaches.
 */
class SetupConnectionsDoNotFollowRedirectsTest {

    private static final String AUTH = CouchDbConfigWriter.basicAuth("admin", "fixture");

    private static final String ADMIN_VIEW =
            "{\"rows\":[{\"value\":{\"_id\":\"admin-doc\",\"_rev\":\"1-a\",\"userId\":\"admin\"}}]}";

    /** What the stand-in answers: a status, headers, and a body. */
    private record Reply(int status, Map<String, String> headers, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        Reply respond(String method, String path);
    }

    private static Reply json(int status, String body) {
        return new Reply(status, Map.of("Content-Type", "application/json"), body);
    }

    private static Reply status(int status) {
        return new Reply(status, Map.of(), "");
    }

    private static Reply redirect(Server to) {
        return new Reply(307, Map.of("Location", to.url() + "/elsewhere"), "");
    }

    /**
     * A minimal local HTTP/1.1 server that records every request that reaches it, one connection
     * per request. Not {@code com.sun.net.httpserver}: that refuses the request line the setup
     * code sends — its view keys carry unencoded quotes, which CouchDB accepts — before any
     * handler runs, so the stand-in would never hear the request it is there to answer.
     */
    private static final class Server implements AutoCloseable {
        private final ServerSocket socket;
        final List<String> heard = Collections.synchronizedList(new ArrayList<>());

        Server(Responder responder) throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        serve(client, responder);
                    } catch (IOException closedOrHungUp) {
                        // keep serving until close()
                    }
                }
            }, "setup-redirect-lock-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void serve(Socket client, Responder responder) throws IOException {
            client.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            int contentLength = 0;
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            in.readNBytes(contentLength);
            heard.add(method + " " + path);

            Reply reply = responder.respond(method, path);
            byte[] body = "HEAD".equals(method) ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(reply.status()).append(" Fixture\r\n");
            reply.headers().forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int c = in.read(); c != -1; c = in.read()) {
                if (c == '\n') {
                    String text = line.toString(StandardCharsets.US_ASCII);
                    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                }
                line.write(c);
            }
            return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        boolean heardA(String method) {
            synchronized (heard) {
                return heard.stream().anyMatch(h -> h.startsWith(method + " "));
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** The address a followed redirect would reach. It answers like a CouchDB that accepted the write. */
    private static Server elsewhere() throws IOException {
        return new Server((method, path) -> json(201, "{\"ok\":true}"));
    }

    @Test
    @DisplayName("a setup config write does not follow a redirect")
    void aConfigWriteDoesNotFollowARedirect() throws Exception {
        try (Server elsewhere = elsewhere();
                Server couch = new Server((method, path) ->
                        "GET".equals(method) ? json(200, "{\"rows\":[]}") : redirect(elsewhere))) {
            try {
                CouchDbConfigWriter.putConfigValue(couch.url(), AUTH, "fixture.key", "fixture-value");
            } catch (Exception theRedirectIsNotASuccessfulWrite) {
                // expected — what matters is where the PUT went
            }
            assertEquals(List.of(), elsewhere.heard,
                    "the config PUT followed the CouchDB's redirect to a second address");
            assertTrue(couch.heardA("PUT"), "the PUT never reached the stand-in CouchDB: " + couch.heard);
        }
    }

    @Test
    @DisplayName("the change-password admin write does not follow a redirect")
    void theChangePasswordAdminWriteDoesNotFollowARedirect() throws Exception {
        try (Server elsewhere = elsewhere();
                Server couch = new Server((method, path) ->
                        "GET".equals(method) ? json(200, ADMIN_VIEW) : redirect(elsewhere))) {
            boolean updated = new SetupAdminResource().updateAdminInDb(couch.url(), "bedroom", AUTH, "$2a$10$fixture");
            assertEquals(List.of(), elsewhere.heard,
                    "the admin document PUT followed the CouchDB's redirect to a second address");
            assertTrue(couch.heardA("PUT"), "the PUT never reached the stand-in CouchDB: " + couch.heard);
            assertFalse(updated, "a redirect was reported as a successful password change");
        }
    }

    @Test
    @DisplayName("the /apply admin write does not follow a redirect")
    void theApplyAdminWriteDoesNotFollowARedirect() throws Exception {
        try (Server elsewhere = elsewhere();
                Server couch = new Server((method, path) ->
                        "GET".equals(method) ? json(200, ADMIN_VIEW) : redirect(elsewhere))) {
            try {
                new SetupApplyResource().updateAdminInDb(couch.url(), "bedroom", AUTH, "$2a$10$fixture");
            } catch (Exception theRedirectIsNotASuccessfulWrite) {
                // expected — what matters is where the PUT went
            }
            assertEquals(List.of(), elsewhere.heard,
                    "the admin document PUT followed the CouchDB's redirect to a second address");
            assertTrue(couch.heardA("PUT"), "the PUT never reached the stand-in CouchDB: " + couch.heard);
        }
    }

    @Test
    @DisplayName("initializing the databases for /apply does not follow a redirect")
    void initializingDatabasesDoesNotFollowARedirect() throws Exception {
        String[] keys = {"db.couchdb.auth.username", "db.couchdb.auth.password",
                "nemaki.startup.require-repositories-yml"};
        Map<String, String> saved = new HashMap<>();
        for (String key : keys) {
            saved.put(key, System.getProperty(key));
        }
        try (Server elsewhere = elsewhere();
                Server couch = new Server((method, path) -> {
                    if ("HEAD".equals(method) && "/bedroom".equals(path)) {
                        return redirect(elsewhere);   // the existence check
                    }
                    if ("HEAD".equals(method)) {
                        return status(404);           // "missing" — so the create PUT runs
                    }
                    return redirect(elsewhere);       // every create, write and read
                })) {
            System.setProperty("db.couchdb.auth.username", "admin");
            System.setProperty("db.couchdb.auth.password", "fixture");
            // No StartupProbeService here: the legacy list names the databases instead.
            System.setProperty("nemaki.startup.require-repositories-yml", "false");
            try {
                new DatabasePreInitializer().initializeDatabases(couch.url(), "admin", "fixture");
            } catch (Exception theRedirectIsNotASuccessfulInitialization) {
                // what matters is where the requests went
            }
            assertEquals(List.of(), elsewhere.heard,
                    "database initialization followed the CouchDB's redirect to a second address");
            assertTrue(couch.heard.contains("HEAD /bedroom"),
                    "the existence check never reached the stand-in CouchDB: " + couch.heard);
            assertTrue(couch.heardA("PUT"), "no create or write reached the stand-in CouchDB: " + couch.heard);
        } finally {
            for (String key : keys) {
                if (saved.get(key) == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, saved.get(key));
                }
            }
        }
    }

    @Test
    @DisplayName("every connection opened on the setup path refuses redirects before it is used")
    void everyConnectionOnTheSetupPathRefusesRedirects() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listed = Files.list(Path.of("src/main/java/jp/aegif/nemaki/api/setup/resource"))) {
            listed.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
        }
        files.add(Path.of("src/main/java/jp/aegif/nemaki/init/DatabasePreInitializer.java"));
        files.add(Path.of("src/main/java/jp/aegif/nemaki/init/StartupProbeService.java"));

        int connections = 0;
        List<String> mayFollow = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("*") || line.startsWith("//") || !line.contains(".openConnection(")) {
                    continue;
                }
                connections++;
                boolean refused = false;
                for (int j = i + 1; j <= Math.min(i + 2, lines.size() - 1); j++) {
                    refused |= lines.get(j).contains("setInstanceFollowRedirects(false)");
                }
                if (!refused) {
                    mayFollow.add(file.getFileName() + ":" + (i + 1) + "  " + line);
                }
            }
        }
        assertTrue(connections >= 9, "found only " + connections
                + " connections on the setup path — the scan is not reading the files it names");
        assertEquals(List.of(), mayFollow,
                "connections on the setup path that do not refuse redirects within two lines of opening");
    }
}
