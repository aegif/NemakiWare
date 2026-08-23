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

import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField.V2Home;
import jp.aegif.nemaki.rest.purview.journal.EndpointKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence table has to be worth trusting, or it is decoration.
 *
 * <p>Its job is to make "added to v1, forgot v2" impossible rather than merely detectable. That
 * only holds if the table is total (every v1 key appears), if every declared v2 attribute is one
 * the endpoint kind will actually accept, and if a {@code NONE} entry states a decision rather
 * than a placeholder — {@code none("TODO")} would otherwise turn the whole mechanism into a
 * formality.
 */
class CaptureEvidenceFieldTest {

    @Test
    @DisplayName("no two facts share a v1 key")
    void v1KeysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            assertTrue(seen.add(field.v1Key()),
                    "duplicate v1 key '" + field.v1Key() + "' — one of the two would silently "
                            + "overwrite the other in the snapshot map");
        }
    }

    @Test
    @DisplayName("every v2 attribute is one the endpoint kind accepts")
    void declaredAttributesAreAllowed() {
        // Declaring an attribute the kind does not allow is not a lint failure at runtime: the
        // LineageEndpoint constructor throws, LineageFact construction fails, and the ingest
        // reports that it lost the evidence entirely.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            V2Home home = field.v2Home();
            EndpointKind kind = switch (home.placement()) {
                case INPUT_ATTRIBUTE -> EndpointKind.EXTERNAL_ASSET;
                case OUTPUT_ATTRIBUTE -> EndpointKind.CMIS_DOCUMENT;
                case IDENTITY, NONE, PROCESS_FACT, JOURNAL_FACT, EVENT_ATTRIBUTION -> null;
            };
            if (home.placement() == V2Home.Placement.PROCESS_FACT) {
                // The one-table principle across the layering boundary: the journal owns a
                // closed key set (it must not import this table), and this pin is what keeps
                // the two from drifting (P1-1(e) §2.2, Codex H1).
                assertTrue(jp.aegif.nemaki.rest.purview.journal.LineageEventV2.PROCESS_FACT_KEYS
                                .contains(field.v1Key()),
                        field + " is a PROCESS_FACT but the event's closed key set does not "
                                + "know '" + field.v1Key() + "' — construction would throw");
                assertTrue(field.disclosure() == CaptureEvidenceField.Disclosure.EXTERNAL_OK,
                        field + " is INTERNAL_ONLY but placed in the compartment the catalog "
                                + "sink reads");
            }
            if (home.placement() == V2Home.Placement.JOURNAL_FACT) {
                assertTrue(jp.aegif.nemaki.rest.purview.journal.LineageEventV2.JOURNAL_FACT_KEYS
                                .contains(field.v1Key()),
                        field + " is a JOURNAL_FACT but the event's closed key set does not "
                                + "know '" + field.v1Key() + "'");
            }
            if (kind == null) {
                continue;
            }
            assertTrue(kind.allowedAttributes().contains(home.attributeName()),
                    field + " declares '" + home.attributeName() + "' on " + kind
                            + ", which does not allow it. Allowed: " + kind.allowedAttributes());
        }
    }

    @Test
    @DisplayName("a fact that is not carried by v2 says why, in a sentence someone can act on")
    void everyAbsenceHasARealReason() {
        // "Not carried" and "not got to it yet" read identically from an absent entry. A reason
        // that is a placeholder is the same as no reason at all.
        List<String> placeholders = List.of("todo", "tbd", "fixme", "xxx", "later", "n/a", "none");
        List<String> weak = new ArrayList<>();

        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            V2Home home = field.v2Home();
            if (home.placement() != V2Home.Placement.NONE
                    && home.placement() != V2Home.Placement.IDENTITY) {
                // Carried placements need no absence reason — the compartment IS the decision.
                continue;
            }
            String reason = home.reason();
            assertNotNull(reason, field + " has no reason");
            String lower = reason.toLowerCase(java.util.Locale.ROOT);
            if (reason.length() < 40 || placeholders.stream().anyMatch(lower::contains)) {
                weak.add(field + " → \"" + reason + "\"");
            }
        }

        assertTrue(weak.isEmpty(), "these reasons are placeholders rather than decisions: " + weak);
    }

    @Test
    @DisplayName("an attribute placement carries a name, and the others do not")
    void placementAndNameAgree() {
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            V2Home home = field.v2Home();
            switch (home.placement()) {
                case INPUT_ATTRIBUTE, OUTPUT_ATTRIBUTE -> {
                    assertNotNull(home.attributeName(), field + " has no attribute name");
                    assertTrue(home.reason() == null,
                            field + " has both an attribute and a reason — the reason would never "
                                    + "be read, so it would drift");
                }
                case IDENTITY, NONE -> assertTrue(home.attributeName() == null,
                        field + " names an attribute but is not placed as one");
            }
        }
    }

    @Test
    @DisplayName("IDENTITY is not used as a softer NONE")
    void identityMeansCarried() {
        // The distinction matters for the claim being made: IDENTITY says v2 DOES carry the fact,
        // inside the endpoint's name. Using it for something v2 drops would overstate what
        // survives the write flip.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            if (field.v2Home().placement() != V2Home.Placement.IDENTITY) {
                continue;
            }
            assertTrue(field.v2Home().carriedByV2(), field + " must count as carried");
            assertTrue(field.v2Home().reason().contains("externalStableKey"),
                    field + " claims IDENTITY but does not say which identity carries it: "
                            + field.v2Home().reason());
        }
    }

    @Test
    @DisplayName("the facts this work claims to rescue really do have a v2 home")
    void thePromisedFactsAreCarried() {
        // The design's section 0 names these. Without this, the table could be total and
        // internally consistent while the headline claim is false.
        for (CaptureEvidenceField promised : List.of(
                CaptureEvidenceField.CONTENT_STORED,
                CaptureEvidenceField.CONTENT_HASH,
                CaptureEvidenceField.CONTENT_HASH_ALGORITHM,
                CaptureEvidenceField.CONTENT_HASH_UNAVAILABLE,
                CaptureEvidenceField.SOURCE_SYSTEM,
                CaptureEvidenceField.SOURCE_OBJECT_ID,
                CaptureEvidenceField.SOURCE_OBJECT_TYPE)) {
            assertTrue(promised.v2Home().carriedByV2(),
                    promised + " is named in the design as surviving the write flip, but the "
                            + "table says it does not");
        }
    }

    @Test
    @DisplayName("the facts this work does NOT rescue are marked so — the control")
    void theUnrescuedFactsAreDeclared() {
        // Without this, marking everything as carried would pass the test above while making the
        // table a claim that nothing checks.
        // P1-1(e) rescued the rest (processFacts / journalFacts / typed attribution). What
        // stays out is the pair (b) §6 kept out of every delivered or journaled v2 home on
        // privacy grounds — personal names with no retention rule anywhere but the journal's.
        for (CaptureEvidenceField absent : List.of(
                CaptureEvidenceField.CHAT_PARTICIPANTS,
                CaptureEvidenceField.CHAT_CHANNEL_NAME)) {
            assertFalse(absent.v2Home().carriedByV2(),
                    absent + " is documented as NOT carried by v2; if that changed, the design "
                            + "and the roadmap have to change with it");
        }
        // And the rescues themselves are pinned, so reverting one is loud:
        assertTrue(CaptureEvidenceField.TARGET_FOLDER_ID.v2Home().carriedByV2(),
                "the REQUIRED Process attribute's supply was the flip's open prerequisite");
        assertTrue(CaptureEvidenceField.EXECUTED_BY.v2Home().placement()
                        == V2Home.Placement.EVENT_ATTRIBUTION,
                "the actor is typed attribution, not a map entry (Codex H2)");
    }
}
