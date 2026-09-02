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
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.JavaSource;

/**
 * The export refusals reach the client instead of being logged over.
 *
 * <h2>Why the exporter's refusal was not enough on its own</h2>
 *
 * <p>{@code ZipExporter} was made to abort rather than finish an archive that lost something.
 * The resource that streams it wrapped three of those calls in {@code catch (Exception) →
 * log.warn}, so the refusal was written to a log file and the archive was finished anyway —
 * the same re-flattening one layer up that this batch has now hit in {@code UserController},
 * {@code DelegatedCallContextFactory}, {@code TypeServiceImpl} and
 * {@code CatalogPropertyMappingResolver}.
 *
 * <p>Asserted on the SOURCE because the three call sites live inside an anonymous
 * {@code StreamingOutput} in a Jersey resource: reaching them behaviourally means standing up
 * the resource, and what has to hold is a property of the catch blocks themselves.
 */
class ExportRefusalReachesTheClientTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java";

    /**
     * Every catch inside the two streaming bodies either rethrows or raises a refusal.
     *
     * <p>The first version of this test asserted that three particular {@code log.warn}
     * strings were ABSENT and one refusal string was PRESENT. An audit listed three edits
     * that restore the swallow and keep it green: reword the log line; build the refusal and
     * log it instead of throwing; revert only ONE of the two identical call sites. What has
     * to hold is a property of every catch in those bodies, so that is what is checked.
     */
    private static void assertEveryCatchRefuses(String body, String where) {
        int at = body.indexOf("} catch (");
        int checked = 0;
        while (at >= 0) {
            int end = matchingClose(body, at);
            String block = body.substring(at, end);
            boolean refuses = block.contains("throw ");
            assertTrue(refuses,
                    "a catch in " + where + " swallows its failure, so the archive is "
                            + "finished over it:\n" + block);
            checked++;
            at = body.indexOf("} catch (", end);
        }
        assertTrue(checked >= 3,
                where + " has fewer catches than the three this test exists for — it was "
                        + "restructured and the check no longer covers what it named (" 
                        + checked + " found)");
    }

    /**
     * End index of the block opened by the first '{' at or after {@code from}.
     *
     * <p>Delegated. This used to be a hand-rolled brace counter with no string-literal or
     * comment handling — so a single '{@code {}' inside a literal added to either streaming
     * body would have shifted every region below it silently, instead of tripping
     * {@link jp.aegif.nemaki.util.test.HarnessBroken}. An audit pointed out that the careful
     * version was already sitting in {@code JavaSource}, which this method's own comment
     * claimed to mirror.
     */
    private static int matchingClose(String text, int from) {
        return JavaSource.matchingClose(text, from);
    }

    @Test
    @DisplayName("every catch in the folder-export streamer refuses rather than logging")
    void theFolderExportStreamerRefuses() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        // The two streaming bodies are textually identical apart from one lineage call, so
        // they are located by the call that differs and bounded by brace matching.
        String body = streamingBodyContaining(source, "publishZipFolderExportLineage(");
        assertEveryCatchRefuses(body, "the folder-export streamer");
    }

    @Test
    @DisplayName("every catch in the objects-export streamer refuses too — the second site")
    void theObjectsExportStreamerRefuses() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        String body = streamingBodyContaining(source, "publishSelectedObjectsExportLineage(");
        assertEveryCatchRefuses(body, "the objects-export streamer");
    }

    @Test
    @DisplayName("the refusal path stops forwarding before it closes the archive")
    void theRefusalStopsForwardingBeforeClosing() throws Exception {
        // close() calls finish(), which writes the ZIP central directory. With
        // try-with-resources the refusal path handed back an archive that OPENS, with the
        // last entry truncated — and the ledger claimed the opposite. Not closing at all
        // fixed that and leaked the native deflater. Both are wanted, so the archive is
        // ALWAYS closed and the refusal path closes it into a sink that has stopped
        // forwarding: the directory is produced and discarded.
        //
        // The name of this test used to say "closed on the success path only", which stopped
        // being true when the refusal path started closing too. A review caught the name and
        // the implementation disagreeing — the same defect class as a stale comment.
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        for (String marker : new String[] {"publishZipFolderExportLineage(",
                "publishSelectedObjectsExportLineage("}) {
            String body = streamingBodyContaining(source, marker);
            assertFalse(body.contains("try (ZipOutputStream"),
                    "the streamer at " + marker + " closes the archive in a try-with-resources"
                            + " again, so a refused export is handed back as an openable one");
            // Over the SINK, not over the response stream. stopForwarding() protects nothing
            // if the archive writes straight through — a review found the control sabotaging
            // exactly that while this lock looked only at the close ordering.
            assertTrue(body.contains("new ZipOutputStream(sink)"),
                    "the archive at " + marker + " is built over the response stream rather"
                            + " than the discardable sink, so the central directory reaches"
                            + " the client on the refusal path");
            int close = body.indexOf("zos.close();");
            // The OUTER catch — the last one in the body — is what a refusal reaches. The
            // first `} catch (` belongs to an inner try around one step of the walk, and
            // comparing against that measured nothing.
            int outerCatch = body.lastIndexOf("} catch (");
            int finallyAt = body.indexOf("} finally {");
            assertTrue(close > 0, "the archive is never closed at " + marker);
            assertTrue(close < outerCatch,
                    "the success-path close at " + marker + " moved into or after the outer"
                            + " catch, so an ordinary export no longer finishes its archive");
            assertTrue(finallyAt < 0 || close < finallyAt,
                    "zos.close() moved into a finally at " + marker + ", which would write"
                            + " the central directory to the CLIENT on the refusal path");
            // And the refusal path: stop forwarding, THEN close. Reversed, the directory
            // reaches the client and the archive opens; omitted, the deflater leaks.
            String tail = body.substring(outerCatch);
            int stop = tail.indexOf("sink.stopForwarding();");
            int closeQuietly = tail.indexOf("closeQuietly(zos);");
            assertTrue(stop >= 0 && closeQuietly > stop,
                    "the refusal path at " + marker + " no longer stops forwarding before it"
                            + " closes the archive: " + tail);
        }
    }

    /** The {@code write(OutputStream)} body that contains {@code marker}. */
    private static String streamingBodyContaining(String source, String marker) {
        // lastIndexOf, not indexOf: each of these names is also a method DECLARATION earlier
        // in the file, and the declaration comes before any streaming body — so searching
        // forwards found it and the lookup for an enclosing body failed.
        int markerAt = source.lastIndexOf(marker);
        assertTrue(markerAt > 0, "the streamer identified by " + marker + " is gone — this "
                + "test no longer looks at what it names");
        int bodyStart = source.lastIndexOf("public void write(OutputStream output)", markerAt);
        assertTrue(bodyStart > 0, "no streaming body before " + marker);
        return source.substring(bodyStart, matchingClose(source, bodyStart));
    }
}
