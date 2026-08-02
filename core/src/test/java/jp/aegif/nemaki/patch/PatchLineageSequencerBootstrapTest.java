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
package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

/**
 * The bootstrap patch's decision rules (v2.3.18 ③): validate-never-overwrite for existing
 * documents, seed only where no history exists, throw (so the patch re-runs) on anything
 * corrupt or ambiguous.
 */
public class PatchLineageSequencerBootstrapTest {

    private static final String REPO = "bedroom";

    private Cloudant cloudant;

    @BeforeEach
    void setUp() {
        cloudant = mock(Cloudant.class, Mockito.RETURNS_DEEP_STUBS);
    }

    private void documentIs(String docId, Map<String, Object> properties) {
        Document doc = new Document();
        doc.setId(docId);
        doc.setRev("1-x");
        properties.forEach(doc::put);
        when(cloudant.getDocument(org.mockito.ArgumentMatchers.argThat(
                (GetDocumentOptions o) -> o != null && docId.equals(o.docId())))
                .execute().getResult()).thenReturn(doc);
    }

    private void documentAbsent(String docId) {
        when(cloudant.getDocument(org.mockito.ArgumentMatchers.argThat(
                (GetDocumentOptions o) -> o != null && docId.equals(o.docId())))
                .execute())
                .thenThrow(mock(NotFoundException.class));
    }

    private void watermarkIs(long max) {
        var row = mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(row.getValue()).thenReturn(Map.of("max", max, "sum", max, "count", 1L));
        var result = mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of(row));
        when(cloudant.postView(any(com.ibm.cloud.cloudant.v1.model.PostViewOptions.class))
                .execute().getResult()).thenReturn(result);
    }

    private void watermarkViewMissing() {
        when(cloudant.postView(any(com.ibm.cloud.cloudant.v1.model.PostViewOptions.class))
                .execute())
                .thenThrow(mock(NotFoundException.class));
    }

    private void historyIs(boolean present) {
        var result = mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(result.getDocs()).thenReturn(present
                ? java.util.List.of(new Document())
                : java.util.List.of());
        when(cloudant.postFind(any(PostFindOptions.class)).execute().getResult())
                .thenReturn(result);
    }

    @Test
    public void aValidExistingLeaseIsPreservedNeverOverwritten() {
        documentIs("lineage_sequencer_lease:" + REPO, Map.of(
                "type", "lineage_sequencer_lease", "generation", 7L));
        Patch_LineageSequencerBootstrap.ensureLease(cloudant, REPO);
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    @Test
    public void aCorruptLeaseThrowsSoThePatchRerunsInsteadOfReseeding() {
        documentIs("lineage_sequencer_lease:" + REPO, Map.of(
                "type", "lineage_sequencer_lease", "generation", "garbage"));
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureLease(cloudant, REPO));
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    /** Every acquire-relied field is validated, not just the generation. */
    @Test
    public void aLeaseWithGarbageOwnerOrExpiryIsCorrupt() {
        documentIs("lineage_sequencer_lease:" + REPO, Map.of(
                "type", "lineage_sequencer_lease", "generation", 3L,
                "owner", 12345));
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureLease(cloudant, REPO));

        setUp();
        documentIs("lineage_sequencer_lease:" + REPO, Map.of(
                "type", "lineage_sequencer_lease", "generation", 3L,
                "owner", "node-a", "expiresAt", "not-a-timestamp"));
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureLease(cloudant, REPO),
                "an unparseable expiry is held-forever — no acquire can ever free it");
    }

    @Test
    public void anAbsentLeaseIsCreatedAtGenerationZero() {
        documentAbsent("lineage_sequencer_lease:" + REPO);
        Patch_LineageSequencerBootstrap.ensureLease(cloudant, REPO);
        verify(cloudant).putDocument(org.mockito.ArgumentMatchers.argThat(
                (PutDocumentOptions o) -> {
                    Object generation = o.document().getProperties().get("generation");
                    return "lineage_sequencer_lease:bedroom".equals(o.docId())
                            && generation instanceof Long g && g == 0L;
                }));
    }

    @Test
    public void aFreshRepositoryGetsACounterSeededAtZero() {
        documentAbsent("lineage_seq:" + REPO);
        historyIs(false);
        Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO);
        verify(cloudant).putDocument(org.mockito.ArgumentMatchers.argThat(
                (PutDocumentOptions o) -> "lineage_seq:bedroom".equals(o.docId())));
    }

    /** History with a missing counter is a rewind in progress — seeding would violate I-2. */
    @Test
    public void aMissingCounterWithHistoryRefusesToSeed() {
        documentAbsent("lineage_seq:" + REPO);
        historyIs(true);
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    @Test
    public void aCorruptCounterThrowsForManualRecovery() {
        documentIs("lineage_seq:" + REPO, Map.of(
                "type", "lineage_sequence", "seq", -5L));
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    /** A well-formed but rewound counter (restored backup) must fail, not pass as patched. */
    @Test
    public void aRewoundCounterIsRefusedAgainstTheWatermark() {
        documentIs("lineage_seq:" + REPO, Map.of(
                "type", "lineage_sequence", "seq", 5L));
        watermarkIs(100L);
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
    }

    @Test
    public void aCounterAtOrAboveTheWatermarkIsAccepted() {
        documentIs("lineage_seq:" + REPO, Map.of(
                "type", "lineage_sequence", "seq", 100L));
        watermarkIs(100L);
        Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO);
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    /** Cursors are history: a cursor-only repository must not be seeded at zero. */
    @Test
    public void aCursorOnlyRepositoryRefusesSeeding() {
        documentAbsent("lineage_seq:" + REPO);
        historyIs(true); // the $in selector includes projection_cursor
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
        verify(cloudant, never()).putDocument(any(PutDocumentOptions.class));
    }

    /** A null view answer is abnormal — never "watermark 0, patch successful". */
    @Test
    public void aNullWatermarkAnswerDefersThePatch() {
        documentIs("lineage_seq:" + REPO, Map.of(
                "type", "lineage_sequence", "seq", 5L));
        when(cloudant.postView(any(com.ibm.cloud.cloudant.v1.model.PostViewOptions.class))
                .execute().getResult()).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
    }

    /** With the view undeployed and history present, the counter cannot be validated. */
    @Test
    public void anUndeployedWatermarkViewWithHistoryDefersThePatch() {
        documentIs("lineage_seq:" + REPO, Map.of(
                "type", "lineage_sequence", "seq", 5L));
        watermarkViewMissing();
        historyIs(true);
        assertThrows(IllegalStateException.class,
                () -> Patch_LineageSequencerBootstrap.ensureCounter(cloudant, REPO));
    }
}
