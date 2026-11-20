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
package jp.aegif.nemaki;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The connections outside the setup path that send credentials or a token on an
 * {@code HttpURLConnection} of their own refuse redirects before they are used (3.4.2).
 *
 * <p>The setup path's connections are {@code SetupConnectionsDoNotFollowRedirectsTest}'s. These are the
 * rest of the {@code openConnection()} calls an external review counted (2026-10-14, §2.4): the tombstone
 * purge and the connection probe to CouchDB (with the CouchDB credentials), the Google Docs read (with the
 * user's bearer token), and the RFC 3161 and OpenTimestamps posts. On JDK 21 a buffered POST follows a 307
 * with its body and a 302 as a GET, and a GET follows both; a POST in fixed-length streaming mode does not
 * follow (measured 2026-11-05) — the two anchor posts were already in that mode, so their refusal is said
 * here rather than left to it.
 *
 * <p>What this reads: each method's body, comments removed and the text of every literal emptied, through
 * {@link JavaSource} as it stands. It holds that the method opens one connection, and that
 * {@code <connection>.setInstanceFollowRedirects(false);} comes after it and before the connection is
 * written to or read from, and that nothing else in the method names the redirect setting (a later
 * {@code setInstanceFollowRedirects(true)} took it back and was not refused — the confirmation review of
 * batch 2, subagent P3-2). It is a reading of the code as written, not an interpreter: a refusal
 * inside an {@code if}, or reflection, would get past it. Where a redirect can be produced, the behaviour is
 * locked as well: {@code APurgeDoesNotFollowARedirectTest} (the purge) and
 * {@code OpenTimestampsAnchorTargetTest.Redirects} (a 3xx is not the sidecar's answer). The probe's HTTP
 * request runs only when a plain socket to the same address has failed, the Google Docs URL is fixed, and
 * the RFC 3161 post does not follow in streaming mode, so for those three this reading is the lock.
 *
 * <p>Not here: {@code BuildInfoResource} calls {@code openConnection()} on the URL of its own class file
 * inside the WAR (a {@code jar:} or {@code file:} URL), which is not HTTP. {@code CloudDriveServiceImpl}'s
 * OneDrive download uses {@code java.net.http.HttpClient} with {@code Redirect.NORMAL}, which Microsoft
 * Graph's {@code /content} needs (it answers with a redirect to the download); on the JDK 21 the image runs
 * (21.0.11), 21.0.12 and 27 that client drops the {@code Authorization} header when the redirect changes
 * the host (measured 2026-11-05; JDK 17 sent it).
 */
class CredentialedConnectionsDoNotFollowRedirectsTest {

    private record Site(String file, String method, String connection) {
    }

    private static final List<Site> SITES = List.of(
            new Site("src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java",
                    "public boolean purgeTombstone(String id)", "conn"),
            new Site("src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientPool.java",
                    "private boolean isConnected()", "connection"),
            new Site("src/main/java/jp/aegif/nemaki/businesslogic/impl/CloudDriveServiceImpl.java",
                    "private org.json.simple.JSONArray fetchDocsApprovalsViaRest(", "conn"),
            new Site("src/main/java/jp/aegif/nemaki/rest/purview/anchor/Rfc3161AnchorTarget.java",
                    "private byte[] post(byte[] derRequest)", "conn"),
            new Site("src/main/java/jp/aegif/nemaki/rest/purview/anchor/OpenTimestampsAnchorTarget.java",
                    "private JsonNode post(String path, String jsonBody)", "conn"));

    @Test
    @DisplayName("each connection that carries credentials or a token refuses redirects before it is used")
    void eachCredentialedConnectionRefusesRedirectsBeforeItIsUsed() throws Exception {
        List<String> mayFollow = new ArrayList<>();
        for (Site site : SITES) {
            String code = JavaSource.withoutLiteralText(
                    JavaSource.methodBody(JavaSource.read(site.file()), site.method()));
            String at = site.file().substring(site.file().lastIndexOf('/') + 1) + " " + site.method();
            int opens = code.indexOf(".openConnection(");
            if (opens < 0 || code.indexOf(".openConnection(", opens + 1) >= 0) {
                mayFollow.add(at + " — does not open exactly one connection");
                continue;
            }
            String refusal = site.connection() + ".setInstanceFollowRedirects(false);";
            int refused = code.indexOf(refusal, opens);
            if (refused < 0) {
                mayFollow.add(at + " — no " + refusal + " after it opens the connection");
                continue;
            }
            // Whatever else names the setting is not the refusal: a later setInstanceFollowRedirects(true), the
            // JVM-wide setFollowRedirects, a value passed in (as SetupConnectionsDoNotFollowRedirectsTest holds).
            String rest = code.substring(0, refused) + code.substring(refused + refusal.length());
            if (rest.contains("FollowRedirects")) {
                mayFollow.add(at + " — names the redirect setting outside " + refusal);
            }
            for (String use : List.of(".getOutputStream(", ".getResponseCode(", ".getInputStream(", ".connect(",
                    ".getResponseMessage(", ".getContentType(", ".getHeaderField(", ".getErrorStream(")) {
                int used = code.indexOf(site.connection() + use, opens);
                if (used >= 0 && used < refused) {
                    mayFollow.add(at + " — " + site.connection() + use + " comes before " + refusal);
                }
            }
        }
        assertEquals(List.of(), mayFollow, "connections that may follow a redirect");
    }
}
