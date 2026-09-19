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
package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.audit.AuditOperation;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * A profile the selector cannot show is "retry", never "not found".
 *
 * <h2>The false 404 one layer above the retryable refusal</h2>
 *
 * <p>The profile controller's PUT and DELETE start with {@code get(profileId)} — a Mango
 * selector — and answered 404 when it came back empty. While the selector's index rebuilds
 * that is a profile that EXISTS, and the service refusal written to be retryable (503) sat
 * behind a gate that never let the request reach it. A review named it: the closure's REST
 * behaviour was incomplete until the gate itself asked an index-free question.
 *
 * <p>The one-row delete resolver is measured here too, for the P1 the same review found: the
 * controller authorises against whichever twin the selector returned first, so the caller's
 * repository must travel to the service and be checked on the row actually deleted.
 */
class ImportProfileHiddenIsNotAbsentTest {

    private static final String REPO = "bedroom";
    private static final String PROF = "cloud-import-bedroom";

    private ImportProfileDefinitionController controller;
    private ImportProfileDefinitionService importProfileDefinitionService;
    private IngestAuthorizationService ingestAuthorizationService;
    private HttpServletRequest httpRequest;
    private IngestSchedulerService ingestSchedulerService;
    private AuditLogger auditLogger;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ImportProfileDefinitionController();
        importProfileDefinitionService = mock(ImportProfileDefinitionService.class);
        ingestAuthorizationService = mock(IngestAuthorizationService.class);
        httpRequest = mock(HttpServletRequest.class);
        inject("importProfileDefinitionService", importProfileDefinitionService);
        inject("connectorDefinitionService", mock(ConnectorDefinitionService.class));
        inject("ingestAuthorizationService", ingestAuthorizationService);
        auditLogger = mock(AuditLogger.class);
        inject("auditLogger", auditLogger);
        inject("httpRequest", httpRequest);
        ingestSchedulerService = mock(IngestSchedulerService.class);
        inject("ingestSchedulerService", ingestSchedulerService);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ImportProfileDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private CallContext adminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        lenient().when(ctx.getRepositoryId()).thenReturn(REPO);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(true);
        return ctx;
    }

    private static ImportProfileDefinition profile() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setProfileId(PROF);
        def.setRepositoryId(REPO);
        def.setDisplayName("Cloud Import (bedroom)");
        def.setTargetFolderId("root-1");
        def.setEnabled(true);
        return def;
    }

    @Test
    @DisplayName("GET /admin/import-profiles answers 503, not 500, when the listing could not "
            + "be completed")
    void theListAnswers503WhenTheListingCannotBeCompleted() throws Exception {
        // The typed refusal escaped the list endpoint — no catch, and GlobalExceptionHandler
        // does not cover this package — as a Spring 500. Through MockMvc because the handler
        // is an @ExceptionHandler, which a direct call never reaches.
        adminCtx();
        when(importProfileDefinitionService.listByRepository(REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a full selector page of 'nemaki_conf' carried no new continuation bookmark"));
        org.springframework.test.web.servlet.MockMvc mockMvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/admin/import-profiles"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .status().isServiceUnavailable()),
                "a listing that could not be completed escaped the profile list as a 500");
    }

    @Test
    @DisplayName("PUT: a profile the selector cannot show is 503, not 404")
    void aHiddenProfileIsA503OnPut() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row of this profile could not be read"));

        ResponseEntity<Map<String, Object>> res = controller.update(PROF, profile());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a profile that exists but is hidden by a rebuilding index was reported as "
                        + "NOT FOUND — a false absence one layer above the retryable refusal");
        verify(importProfileDefinitionService, never()).update(any());
    }

    @Test
    @DisplayName("PUT: a profile no index-free read can find is still 404 — the control")
    void anAbsentProfileIsStillA404OnPut() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.existsIndexFree(PROF, REPO)).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.update(PROF, profile());

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(),
                "a genuinely absent profile answered something other than 404 — every "
                        + "404 would become a 503");
    }

    @Test
    @DisplayName("PUT: an index-free read that cannot read a row is 503 too")
    void anUnreadableIndexFreeReadIsA503OnPut() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        // The verbs resolve this repository's row index-free now; the refusal that means
        // "could not read" comes from that read.
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row could not be read"));

        ResponseEntity<Map<String, Object>> res = controller.update(PROF, profile());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
    }

    @Test
    @DisplayName("DELETE: a hidden profile is 503, not 404")
    void aHiddenProfileIsA503OnDelete() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row of this profile could not be read"));

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verify(importProfileDefinitionService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE ?docId: the caller's repository travels to the service, and the "
            + "profile survives (no deletion record, scheduler untouched)")
    void theResolverCarriesTheCallersRepository() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // A SURVIVING row: the service returns how many remain, and Mockito answers 0 for an
        // unstubbed int — which is the "the other twin was deleted concurrently" branch. The
        // ordinary path is what these tests are about.
        when(importProfileDefinitionService.delete(eq(PROF), anyString(), eq(REPO))).thenReturn(1);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, "legacy-row-9");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        // The P1: without the caller's repository the service can only check type and
        // profileId, and a divergent twin in ANOTHER repository is deletable by a caller
        // authorised here.
        verify(importProfileDefinitionService).delete(eq(PROF), eq("legacy-row-9"), eq(REPO));
        verify(importProfileDefinitionService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE ?docId: a row the service refuses is 404 — not distinguishable "
            + "from no row")
    void aRefusedResolverRowIsA404() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        doThrow(new IllegalArgumentException("row canopy-row is not a definition of import"
                + " profile cloud-import-bedroom in repository bedroom"))
                .when(importProfileDefinitionService).delete(eq(PROF), eq("canopy-row"), eq(REPO));

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, "canopy-row");

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(),
                "a refused cross-repository row answered something that distinguishes it "
                        + "from no row at all");
    }

    private CallContext delegatedCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("bob");
        lenient().when(ctx.getRepositoryId()).thenReturn(REPO);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(false);
        return ctx;
    }

    @Test
    @DisplayName("DELETE ?docId: a delegated caller is refused — resolving twins is an "
            + "administrator operation")
    void theResolverIsAdminOnly() {
        // The delegated checks (delegated flag, cmis:all on the target folder) ran on the
        // twin the selector returned, not on the addressed row — so a delegated user
        // managing twin A could delete an admin-managed twin B. A round-2 review named
        // it; the resolver is administrators only.
        CallContext ctx = delegatedCtx();
        ImportProfileDefinition delegated = profile();
        delegated.setDelegated(true);
        when(importProfileDefinitionService.get(PROF)).thenReturn(delegated);
        when(ingestAuthorizationService.resolveFolderId(eq(REPO), any(), any())).thenReturn("root-1");
        when(ingestAuthorizationService.canManageProfileForFolder(eq(ctx), eq(REPO), eq("root-1")))
                .thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, "legacy-row-9");

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "a delegated caller resolved twins through the docId path — the addressed "
                        + "row was never authorised for them");
        verify(importProfileDefinitionService, never()).delete(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE (plain): the caller's repository travels, and an unreadable row is 503")
    void thePlainDeleteCarriesTheRepositoryAndRefusesRetryably() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());

        ResponseEntity<Map<String, Object>> ok = controller.delete(PROF, null);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        verify(importProfileDefinitionService).delete(eq(PROF), eq(REPO));

        doThrow(new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                "cannot be deleted completely"))
                .when(importProfileDefinitionService).delete(eq(PROF), eq(REPO));
        ResponseEntity<Map<String, Object>> refused =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> controller.delete(PROF, null),
                        "the retryable refusal escaped the controller unmapped — a 500");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, refused.getStatusCode(),
                "a delete that could not read every row reported success or 500");
    }

    @Test
    @DisplayName("the row-addressed delete audits the row — success and both denials")
    void theRowAddressedDeleteIsAudited() {
        // The row IS destroyed and the surviving twin's content becomes the effective
        // configuration. A review found this path silent, then found the replacement audit
        // describing an arbitrary twin; what it may record is what the call establishes.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // A SURVIVING row: the service returns how many remain, and Mockito answers 0 for an
        // unstubbed int — which is the "the other twin was deleted concurrently" branch. The
        // ordinary path is what these tests are about.
        when(importProfileDefinitionService.delete(eq(PROF), anyString(), eq(REPO))).thenReturn(1);

        controller.delete(PROF, "legacy-row-9");

        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(eq(AuditOperation.EXTERNAL_PROFILE_DELETED),
                eq(REPO), anyString(), eq(PROF), eq(true), anyString(), details.capture());
        assertEquals("legacy-row-9", details.getValue().get("definitionRowId"),
                "the audit does not identify the row that was removed: " + details.getValue());
        assertTrue(!details.getValue().containsKey("delegated"),
                "the audit claims a profile-shaped fact this call did not establish: "
                        + details.getValue());

        // the delegated caller's refusal is on the record too
        org.mockito.Mockito.reset(auditLogger);
        delegatedCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        controller.delete(PROF, "legacy-row-9");
        verify(auditLogger).logOperation(eq(AuditOperation.EXTERNAL_PROFILE_DELETED),
                anyString(), anyString(), eq(PROF), eq(false), anyString(),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    @DisplayName("a row delete that left NO row finishes the work the path skips")
    void aRowDeleteThatLostTheRaceStopsTheSchedulerAndRecordsTheDeletion() {
        // Two administrators can each address a different twin, both see a pair, and both
        // delete: the profile is gone through a path that deliberately skips the scheduler
        // stop and the deletion record because it assumes a survivor. The service reports
        // how many rows are left so the caller can finish that job. A review found the race.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq("legacy-row-9"), eq(REPO)))
                .thenReturn(0);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, "legacy-row-9");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ingestSchedulerService).stopIdle(PROF);
        assertTrue(String.valueOf(res.getBody()).contains("no definition row remains"),
                "the caller is not told the profile is gone: " + res.getBody());
        // The RECORD, not only the scheduler: without this the audit call could be removed
        // and the lock would still pass on the stopIdle assertion. A review found the gap.
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(eq(AuditOperation.EXTERNAL_PROFILE_DELETED),
                eq(REPO), anyString(), eq(PROF), eq(true), anyString(), details.capture());
        assertTrue(String.valueOf(details.getValue().get("outcome")).contains("profile deleted"),
                "the audit does not say the profile is gone — and the message field it used "
                        + "to carry that in is dropped on success: " + details.getValue());
    }

    @Test
    @DisplayName("a row delete whose survivors could not be counted says so — and does NOT "
            + "stop the scheduler")
    void aRowDeleteWithAnUnknownSurvivorCountSaysSo() {
        // -1 is "could not ask", not "a row survives" and not "none survives". Stopping the
        // scheduler here would silently disable mail capture for a profile that may still
        // exist — the worse of the two mistakes — so the answer is to say what is unknown.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq("legacy-row-9"), eq(REPO)))
                .thenReturn(-1);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, "legacy-row-9");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ingestSchedulerService, never()).stopIdle(PROF);
        assertTrue(String.valueOf(res.getBody()).contains("could not be established"),
                "an unknown survivor count was reported as an ordinary success: " + res.getBody());
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(eq(AuditOperation.EXTERNAL_PROFILE_DELETED),
                eq(REPO), anyString(), eq(PROF), eq(true), anyString(), details.capture());
        assertTrue(String.valueOf(details.getValue().get("outcome")).contains("unknown"),
                "the audit does not record that the survivors are unknown: " + details.getValue());
    }

    @Test
    @DisplayName("a row delete whose count cannot read a row is 503 and audited, not a 500")
    void aRowDeleteWhoseCountCannotReadIsA503() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq("legacy-row-9"), eq(REPO)))
                .thenThrow(new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row could not be read"));

        ResponseEntity<Map<String, Object>> res =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> controller.delete(PROF, "legacy-row-9"),
                        "the refusal escaped the row-addressed path — an unclassified 500");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verify(auditLogger).logOperation(eq(AuditOperation.EXTERNAL_PROFILE_DELETED),
                anyString(), anyString(), eq(PROF), eq(false), anyString(),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    @DisplayName("DELETE: this repository's rows are reachable when the profileId is also "
            + "used by another repository")
    void aSharedProfileIdDoesNotLockThisRepositoryOut() {
        // get() selects on profileId alone, so with the same id in two repositories it hands
        // back an arbitrary twin. The cross-repository 404 then refused the administrator who
        // owns the OTHER row — and the documented repair ("that repository's administrator
        // deletes their row") was unreachable through the API. A review found it.
        adminCtx();
        ImportProfileDefinition othersRow = profile();
        othersRow.setRepositoryId("canopy");
        when(importProfileDefinitionService.get(PROF)).thenReturn(othersRow);
        // This repository DOES have its own row: the shared-profileId case. (A throwing stub
        // was injected here by a blanket edit and then re-stubbed — the second when(...)
        // re-evaluates the throw during setup, so the test failed before it ran.)
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        // The OTHER repository still has its row: an unstubbed int answers 0, which is the
        // "nothing remains anywhere" branch — the opposite of this test's premise, and it
        // would have hidden a regression in the restraint below.
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(1);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "the caller cannot remove their own repository's rows because the selector "
                        + "returned another repository's twin");
        verify(importProfileDefinitionService).delete(eq(PROF), eq(REPO));
        // This branch stops no scheduler: the profileId is still live in the other
        // repository, and stopIdle is keyed by profileId alone.
        verify(ingestSchedulerService, never()).stopIdle(PROF);
    }

    @Test
    @DisplayName("a survivor elsewhere does NOT protect an IDLE session that serves THIS "
            + "repository")
    void aSessionServingThisRepositoryIsStopped() {
        // The restraint above protects another repository's live capture. Its other half was
        // missing: the IDLE map is keyed by profileId alone and a session captures ONE row's
        // repositoryId for every message it imports, so deleting this repository's row while
        // another keeps the id left mail arriving into a profile that no longer exists — a
        // deletion that does not stop the capture it authorised. A review found it.
        adminCtx();
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(1);
        when(ingestSchedulerService.getIdleProfiles()).thenReturn(java.util.List.of(PROF));
        when(ingestSchedulerService.getIdleRepository(PROF)).thenReturn(REPO);

        assertEquals(HttpStatus.OK, controller.delete(PROF, null).getStatusCode());

        verify(ingestSchedulerService).stopIdle(PROF);
    }

    @Test
    @DisplayName("an IDLE session that cannot be attributed is stopped, not left running")
    void anUnattributableSessionIsStopped() {
        // "Which repository does this session serve" is answerable only while the session
        // recorded it. No answer is not "another repository's" — losing capture is undone by
        // restarting IDLE, a live connection nobody can close is not. (The imports
        // themselves are refused by the message loop, which reloads the profile.)
        adminCtx();
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(1);
        when(ingestSchedulerService.getIdleProfiles()).thenReturn(java.util.List.of(PROF));
        when(ingestSchedulerService.getIdleRepository(PROF)).thenReturn(null);

        assertEquals(HttpStatus.OK, controller.delete(PROF, null).getStatusCode());

        verify(ingestSchedulerService).stopIdle(PROF);
    }

    @Test
    @DisplayName("an IDLE session that serves ANOTHER repository is left running")
    void aSessionServingAnotherRepositoryIsLeftAlone() {
        // The counterpart: stopping on the confined delete would cut a capture this caller
        // does not own. Without this the fix above could be "always stop" and still pass.
        adminCtx();
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(1);
        when(ingestSchedulerService.getIdleProfiles()).thenReturn(java.util.List.of(PROF));
        when(ingestSchedulerService.getIdleRepository(PROF)).thenReturn("canopy");

        assertEquals(HttpStatus.OK, controller.delete(PROF, null).getStatusCode());

        verify(ingestSchedulerService, never()).stopIdle(PROF);
    }

    @Test
    @DisplayName("the shared-profileId branch stops the scheduler when nothing remains")
    void aSharedProfileIdBranchStopsTheSchedulerWhenNothingRemains() {
        // The sibling test above asserts the restraint (a survivor elsewhere leaves the
        // scheduler alone); removing the decision entirely would keep that true, so the
        // other half has to be measured here. A concurrent delete of the other repository's
        // row leaves the profileId gone, and this branch never touched the scheduler.
        adminCtx();
        ImportProfileDefinition othersRow = profile();
        othersRow.setRepositoryId("canopy");
        when(importProfileDefinitionService.get(PROF)).thenReturn(othersRow);
        // This repository DOES have its own row: the shared-profileId case. (A throwing stub
        // was injected here by a blanket edit and then re-stubbed — the second when(...)
        // re-evaluates the throw during setup, so the test failed before it ran.)
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(0);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ingestSchedulerService).stopIdle(PROF);
        // A KNOWN count of zero carries no warning; "could not be established" is reserved
        // for -1. (This asserted a phrase only the row-addressed delete writes — copied from
        // its sibling — and was red on a healthy tree.)
        assertFalse(String.valueOf(res.getBody()).contains("could not be established"),
                "a known count of zero was reported as an unknown one: " + res.getBody());
    }

    @Test
    @DisplayName("GET/PUT: a shared profileId does not hide this repository's own row")
    void theReadVerbsReachThisRepositorysRow() {
        // get() selects on profileId alone, so with the same id in two repositories it hands
        // back an arbitrary twin and the caller was refused 404 for a profile they own. The
        // DELETE path was fixed for this; a review found GET, PUT and the ownership transfer
        // left behind.
        adminCtx();
        ImportProfileDefinition othersRow = profile();
        othersRow.setRepositoryId("canopy");
        when(importProfileDefinitionService.get(PROF)).thenReturn(othersRow);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());

        assertEquals(HttpStatus.OK, controller.get(PROF).getStatusCode(),
                "GET answered 404 for a profile this repository owns");
        assertTrue(controller.update(PROF, profile()).getStatusCode() != HttpStatus.NOT_FOUND,
                "PUT did not get past the 404 gate for a profile this repository owns "
                        + "(the write itself may still refuse a shared profileId with 409)");
        assertTrue(controller.transferOwnership(PROF, Map.of("mode", "admin")).getStatusCode()
                        != HttpStatus.NOT_FOUND,
                "ownership transfer answered 404 for a profile this repository owns");
    }

    @Test
    @DisplayName("GET: another repository's profile is still 404 when this one has none")
    void theReadVerbsStillConfineToTheRepository() {
        adminCtx();
        ImportProfileDefinition othersRow = profile();
        othersRow.setRepositoryId("canopy");
        when(importProfileDefinitionService.get(PROF)).thenReturn(othersRow);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(null);

        assertEquals(HttpStatus.NOT_FOUND, controller.get(PROF).getStatusCode(),
                "cross-repository confinement was traded away");
    }

    @Test
    @DisplayName("DELETE: a profile that really is only another repository's is still 404")
    void anotherRepositorysProfileIsStill404() {
        adminCtx();
        ImportProfileDefinition othersRow = profile();
        othersRow.setRepositoryId("canopy");
        when(importProfileDefinitionService.get(PROF)).thenReturn(othersRow);
        when(importProfileDefinitionService.existsIndexFree(PROF, REPO)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller.delete(PROF, null).getStatusCode(),
                "cross-repository confinement was traded away");
        verify(importProfileDefinitionService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("GET: a profile the selector cannot show is 503, not 404")
    void aHiddenProfileIsA503OnGet() {
        // PUT and DELETE got the split first; GET — the verb an operator reaches for first
        // — still answered 404 for a profile that is there. A review found it.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row of this profile could not be read"));

        ResponseEntity<Map<String, Object>> res = controller.get(PROF);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a profile that exists but is invisible to the index read as absent");
    }

    @Test
    @DisplayName("DELETE: two rows of one profile in this repository is 409, not an arbitrary one")
    void aPairInThisRepositoryIsA409NotAChoice() {
        // Returning either row is the silent choice this batch exists to prevent — and the
        // DELETE that follows authorises from what the read returned and then removes EVERY
        // row of the repository, so a caller authorised by one twin could remove the other.
        adminCtx();
        // DELETE, not GET: this verb authorises from what it resolves and then removes every
        // row of the repository, so it resolves index-free even when the selector answered.
        // The selector returns ONE of the pair — the ordinary case, and the one a shortcut
        // would accept without ever asking.
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException(
                        "import profile " + PROF + " has more than one definition row"));

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "one of two rows was picked, authorised from, and then both were removed");
        verify(importProfileDefinitionService, never()).delete(anyString(), anyString());
    }

    @Test
    @DisplayName("GET: an absence established index-free is answered without a second walk")
    void anAbsenceIsNotRewalked() {
        // getForRepository already walked the whole database and refuses when it cannot
        // read; asking existsIndexFree afterwards doubled the cost and could turn a settled
        // absence into a 503 when only the second walk failed. A review found the pair.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(null);

        assertEquals(HttpStatus.NOT_FOUND, controller.get(PROF).getStatusCode());
        verify(importProfileDefinitionService, never()).existsIndexFree(anyString(), anyString());
    }

    @Test
    @DisplayName("GET: a profile that really is absent is still 404")
    void anAbsentProfileIsStill404OnGet() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.existsIndexFree(PROF, REPO)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller.get(PROF).getStatusCode(),
                "every 404 became a 503");
    }

    @Test
    @DisplayName("a delete that failed part-way is audited and retryable, not a silent 500")
    void aPartlyFailedDeleteIsAuditedAndRetryable() {
        // Rows are deleted one at a time with no transaction across documents. A failure
        // after the first row leaves the profile partly deleted; letting it escape answered
        // 500 with no audit entry for a deletion that had partly happened.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        org.mockito.Mockito.doThrow(
                new ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException(
                        "import profile " + PROF + " is PARTLY deleted: 1 of 2 definition"
                                + " row(s) were removed before the store refused (conflict)."
                                + " Retry to remove the rest.", new RuntimeException("conflict")))
                .when(importProfileDefinitionService).delete(eq(PROF), eq(REPO));

        ResponseEntity<Map<String, Object>> res =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> controller.delete(PROF, null),
                        "the partial failure escaped the controller — a 500");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a partly deleted profile answered something other than retry");
        assertTrue(String.valueOf(res.getBody()).contains("PARTLY deleted"),
                "the 503 came from some other arm than the partial-delete one: " + res.getBody());
        verify(auditLogger, org.mockito.Mockito.atLeastOnce()).logOperation(
                eq(AuditOperation.EXTERNAL_PROFILE_DELETED), anyString(), anyString(),
                anyString(), eq(false), anyString(),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    @DisplayName("a repository-confined delete does not stop a scheduler another repository "
            + "still needs")
    void aConfinedDeleteLeavesAnotherRepositorysSchedulerAlone() {
        // stopIdle is keyed by profileId alone while the delete is confined to one
        // repository. With the same profileId in two repositories — a configuration this
        // batch documents as supported — deleting A's rows cut B's live mail capture. A
        // review found it; the service now reports the rows left ANYWHERE.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(1);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ingestSchedulerService, never()).stopIdle(PROF);
    }

    @Test
    @DisplayName("a plain delete whose survivors could not be counted says so")
    void aPlainDeleteWithAnUnknownCountSaysSo() {
        // -1 is "could not ask". The caller was getting the same body as for a known
        // survivor count, so a failed read read as an answered one — the batch's own
        // subject, on the response of a destructive operation.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(-1);

        ResponseEntity<Map<String, Object>> res = controller.delete(PROF, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ingestSchedulerService, never()).stopIdle(PROF);
        assertTrue(String.valueOf(res.getBody()).contains("could not be established"),
                "an unknown survivor count was reported as an ordinary success: " + res.getBody());
    }

    @Test
    @DisplayName("a delete that left nothing anywhere DOES stop the scheduler")
    void aDeleteThatLeftNothingStopsTheScheduler() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        when(importProfileDefinitionService.delete(eq(PROF), eq(REPO))).thenReturn(0);

        controller.delete(PROF, null);

        verify(ingestSchedulerService).stopIdle(PROF);
    }

    @Test
    @DisplayName("a failing stopIdle does not lose the audit of a deletion that happened")
    void aFailingStopIdleDoesNotLoseTheAudit() {
        // The profile is already deleted when stopIdle runs. Letting its failure escape
        // would answer 500 for a deletion that HAPPENED and drop the audit entry — the
        // caller reads "not deleted" and the security trail has no record. The window is
        // new to the reordered path; a review found it.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        org.mockito.Mockito.doThrow(new RuntimeException("IMAP socket already closed"))
                .when(ingestSchedulerService).stopIdle(PROF);

        ResponseEntity<Map<String, Object>> res =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> controller.delete(PROF, null),
                        "a failing IDLE shutdown escaped AFTER the profile was deleted");

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "a deletion that happened was reported as a failure");
        verify(auditLogger, org.mockito.Mockito.atLeastOnce()).logOperation(
                eq(AuditOperation.EXTERNAL_PROFILE_DELETED), anyString(), anyString(),
                anyString(), eq(true), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    @DisplayName("a REFUSED delete leaves IMAP IDLE running; a successful one stops it")
    void aRefusedDeleteLeavesImapIdleRunning() {
        // stopIdle used to run BEFORE the delete. Once the delete could refuse retryably,
        // that order stopped live mail capture for a profile that still existed — a 503
        // with capture silently disabled, since stopIdle disconnects the adapter and
        // nothing restarts it. A round-3 review caught the ordering.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // The plain delete resolves this repository's row index-free (it authorises from what
        // it resolves and then removes every row, so it must be able to see a PAIR).
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        doThrow(new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                "cannot be deleted completely"))
                .when(importProfileDefinitionService).delete(eq(PROF), eq(REPO));

        ResponseEntity<Map<String, Object>> refused =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> controller.delete(PROF, null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, refused.getStatusCode());
        verify(ingestSchedulerService, never()).stopIdle(PROF);

        // and the control: a delete that succeeds does stop it
        org.mockito.Mockito.reset(importProfileDefinitionService);
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        // reset() drops EVERY stub, including the index-free resolution this verb always
        // performs — restoring only get() left the delete answering 404, so this half of the
        // test measured nothing. A review found it red on a healthy tree.
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(profile());
        ResponseEntity<Map<String, Object>> ok = controller.delete(PROF, null);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        verify(ingestSchedulerService).stopIdle(PROF);
    }

    @Test
    @DisplayName("ownership transfer: a hidden profile is 503, not 404")
    void aHiddenProfileIsA503OnOwnershipTransfer() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a row of this profile could not be read"));

        ResponseEntity<Map<String, Object>> res =
                controller.transferOwnership(PROF, Map.of("mode", "admin"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "the ownership transfer kept the false-404 gate the other verbs lost");
    }

    @Test
    @DisplayName("PUT: the service's retryable refusal reaches the client as 503")
    void theRetryableRefusalReachesTheClientOnPut() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.update(any())).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "the index has not caught up"));

        ResponseEntity<Map<String, Object>> res = controller.update(PROF, profile());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a transient, retryable refusal reached the client as something else");
    }

    @Test
    @DisplayName("PUT: a standing twin pair is 409 — not a retry, not a 500")
    void aStandingTwinPairIsA409OnPut() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(profile());
        when(importProfileDefinitionService.update(any())).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException(
                        "import profile cloud-import-bedroom has 2 definition rows (DELETE ...?docId=...)"));

        ResponseEntity<Map<String, Object>> res = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> controller.update(PROF, profile()),
                "the standing-twin refusal escaped the controller unmapped — a 500");

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "a standing twin pair answered something other than 409 — a retry that "
                        + "cannot succeed, or a bad request that was not one");
    }
}
