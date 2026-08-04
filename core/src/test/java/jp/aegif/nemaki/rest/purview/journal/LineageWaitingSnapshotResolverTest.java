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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.journal.LineageWaitingSnapshotResolver.Candidate;
import jp.aegif.nemaki.rest.purview.journal.LineageWaitingSnapshotResolver.Resolution;

/**
 * Corruption is not incompleteness, and "whichever row came first" is not a choice.
 *
 * <p>Both distinctions matter for the same reason: the historical builder writes the catalog's
 * only remaining record of an object nobody can look at any more. Material it was not entitled
 * to use must not reach it, and a verdict it could not establish must not be terminal.
 */
public class LineageWaitingSnapshotResolverTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/objects/doc-1";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;

    private static LineageCatalogObligation obligation() {
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN), TARGET, REPO, KIND, QN,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok", 9999L, 0L, 0, 1L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    private static LineageWaitingSnapshot snapshot(String name,
            LineageSourceDisposition disposition) {
        return LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", name),
                disposition, 2);
    }

    private static LineageWaitingSnapshotResolver resolverOver(List<Candidate> candidates) {
        return new LineageWaitingSnapshotResolver(taskKey -> candidates);
    }

    @Test
    @DisplayName("one matching candidate resolves")
    public void singleCandidate() {
        Resolution resolution = resolverOver(List.of(
                new Candidate("d-1", snapshot("a.txt", LineageSourceDisposition.SOURCE_PURGED))))
                .resolve(obligation());

        LineageWaitingSnapshot found =
                assertInstanceOf(Resolution.Found.class, resolution).snapshot();
        assertEquals(QN, found.catalogQualifiedName());
        assertEquals(LineageSourceDisposition.SOURCE_PURGED, found.sourceDisposition());
    }

    /** A wait that has ended is not corruption; there is simply nothing to rebuild from. */
    @Test
    @DisplayName("no waiting event is its own answer, not corruption")
    public void noWaitingEvent() {
        assertInstanceOf(Resolution.NoWaitingEvent.class,
                resolverOver(List.of()).resolve(obligation()));
    }

    @Test
    @DisplayName("a query failure is corruption, never an empty result")
    public void queryFailureIsCorrupt() {
        LineageWaitingSnapshotResolver resolver = new LineageWaitingSnapshotResolver(taskKey -> {
            throw new IllegalStateException("view unavailable");
        });

        Resolution resolution = resolver.resolve(obligation());
        assertInstanceOf(Resolution.Corrupt.class, resolution);
    }

    /**
     * Each mismatch separately: an entity rebuilt from another repository's snapshot would be
     * wrong in a way nothing downstream could detect.
     */
    @Test
    @DisplayName("a subject mismatch in any part is corruption")
    public void subjectMismatchIsCorrupt() {
        assertInstanceOf(Resolution.Corrupt.class, resolverOver(List.of(new Candidate("d-1",
                LineageWaitingSnapshot.of("atlas", REPO, KIND, QN, Map.of(),
                        LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));

        assertInstanceOf(Resolution.Corrupt.class, resolverOver(List.of(new Candidate("d-1",
                LineageWaitingSnapshot.of(TARGET, "canopy", KIND, QN, Map.of(),
                        LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));

        assertInstanceOf(Resolution.Corrupt.class, resolverOver(List.of(new Candidate("d-1",
                LineageWaitingSnapshot.of(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN, Map.of(),
                        LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));

        assertInstanceOf(Resolution.Corrupt.class, resolverOver(List.of(new Candidate("d-1",
                LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN + "-other", Map.of(),
                        LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));
    }

    /** Same inputs, same snapshot — not whichever row the view happened to return first. */
    @Test
    @DisplayName("several agreeing candidates resolve deterministically")
    public void severalAgreeingCandidates() {
        LineageWaitingSnapshot same = snapshot("a.txt", LineageSourceDisposition.SOURCE_PURGED);
        Resolution forward = resolverOver(List.of(
                new Candidate("d-1", same), new Candidate("d-2", same))).resolve(obligation());
        Resolution reverse = resolverOver(List.of(
                new Candidate("d-2", same), new Candidate("d-1", same))).resolve(obligation());

        assertEquals(assertInstanceOf(Resolution.Found.class, forward).snapshot().evidenceDigest(),
                assertInstanceOf(Resolution.Found.class, reverse).snapshot().evidenceDigest());
    }

    /**
     * Same subject, different content. Choosing either would silently make one event's record
     * of the object the one that survives into the catalog forever.
     */
    @Test
    @DisplayName("candidates that disagree about content are corruption, not a choice")
    public void disagreeingCandidatesAreCorrupt() {
        Resolution resolution = resolverOver(List.of(
                new Candidate("d-1", snapshot("a.txt", LineageSourceDisposition.SOURCE_PURGED)),
                new Candidate("d-2", snapshot("b.txt", LineageSourceDisposition.SOURCE_PURGED))))
                .resolve(obligation());

        assertInstanceOf(Resolution.Corrupt.class, resolution);
    }

    @Test
    @DisplayName("a candidate with no snapshot is corruption")
    public void nullSnapshotIsCorrupt() {
        assertInstanceOf(Resolution.Corrupt.class,
                resolverOver(java.util.Arrays.asList(new Candidate("d-1", null)))
                        .resolve(obligation()));
    }

    /** The evidence digest binds a verdict to the material it was reached from. */
    @Test
    @DisplayName("the evidence digest changes with the content and not with the order")
    public void evidenceDigestBindsContent() {
        LineageWaitingSnapshot one = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                new java.util.LinkedHashMap<>(Map.of("a", "1", "b", "2")),
                LineageSourceDisposition.SOURCE_PURGED, 2);
        LineageWaitingSnapshot reordered = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                new java.util.LinkedHashMap<>(Map.of("b", "2", "a", "1")),
                LineageSourceDisposition.SOURCE_PURGED, 2);
        LineageWaitingSnapshot different = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                Map.of("a", "1", "b", "3"), LineageSourceDisposition.SOURCE_PURGED, 2);

        assertEquals(one.evidenceDigest(), reordered.evidenceDigest());
        assertFalse(one.evidenceDigest().equals(different.evidenceDigest()));
    }

    @Test
    @DisplayName("mandatory attributes are checked structurally, blank counting as absent")
    public void mandatoryAttributes() {
        LineageWaitingSnapshot complete = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                Map.of("name", "a.txt", "versionLabel", "1.0"),
                LineageSourceDisposition.SOURCE_PURGED, 2);
        LineageWaitingSnapshot blank = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                Map.of("name", "   "), LineageSourceDisposition.SOURCE_PURGED, 2);

        assertTrue(complete.hasAll(List.of("name", "versionLabel")));
        assertFalse(complete.hasAll(List.of("name", "contentHash")));
        assertFalse(blank.hasAll(List.of("name")));
        assertTrue(complete.hasAll(null));
    }

    /** The snapshot is logged and put in reports; its content is the object's own. */
    @Test
    @DisplayName("the description carries no qualified name and no attribute value")
    public void descriptionLeaksNothing() {
        String secretish = "nemaki://bedroom/external/s3%3A%2F%2Fbucket%2Fkey";
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO,
                EndpointKind.EXTERNAL_ASSET, secretish, Map.of("externalStableKey", secretish),
                LineageSourceDisposition.SOURCE_PURGED, 2);

        String description = snapshot.toString();
        assertFalse(description.contains(secretish));
        assertTrue(description.contains("<redacted:"));
    }

    /** Immutable: the builder must not be able to add a field on the way to publishing. */
    @Test
    @DisplayName("attributes cannot be modified after construction")
    public void attributesAreImmutable() {
        java.util.Map<String, Object> mutable = new java.util.LinkedHashMap<>();
        mutable.put("name", "a.txt");
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                mutable, LineageSourceDisposition.SOURCE_PURGED, 2);

        mutable.put("name", "changed");
        assertEquals("a.txt", snapshot.attributes().get("name"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.attributes().put("injected", "x"));
    }

    /** Only a purged source may be rebuilt. */
    @Test
    @DisplayName("disposition decides, and catalog-absent is not one of its inputs")
    public void dispositionGovernsRebuild() {
        assertTrue(LineageSourceDisposition.SOURCE_PURGED.permitsHistoricalEntity());
        assertFalse(LineageSourceDisposition.SOURCE_EXISTS.permitsHistoricalEntity());
        assertFalse(LineageSourceDisposition.SOURCE_UNKNOWN.permitsHistoricalEntity());
    }
}
