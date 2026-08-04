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
package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageDrestReadiness;
import jp.aegif.nemaki.rest.purview.journal.LineageSpoolMachinery;
import jp.aegif.nemaki.rest.purview.publish.CloudMetadataSnapshotFormat;
import jp.aegif.nemaki.rest.purview.state.CloudMetadataCursorInspection;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * The 4b preflight routes (v2.3.27): what the deployment can answer, what it must refuse to
 * claim, and — above all — that a check for residual tokens never prints one.
 */
public class LineagePreflightShapeTest {

    private static final String TOKEN_URL =
            "https://contoso.sharepoint.com/:x:/g/personal/u/PATHTOKEN?authkey=AUTHKEYabc";

    private LineageJournalController controller;
    private PurviewCursorStateService cursorStateService;
    private LineageConfig lineageConfig;
    private LineageDrestReadiness readiness;
    private LineageSpoolMachinery machinery;

    @TempDir
    Path spoolDir;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LineageJournalController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        set("httpRequest", request);

        cursorStateService = mock(PurviewCursorStateService.class);
        set("cursorStateService", cursorStateService);

        lineageConfig = mock(LineageConfig.class);
        when(lineageConfig.getSpoolDir()).thenReturn(spoolDir.toString());
        set("lineageConfig", lineageConfig);

        readiness = mock(LineageDrestReadiness.class);
        when(readiness.evaluate())
                .thenReturn(new LineageDrestReadiness.Readiness(true, List.of()));
        set("drestReadinessBean", readiness);

        machinery = mock(LineageSpoolMachinery.class);
        when(machinery.probeReadiness()).thenReturn(true);
        set("preflightSpoolMachinery", machinery);

        repositoryInfoMap = mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        when(repositoryInfoMap.keys()).thenReturn(new java.util.LinkedHashSet<>(
                List.of("bedroom")));
        set("repositoryInfoMap", repositoryInfoMap);
    }

    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;

    private void set(String field, Object value) throws Exception {
        Field f = LineageJournalController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private static CloudMetadataCursorInspection clean(String repositoryId) {
        return CloudMetadataCursorInspection.of(repositoryId, PurviewStateStore.RawEntry.of(
                CloudMetadataSnapshotFormat.entry("doc-1", "onedrive", "f", "2026-01-01")));
    }

    private static CloudMetadataCursorInspection dirty(String repositoryId) {
        return CloudMetadataCursorInspection.of(repositoryId, PurviewStateStore.RawEntry.of(
                "doc-1|onedrive|file-1|" + TOKEN_URL + "|2026-01-01"));
    }

    // ---------------------------------------------------------------- /preflight/cursors

    @Test
    public void aCleanDeploymentPassesTheCursorCheck() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        Map<String, Object> body = controller.preflightCursors().getBody();
        assertEquals("PASS", body.get("verdict"));
        assertEquals(1, body.get("checked"));
    }

    @Test
    public void aPopulatedUrlSlotFailsTheCursorCheck() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom"), dirty("canopy")));
        Map<String, Object> body = controller.preflightCursors().getBody();
        assertEquals("FAIL", body.get("verdict"));
    }

    @Test
    public void aReadErrorFailsTheCursorCheck() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(CloudMetadataCursorInspection.of("bedroom",
                        PurviewStateStore.RawEntry.error())));
        Map<String, Object> body = controller.preflightCursors().getBody();
        assertEquals("FAIL", body.get("verdict"), "ERROR is never green");
    }

    /** The response is the one place a leak would be published. */
    @Test
    public void noCursorValueOrTokenFragmentReachesTheResponse() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(dirty("bedroom")));
        String rendered = String.valueOf(controller.preflightCursors().getBody());
        for (String fragment : List.of(TOKEN_URL, "AUTHKEYabc", "PATHTOKEN",
                "sharepoint.com", "authkey")) {
            assertFalse(rendered.contains(fragment), "the response leaked '" + fragment + "'");
        }
    }

    // ---------------------------------------------------------------- /preflight

    @Test
    public void anUnwiredReaderAdmissionIsAFailureNotAnOmission() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        Map<String, Object> body = controller.preflight().getBody();
        assertNotNull(body.get("readerAdmission"), "an omitted gate reads as fine");
        assertEquals("FAIL", body.get("verdict"));
    }

    @Test
    public void anUnwiredRepositoryInventoryFailsRatherThanReadingAsEmpty() throws Exception {
        set("repositoryInfoMap", null);
        Map<String, Object> body = controller.preflightCursors().getBody();
        assertEquals("FAIL", body.get("verdict"),
                "an inventory we could not obtain is one we did not check");
    }

    @Test
    public void theOverallVerdictIsNeverPass() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        Map<String, Object> body = controller.preflight().getBody();
        assertNotEquals("PASS", String.valueOf(body.get("verdict")),
                "the externally-measured items mean the app cannot declare a pass");
        assertTrue(List.of("FAIL", "EXTERNAL_EVIDENCE_REQUIRED")
                .contains(String.valueOf(body.get("verdict"))));
    }

    @Test
    public void whatTheApplicationCannotCheckIsNamedNotOmitted() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        List<String> notCheckable = (List<String>) controller.preflight().getBody()
                .get("notCheckableByThisApplication");
        assertNotNull(notCheckable);
        assertFalse(notCheckable.isEmpty());
        String joined = String.join(" ", notCheckable);
        for (String subject : List.of("scale-to-one", "encryption", "key custody",
                "persistence across a restart", "Purview")) {
            assertTrue(joined.contains(subject), "unnamed: " + subject);
        }
    }

    @Test
    public void theSpoolIsReportedByRealPathAndFileStore() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> spool =
                (Map<String, Object>) controller.preflight().getBody().get("spool");
        assertEquals("PASS", spool.get("verdict"));
        assertNotNull(spool.get("realPath"));
        assertTrue(Path.of((String) spool.get("realPath")).isAbsolute());
        assertNotNull(spool.get("fileStore"), "the volume is what an operator checks");
    }

    @Test
    public void aMissingSpoolPathFailsRatherThanBeingReportedAsConfigured() throws Exception {
        Path gone = spoolDir.resolve("not-created");
        when(lineageConfig.getSpoolDir()).thenReturn(gone.toString());
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> spool =
                (Map<String, Object>) controller.preflight().getBody().get("spool");
        assertEquals("FAIL", spool.get("verdict"));
        assertNull(spool.get("realPath"));
    }

    @Test
    public void anUnsetSpoolDirFails() {
        when(lineageConfig.getSpoolDir()).thenReturn("");
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        Map<String, Object> body = controller.preflight().getBody();
        assertEquals("FAIL", body.get("verdict"));
    }

    /** Without a barrier there is no allowlist, so the production policy cannot be met. */
    @Test
    public void anAbsentBarrierIsNotAPassBecauseTheAllowlistCannotHaveBeenSet() {
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> barrier =
                (Map<String, Object>) controller.preflight().getBody().get("barrier");
        assertEquals("empty-allowlist-not-acceptable-in-production",
                barrier.get("approvedBinaryDigestsPolicy"));
        assertEquals("FAIL", controller.preflight().getBody().get("verdict"));
    }

    @Test
    public void anUnmeasurableDigestFails() throws Exception {
        LineageBinaryDigest unmeasurable = mock(LineageBinaryDigest.class);
        when(unmeasurable.digest())
                .thenThrow(new LineageBinaryDigest.UnmeasurableException("no root", null));
        set("binaryDigest", unmeasurable);
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> barrier =
                (Map<String, Object>) controller.preflight().getBody().get("barrier");
        assertEquals(Boolean.FALSE, barrier.get("binaryDigestMeasurable"));
        assertNull(barrier.get("measuredBinaryDigest"), "never a fabricated value");
        assertEquals("FAIL", controller.preflight().getBody().get("verdict"));
    }

    @Test
    public void theCircularityWarningIsInTheResponse() throws Exception {
        LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
        when(digest.digest()).thenReturn("d".repeat(64));
        set("binaryDigest", digest);
        when(cursorStateService.inspectCloudMetadataCursors(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(clean("bedroom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> barrier =
                (Map<String, Object>) controller.preflight().getBody().get("barrier");
        assertEquals("d".repeat(64), barrier.get("measuredBinaryDigest"));
        assertTrue(String.valueOf(barrier.get("binaryDigestNote")).contains("circular"),
                "the value is for comparison, and the response says so");
    }

    /** The offline CLI and the ACK path must be one algorithm, not two that agree. */
    @Test
    public void theOfflineDigestEqualsWhatTheAckPathWouldReport(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("WEB-INF/lib"));
        Files.write(root.resolve("WEB-INF/lib/a.jar"), "aaa".getBytes());

        LineageConfig config = mock(LineageConfig.class);
        when(config.getBarrierDistributionDir()).thenReturn(root.toString());
        java.lang.reflect.Constructor<LineageBinaryDigest> ctor =
                LineageBinaryDigest.class.getDeclaredConstructor(LineageConfig.class);
        ctor.setAccessible(true);
        LineageBinaryDigest ackPath = ctor.newInstance(config);

        // run(), not main(): main() ends in System.exit, which took Surefire down with it
        // the first time this test existed.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.lang.reflect.Method run = LineageBinaryDigest.class.getDeclaredMethod("run",
                String[].class, java.io.PrintStream.class, java.io.PrintStream.class);
        run.setAccessible(true);
        int exit = (int) run.invoke(null, new String[] { root.toString() },
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));

        String ackDigest;
        try {
            ackDigest = ackPath.digest();
        } catch (LineageBinaryDigest.UnmeasurableException unmeasurable) {
            // This filesystem gives no SecureDirectoryStream. The contract still holds and is
            // still worth pinning: BOTH paths must refuse, and the CLI must say so with a
            // non-zero exit rather than printing something someone could approve.
            assertEquals(1, exit, "an unmeasurable artifact must not print a digest");
            assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).isBlank());
            assertTrue(err.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("unmeasurable"));
            return;
        }
        assertEquals(0, exit);
        assertEquals(ackDigest, out.toString(java.nio.charset.StandardCharsets.UTF_8).trim(),
                "the CLI must report what the ACK would, through the same code");
    }

    private static void assertNotEquals(String unexpected, String actual, String message) {
        assertFalse(unexpected.equals(actual), message);
    }
}
