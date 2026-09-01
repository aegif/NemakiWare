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

import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every door on the anchor API is admin-only.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>It did not, for a while: {@code AnchorController} shipped with four endpoints, four
 * {@code requireAdmin()} calls and zero tests. A review pointed out that deleting the three
 * lines in {@code status()} would let any authenticated caller read the Merkle root, the
 * ledger's head sequence and the receipt list, and nothing in the suite would notice.
 *
 * <p>The sibling {@code AuthenticityReportControllerTest} had already written down the reason
 * this matters — "it would be entirely possible to gate one and leave the other open" — while
 * this controller had four ungated-by-omission doors.
 *
 * <p>Rather than one test per endpoint, the first test walks EVERY mapped method by reflection.
 * A fifth endpoint added later is covered the day it is written, which a hand-listed set is not.
 */
class AnchorControllerTest {

    private static AnchorController controllerFor(boolean admin) {
        AnchorController controller = new AnchorController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        return controller;
    }

    private static List<Method> mappedEndpoints() {
        List<Method> endpoints = new ArrayList<>();
        for (Method method : AnchorController.class.getDeclaredMethods()) {
            // EVERY mapping annotation. Limiting this to Get/Post is what made the claim
            // "a fifth endpoint is covered the day it is written" untrue — this project uses
            // PATCH, and a @PutMapping/@DeleteMapping/@PatchMapping door would have been
            // walked past in silence (review).
            for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(
                    GetMapping.class, PostMapping.class,
                    org.springframework.web.bind.annotation.PutMapping.class,
                    org.springframework.web.bind.annotation.DeleteMapping.class,
                    org.springframework.web.bind.annotation.PatchMapping.class,
                    org.springframework.web.bind.annotation.RequestMapping.class)) {
                if (method.getAnnotation(mapping) != null) {
                    endpoints.add(method);
                    break;
                }
            }
        }
        return endpoints;
    }

    /** Plausible arguments for each endpoint, by parameter type. */
    private static Object[] argumentsFor(Method method) {
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) {
                args[i] = "bedroom";
            } else if (type == int.class) {
                args[i] = 10;
            } else if (type == boolean.class) {
                args[i] = false;
            }
        }
        return args;
    }

    @Test
    @DisplayName("EVERY mapped endpoint refuses a non-admin")
    void everyEndpointIsAdminOnly() throws Exception {
        AnchorController controller = controllerFor(false);
        List<Method> endpoints = mappedEndpoints();

        // A count guard, so an endpoint that stops being mapped (and therefore stops being
        // checked) is visible rather than silently reducing the coverage of this test.
        assertEquals(5, endpoints.size(),
                "the anchor API has " + endpoints.size() + " mapped endpoints, not 5; if one "
                        + "was added, confirm it is gated and update this number");

        for (Method endpoint : endpoints) {
            Object response = endpoint.invoke(controller, argumentsFor(endpoint));
            HttpStatus status = (HttpStatus) response.getClass()
                    .getMethod("getStatusCode").invoke(response);
            assertEquals(HttpStatus.FORBIDDEN, status,
                    endpoint.getName() + " served a non-admin. This API exposes the Merkle "
                            + "root, the ledger's head sequence and every anchor receipt, and "
                            + "checkpoint-and-anchor SENDS data to an external service.");
        }
    }

    @Test
    @DisplayName("a checkpoint that was NOT sealed does not answer 200 success")
    void aFailedSealIsNotReportedAsSuccess() throws Exception {
        // closeCheckpoint reports expected failures in its RETURNED map, not by throwing. The
        // outer status said "success" over an inner "error" and then anchored the PREVIOUS
        // checkpoint — so an operator saw 200 for a seal that did not happen (review).
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.anchor.AnchorService anchors =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorService.class);
        setField(controller, "anchorService", anchors);
        jp.aegif.nemaki.evidence.EvidenceLedgerService ledger =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerService.class);
        when(ledger.closeCheckpoint(anyString(), anyString())).thenReturn(
                java.util.Map.of("status", "error", "message", "the span does not verify"));
        setField(controller, "ledgerService", ledger);
        // The ledger store MUST be wired here. The defect being guarded against is "anchor the
        // PREVIOUS checkpoint anyway", and without a store there is no previous checkpoint to
        // anchor — the never() below would then be held up by the fixture rather than by the
        // code, and a re-introduced call would sail through. Measured: with the store left
        // null, putting the anchor call back into the error branch kept every test green.
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.latestCheckpoint(anyString())).thenReturn(
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT, null,
                        "2026-08-25T00:00:00Z"));
        setField(controller, "ledgerStore", store);

        Object response = AnchorController.class
                .getDeclaredMethod("checkpointAndAnchor", String.class)
                .invoke(controller, "bedroom");
        HttpStatus code = (HttpStatus) response.getClass()
                .getMethod("getStatusCode").invoke(response);

        assertTrue(code != HttpStatus.OK,
                "a checkpoint that was not sealed answered " + code + "; the caller reads that "
                        + "as a seal that happened");
        // The status code was the only thing asserted, and the status code is not the defect.
        // The defect was anchoring the PREVIOUS checkpoint beside a seal that did not happen —
        // which survives any amount of correct status code (review).
        org.mockito.Mockito.verify(anchors, org.mockito.Mockito.never())
                .anchor(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(anchors, org.mockito.Mockito.never())
                .retryUnsettled(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the scheduled endpoint never retries — that costs money and is called by hand")
    void aNoopRunDoesNotRetryAnything() throws Exception {
        // This was the other way round for one commit: the noop branch retried the rungs that
        // held nothing. But an unconfigured rung holds nothing for ever, so a default
        // deployment rewrote three NOT_CONFIGURED rows a minute, and a rung that had genuinely
        // failed was contacted on a one-minute timer with no backoff — on rung 3 that is a
        // timestamp token bought every minute. Retrying belongs behind a deliberate call.
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.anchor.AnchorService anchors =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorService.class);
        setField(controller, "anchorService", anchors);
        jp.aegif.nemaki.evidence.EvidenceLedgerService ledger =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerService.class);
        when(ledger.closeCheckpoint(anyString(), anyString())).thenReturn(
                java.util.Map.of("status", "noop", "message", "no entries since the last one"));
        setField(controller, "ledgerService", ledger);
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.latestCheckpoint(anyString())).thenReturn(
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT, null,
                        "2026-08-25T00:00:00Z"));
        setField(controller, "ledgerStore", store);

        Object response = AnchorController.class
                .getDeclaredMethod("checkpointAndAnchor", String.class)
                .invoke(controller, "bedroom");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getClass()
                .getMethod("getBody").invoke(response);

        assertEquals("noop", body.get("status"), body + "");
        org.mockito.Mockito.verify(anchors, org.mockito.Mockito.never())
                .anchor(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(anchors, org.mockito.Mockito.never())
                .retryUnsettled(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the deliberate endpoint retries THIS checkpoint and shows what came back")
    void theRetryEndpointPassesTheLatestCheckpointAndPublishesTheOutcome() throws Exception {
        // Closing and anchoring are one call, so a checkpoint that WAS sealed but whose anchor
        // failed had no way back: the seal cannot be redone, upgrade-pending only looks at
        // PENDING rows, and every later run again has nothing new to seal. The rung stayed
        // FAILED for ever. Re-anchoring the whole ladder is not the answer either — that mints
        // a second commitment for a rung that already settled — so only the empty ones are
        // retried, and AnchorService decides which those are.
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.anchor.AnchorService anchors =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorService.class);
        when(anchors.retryUnsettled(org.mockito.ArgumentMatchers.any())).thenReturn(
                new jp.aegif.nemaki.evidence.anchor.AnchorService.Outcome("bedroom", 5, ROOT,
                        java.util.List.of(), null));
        setField(controller, "anchorService", anchors);
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        jp.aegif.nemaki.evidence.EvidenceCheckpoint latest =
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT, null,
                        "2026-08-25T00:00:00Z");
        when(store.latestCheckpoint(anyString())).thenReturn(latest);
        setField(controller, "ledgerStore", store);

        Object response = AnchorController.class
                .getDeclaredMethod("retryUnsettled", String.class)
                .invoke(controller, "bedroom");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getClass()
                .getMethod("getBody").invoke(response);

        assertEquals("success", body.get("status"), body + "");
        // same(), not any(): any() matches null too, so passing null — retrying nothing at all
        // — would have satisfied the earlier version of this check.
        org.mockito.Mockito.verify(anchors)
                .retryUnsettled(org.mockito.ArgumentMatchers.same(latest));
        assertNotNull(body.get("anchor"),
                "the retry ran and the caller was shown nothing of what came back, so a "
                        + "refusal or a failed rung is invisible: " + body);
        assertNotNull(body.get("message"),
                "an empty receipt list has two very different causes and nothing said which");
        org.mockito.Mockito.verify(anchors, org.mockito.Mockito.never())
                .anchor(org.mockito.ArgumentMatchers.any());
    }

    private static final String ROOT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("an unwired receipt store is not an empty list of receipts")
    void anUnwiredReceiptStoreIsNotAnEmptyResult() throws Exception {
        // "We could not ask" is not "there are none". An empty list beside status:success reads
        // as "this checkpoint was never anchored" — a claim about the deployment made on the
        // strength of a missing bean.
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.EvidenceLedgerStore ledger =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(ledger.latestCheckpoint(anyString())).thenReturn(
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        null, "2026-08-25T00:00:00Z"));
        setField(controller, "ledgerStore", ledger);

        Object response = AnchorController.class.getDeclaredMethod("status", String.class)
                .invoke(controller, "bedroom");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getClass()
                .getMethod("getBody").invoke(response);

        assertNull(body.get("receipts"),
                "an unwired receipt store answered with an empty receipt list: " + body);
        assertNotNull(body.get("receiptsUnavailable"),
                "nothing said why the receipts are missing: " + body);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("an admin is not refused — the control")
    void anAdminGetsThrough() throws Exception {
        // Without this, gating everything shut unconditionally would pass the test above and
        // the API would be unusable.
        AnchorController controller = controllerFor(true);

        Method status = AnchorController.class.getDeclaredMethod("status", String.class);
        Object response = status.invoke(controller, "bedroom");
        HttpStatus code = (HttpStatus) response.getClass()
                .getMethod("getStatusCode").invoke(response);

        // The ledger store is unwired here, so SERVICE_UNAVAILABLE is the right answer — what
        // matters is that it is NOT the refusal.
        assertTrue(code != HttpStatus.FORBIDDEN,
                "an admin was refused; the gate no longer distinguishes who is asking");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, code,
                "an unwired ledger answered " + code + "; absence of the store must not read "
                        + "as an empty but successful result");
    }

    /** A receipt store that answers and holds nothing. */
    private static jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore emptyStore() {
        jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore empty =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
        when(empty.isActive()).thenReturn(true);
        when(empty.forCheckpoint(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        return empty;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> statusBodyWith(
            jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore receipts) throws Exception {
        return statusBodyWith(receipts, 9L);
    }

    /**
     * @param highest what the ledger reports as its highest sequence. A PARAMETER, not a static
     *        latch: the first version used one and left it set, so whether a later test saw 9
     *        or -1 depended on execution order.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> statusBodyWith(
            jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore receipts, long highest)
            throws Exception {
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.latestCheckpoint(anyString())).thenReturn(
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT, null,
                        "2026-08-25T00:00:00Z"));
        when(store.highestSequence(anyString())).thenReturn(highest);
        setField(controller, "ledgerStore", store);
        setField(controller, "receiptStore", receipts);
        Object response = AnchorController.class.getDeclaredMethod("status", String.class)
                .invoke(controller, "bedroom");
        return (java.util.Map<String, Object>) response.getClass().getMethod("getBody").invoke(response);
    }

    @Test
    @DisplayName("a SEALED but never-anchored checkpoint does not report zero exposure")
    void aSealedButUnanchoredCheckpointIsFullyExposed() throws Exception {
        // unanchoredEntries was `highest - latest.toSequence()` off the last SEALED checkpoint,
        // which says nothing about whether anything anchored it. With no rung configured, or
        // one that FAILED, that answered 0 while every entry was unanchored -- and this is the
        // single number an operator uses to size the window in which the ledger is still
        // quietly rewritable (p2-0 §0). No test named this field before.
        jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore empty =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
        when(empty.isActive()).thenReturn(true);
        when(empty.forCheckpoint(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());

        java.util.Map<String, Object> body = statusBodyWith(empty);

        // TEN, not nine. Sequences are 0-based, so highestSequence()==9 means ten entries --
        // and the first version of this assertion said 9 because that is what the code
        // produced. A lock written against the output pins the output, off-by-one included.
        assertEquals(10L, body.get("unanchoredEntries"),
                "a checkpoint nothing anchored reported " + body.get("unanchoredEntries")
                        + " entries exposed; the ledger holds ten and every one of them is held "
                        + "only by this database");
        assertNull(body.get("unanchoredEntriesRung"));
        // An empty ledger answers -1 from highestSequence; the exposure must not go negative.
        assertEquals(0L, statusBodyWith(emptyStore(), -1L).get("unanchoredEntries"),
                "an empty ledger reported a negative number of exposed entries");
        assertEquals(4L, body.get("entriesAfterLatestCheckpoint"),
                "the seal-relative count is gone, so the two facts can no longer be told apart");
    }

    @Test
    @DisplayName("an unanswerable receipt store does not report zero exposure either")
    void anUnreachableReceiptStoreDoesNotReportZeroExposure() throws Exception {
        // "We could not ask" is not "nothing is exposed" -- the rule the receipts list in this
        // same response already follows. Both store-unavailable arms, because the audit that
        // found this named "one arm of a fan-out was fixed and one arm was tested" as the
        // mechanic behind five separate defects.
        for (jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore store : new java.util.ArrayList<
                jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore>() {{
                    add(null);
                    jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore down =
                            mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
                    when(down.isActive()).thenReturn(false);
                    add(down);
                }}) {
            java.util.Map<String, Object> body = statusBodyWith(store);

            assertNull(body.get("unanchoredEntries"),
                    "a store that could not be asked produced a number for how much is exposed: "
                            + body.get("unanchoredEntries"));
            assertNotNull(body.get("unanchoredEntriesUnavailable"),
                    "nothing says why the exposure could not be computed");
        }
    }

    @Test
    @DisplayName("the rung quoted beside the exposure is the STRONGEST confirmed one")
    void theRungQuotedIsTheStrongestConfirmed() throws Exception {
        // The picker was called newestConfirmed and returned the first CONFIRMED row the store
        // yielded. CouchAnchorReceiptStore.forCheckpoint reads a view keyed by
        // (domain, toSequence) with NO time ordering, so "newest" was unsupported -- and with
        // two rungs settled it could name ATLAS_CATALOG, whose own enum comment says it must
        // not be presented as a time proof at all, as the rung backing unanchoredEntries.
        //
        // The catalog rung is deliberately FIRST in the list, so a picker that takes the first
        // CONFIRMED row fails this.
        // Minted through the real targets: AnchorReceipt.confirmed is package-private, and a
        // final class cannot be mocked here. CatalogAnchorTarget produces a genuine
        // NOT_A_TIME_PROOF receipt; the codec round-trip supplies the stronger one, so both are
        // objects the product actually builds rather than test-only shapes.
        jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget minter =
                new jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget();
        minter.setEnabled(true);
        minter.setPublisher(digest -> "entity-1");
        jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt catalog = minter.anchor(ROOT);
        java.util.Map<String, Object> asTsa = jp.aegif.nemaki.rest.purview.anchor
                .AnchorReceiptCodec.toDocument(catalog);
        asTsa.put("kind", "RFC3161_TSA");
        asTsa.put("timeSemantics", "BIDIRECTIONAL_WITHIN_ACCURACY");
        jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt tsa =
                jp.aegif.nemaki.rest.purview.anchor.AnchorReceiptCodec.fromDocument(asTsa);

        jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore store =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
        when(store.isActive()).thenReturn(true);

        // BOTH orders. Asserting only [catalog, tsa] distinguishes the fix from
        // first-CONFIRMED-wins, and passes equally for last-CONFIRMED-wins -- which is exactly
        // as arbitrary as what was replaced, since the store's view has no ordering.
        for (List<jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt> order
                : List.of(List.of(catalog, tsa), List.of(tsa, catalog))) {
            when(store.forCheckpoint(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(order);

            java.util.Map<String, Object> body = statusBodyWith(store);

            assertEquals("RFC3161_TSA", body.get("unanchoredEntriesRung"),
                    "with the rungs in this order the exposure number is quoted beside "
                            + body.get("unanchoredEntriesRung")
                            + ", not the strongest anchor that covers it");
        }
    }

    @Test
    @DisplayName("an OLDER confirmed checkpoint still counts as cover")
    void anOlderConfirmedCheckpointStillCovers() throws Exception {
        // The exposure was measured only from the LATEST checkpoint's receipts. With
        // checkpoint 5 confirmed, checkpoint 10 sealed but pending and the head at 12, every
        // entry was reported exposed -- the safe direction, but not what the field says it
        // counts, and it never shrinks when an older anchor settles, which reads to an operator
        // as anchoring not working at all.
        jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget minter =
                new jp.aegif.nemaki.rest.purview.anchor.CatalogAnchorTarget();
        minter.setEnabled(true);
        minter.setPublisher(digest -> "entity-1");
        jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt older = minter.anchor(ROOT);

        jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore store =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
        when(store.isActive()).thenReturn(true);
        // Nothing settled on the latest checkpoint...
        when(store.forCheckpoint(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        // ...but an older one, through sequence 5, has.
        when(store.confirmed(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.PendingReceipt(
                                "bedroom", 5L, older)));

        java.util.Map<String, Object> body = statusBodyWith(store, 12L);

        assertEquals(7L, body.get("unanchoredEntries"),
                "entries 6..12 are exposed and " + body.get("unanchoredEntries")
                        + " was reported; an older confirmed anchor still covers its own span");
        assertEquals(5L, body.get("unanchoredEntriesThroughSequence"));
        assertEquals("ATLAS_CATALOG", body.get("unanchoredEntriesRung"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a REFUSED anchor is not answered 200 success")
    void aRefusedAnchorIsNotSuccess() throws Exception {
        // `status: "success"` is written before anything is attempted, and AnchorService reports
        // refusal in its RETURNED Outcome rather than by throwing. So a refused anchor came back
        // 200 success -- and AnchorService.store is a plain setter with no @Autowired, so a
        // mis-wired bean refuses every call while every call answers success.
        //
        // The comment in this method says this exact defect was fixed. It was: for
        // closeCheckpoint's returned map. The second producer in the same method, following the
        // same convention, was not. The sibling endpoint has always mapped it.
        AnchorController controller = controllerFor(true);
        jp.aegif.nemaki.evidence.EvidenceLedgerService ledger =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerService.class);
        when(ledger.closeCheckpoint(anyString(), anyString()))
                .thenReturn(java.util.Map.of("status", "success", "toSequence", 5L));
        setField(controller, "ledgerService", ledger);
        jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
        when(store.latestCheckpoint(anyString())).thenReturn(
                jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT, null,
                        "2026-08-25T00:00:00Z"));
        setField(controller, "ledgerStore", store);
        jp.aegif.nemaki.evidence.anchor.AnchorService anchors =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorService.class);
        when(anchors.anchor(org.mockito.ArgumentMatchers.any())).thenReturn(
                new jp.aegif.nemaki.evidence.anchor.AnchorService.Outcome("bedroom", 5, ROOT,
                        List.of(), "the receipt store is not wired, so nothing was anchored"));
        setField(controller, "anchorService", anchors);

        Object response = AnchorController.class
                .getDeclaredMethod("checkpointAndAnchor", String.class)
                .invoke(controller, "bedroom");
        HttpStatus code = (HttpStatus) response.getClass().getMethod("getStatusCode")
                .invoke(response);
        java.util.Map<String, Object> body = (java.util.Map<String, Object>)
                response.getClass().getMethod("getBody").invoke(response);

        assertTrue(code != HttpStatus.OK,
                "a refused anchor answered " + code + "; the caller reads that as anchored");
        org.junit.jupiter.api.Assertions.assertNotEquals("success", body.get("status"),
                "the outer status says success over an inner refusal: " + body);
    }

    @Test
    @DisplayName("receipts the store could not read are not silently missing from /status")
    void droppedReceiptRowsAreDisclosed() throws Exception {
        // The store drops a row it cannot decode and counts it -- the counter exists precisely
        // so "a row that cannot be read is not an absent receipt" survives the read. Both of
        // AnchorService's verbs consult it. /status, the one an operator actually looks at, did
        // not: the list presented itself as complete and the rung whose receipt was dropped read
        // as unanchored below it.
        jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore lossy =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore.class);
        when(lossy.isActive()).thenReturn(true);
        when(lossy.forCheckpoint(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        when(lossy.unreadableCount()).thenReturn(2);

        java.util.Map<String, Object> body = statusBodyWith(lossy);

        assertEquals(2, body.get("receiptsUnreadable"),
                "rows the store could not decode are missing from the response with no trace: "
                        + body);
        assertTrue(String.valueOf(body.get("receiptsUnavailable")).contains("NOT a finding"),
                "the disclosure does not say what it is not: " + body);
    }

    @Test
    @DisplayName("a clean read carries no unreadable disclosure — the control")
    void aCleanReadIsNotCalledLossy() throws Exception {
        // Without this, emitting the disclosure unconditionally would satisfy the test above and
        // make every status response claim receipts had been lost.
        java.util.Map<String, Object> body = statusBodyWith(emptyStore());

        assertNull(body.get("receiptsUnreadable"),
                "a store that dropped nothing was reported as lossy: " + body);
        assertNull(body.get("receiptsUnavailable"), String.valueOf(body));
    }

    @Test
    @DisplayName("/status answers a ledger read it could not make, rather than a bare 500")
    void statusReportsALedgerReadItCouldNotMake() throws Exception {
        // The store was changed to REFUSE a read it could not make rather than answer "there is
        // nothing". This method never wrapped either call, so the refusal became a bare 500
        // whose body carries neither `limits` nor the reason — on the one endpoint whose whole
        // job is to say what is and is not anchored. Its sibling /retry-unsettled has wrapped
        // the same call all along: one arm of one class.
        //
        // Both reads are driven, because they are separate calls and a wrap on one leaves the
        // other bare.
        // THREE arms, because there are three throwing reads and each has its own wrap:
        // latestCheckpoint; highestSequence on the no-checkpoint path; and highestSequence on
        // the path where a checkpoint EXISTS. The first version drove only the first two — its
        // highestSequence fixture forced latestCheckpoint to null — so removing the third wrap
        // left it green. Two calls to the same method are two arms when they sit in different
        // branches.
        for (String arm : new String[] { "checkpoint", "head-without-checkpoint",
                "head-with-checkpoint" }) {
            AnchorController controller = controllerFor(true);
            jp.aegif.nemaki.evidence.EvidenceLedgerStore store =
                    mock(jp.aegif.nemaki.evidence.EvidenceLedgerStore.class);
            switch (arm) {
                case "checkpoint" -> when(store.latestCheckpoint(anyString()))
                        .thenThrow(new IllegalStateException("the view did not answer"));
                case "head-without-checkpoint" -> {
                    when(store.latestCheckpoint(anyString())).thenReturn(null);
                    when(store.highestSequence(anyString()))
                            .thenThrow(new IllegalStateException("the view did not answer"));
                }
                default -> {
                    when(store.latestCheckpoint(anyString())).thenReturn(
                            jp.aegif.nemaki.evidence.EvidenceCheckpoint.of("bedroom", 0, 5, ROOT,
                                    null, "2026-08-25T00:00:00Z"));
                    when(store.highestSequence(anyString()))
                            .thenThrow(new IllegalStateException("the view did not answer"));
                }
            }
            setField(controller, "ledgerStore", store);

            Object response = AnchorController.class.getDeclaredMethod("status", String.class)
                    .invoke(controller, "bedroom");
            HttpStatus code = (HttpStatus) response.getClass().getMethod("getStatusCode")
                    .invoke(response);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> body = (java.util.Map<String, Object>)
                    response.getClass().getMethod("getBody").invoke(response);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, code, arm + ": " + body);
            assertNotNull(body.get("limits"),
                    arm + ": the refusal carries no statement of what a status answer "
                            + "establishes: " + body);
            assertTrue(String.valueOf(body.get("message")).contains("NOT a statement"),
                    arm + ": the refusal does not say what it is not: " + body);
        }
    }

    @Test
    @DisplayName("upgradePending's 'could not ask' reaches the RESPONSE as 503, not as success")
    void anUnaskableUpgradeIsNotAnEmptyOne() throws Exception {
        // Pinned in the service and nowhere else. The harm the service test names happens HERE:
        // reverting this arm answers 200 with status:"success", upgradedCount:0 and the note
        // "nothing had settled yet ... do not re-anchor", so a deployment whose receipt store
        // is unreachable is advised to leave a commitment unupgraded for ever — from a question
        // nobody managed to ask.
        jp.aegif.nemaki.evidence.anchor.AnchorService anchors =
                mock(jp.aegif.nemaki.evidence.anchor.AnchorService.class);
        when(anchors.upgradePending(anyString(), anyInt())).thenReturn(
                new jp.aegif.nemaki.evidence.anchor.AnchorService.Upgraded(java.util.List.of(),
                        "the receipt store could not be read, so which rungs have settled is "
                                + "unknown"));
        AnchorController controller = controllerFor(true);
        setField(controller, "anchorService", anchors);

        org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
                controller.upgradePending("bedroom", 10);
        java.util.Map<String, Object> body = response.getBody();

        assertEquals(503, response.getStatusCode().value(),
                "a store that could not be read was answered as a completed upgrade: " + body);
        assertEquals("unavailable", body.get("status"), String.valueOf(body));
        assertNotNull(body.get("limits"),
                "the refusal carries no statement of what it does not establish: " + body);
    }
}
