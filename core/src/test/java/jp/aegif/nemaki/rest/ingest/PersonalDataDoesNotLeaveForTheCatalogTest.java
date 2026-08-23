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

import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField.Disclosure;
import jp.aegif.nemaki.rest.purview.payload.CatalogPropertyMappingResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facts declared personal data must not leave for an external catalogue, by either door.
 *
 * <h2>Why two doors</h2>
 *
 * <p>Door 1 is the lineage sink, which copies the v1 snapshot into its outbound payload — that
 * one was genuinely open and is now filtered, with a payload-building test.
 *
 * <p>Door 2 is narrower than this class first claimed. The ingest writes chat facts into
 * ASPECTS, not {@code subTypeProperties}, and {@code appendCustomPropertyValues} reads only
 * {@code subTypeProperties} — so a mapping for {@code nemaki:chatParticipants} projected null
 * even before the ban (external review). What the ban actually closes is the residual route:
 * an admin-defined PRIMARY subtype declaring the same property id would land its values in
 * {@code subTypeProperties}, and a mapping would then carry them out. The rejection is enforced
 * both when mappings are parsed (covering ones saved before this rule, restores, hand edits)
 * and at the payload boundary, and {@code CatalogPropertyMappingResolver}'s own javadoc already
 * says a custom mapping "must not be a second door" to a withheld value.
 *
 * <h2>What this does not establish</h2>
 *
 * <p>That personal names are gone from the product. {@code nemaki:chatContextMetadata} is
 * fulltext-indexed and its properties are queryable, and the Solr indexer copies every aspect
 * value — so participants remain searchable on a default deployment, which withholds nothing
 * from the catalogue. This closes the catalogue routes only.
 */
class PersonalDataDoesNotLeaveForTheCatalogTest {

    @Test
    @DisplayName("every fact declares whether it may leave — there is no default")
    void everyFactDeclaresItsDisclosure() {
        // The constructor requires it, so a field cannot be added without a decision. This
        // guards the weaker failure: someone reintroducing a defaulting overload.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            assertTrue(field.disclosure() != null,
                    field.v1Key() + " does not say whether it may leave for a catalogue");
        }
    }

    @Test
    @DisplayName("the chat facts that carry people are withheld from the sink")
    void personalChatFactsAreWithheld() {
        Set<String> withheld = CaptureEvidenceField.internalOnlyV1Keys();

        assertTrue(withheld.contains("chat.participants"),
                "the participants of a captured conversation are the point of this: " + withheld);
        assertTrue(withheld.contains("chat.channelName"),
                "in a direct message the channel name is the other person's name: " + withheld);
        assertTrue(withheld.contains("chat.selectionReason"), withheld.toString());
        assertTrue(withheld.contains("chat.evidenceScope"), withheld.toString());
        assertTrue(withheld.contains("chat.captureWindowStart"),
                "the window is unvalidated caller text on the path that reaches the snapshot, so "
                        + "the rule that withholds selectionReason has to withhold it too: "
                        + withheld);
    }

    @Test
    @DisplayName("identifiers, digests and machine state still go — the control")
    void theRestStillGoes() {
        // Without this, marking everything INTERNAL_ONLY would satisfy the test above and empty
        // the catalogue of the lineage it exists to show.
        Set<String> withheld = CaptureEvidenceField.internalOnlyV1Keys();

        assertFalse(withheld.contains("contentHash"), withheld.toString());
        assertFalse(withheld.contains("sourceSystem"), withheld.toString());
        assertFalse(withheld.contains("chat.channelId"),
                "the channel id is inside the endpoint's qualified name, so withholding the "
                        + "attribute would hide the key and ship the value anyway: " + withheld);
    }

    @Test
    @DisplayName("a fact inside the endpoint identity cannot be declared INTERNAL_ONLY")
    void identityFactsCannotBeWithheld() {
        // The fail-open this forbids: the key would vanish from the attribute map while the value
        // travelled on in the qualifiedName, and the table would read as though it were withheld.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            if (field.v2Home() != null
                    && field.v2Home().placement() == CaptureEvidenceField.V2Home.Placement.IDENTITY) {
                assertFalse(field.isInternalOnly(),
                        field.v1Key() + " is carried by the endpoint identity; withholding its "
                                + "attribute hides the key and ships the value");
            }
        }
    }

    @Test
    @DisplayName("the second door is closed too: a custom mapping is rejected outright")
    void theCustomMappingDoorIsClosed() {
        // Asserted through the decision, not the set: the set is package-private and, more to
        // the point, what matters is that a mapping is REFUSED — reading the constant would pass
        // even if nothing consulted it.
        assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                CatalogPropertyMappingResolver.rejectionFor("nemaki:chatParticipants", "people"),
                "an administrator could map the participants onto the document entity, and the "
                        + "sink-side filter would never see it");
        assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                CatalogPropertyMappingResolver.rejectionFor("nemaki:chatChannelName", "channel"));
        // The value that set was created for is still refused.
        assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                CatalogPropertyMappingResolver.rejectionFor("nemaki:cloudFileUrl", "legacyUrl"),
                "deriving the set from the evidence table dropped the entry it already had");
        // And an ordinary property is still mappable — the control.
        assertNull(CatalogPropertyMappingResolver.rejectionFor("nemaki:dept", "department"),
                "the derivation refused a property that has nothing to do with evidence");
    }

    @Test
    @DisplayName("the two doors are driven by one declaration, not two lists")
    void bothDoorsComeFromTheSameDeclaration() {
        // If these ever diverge, a fact is withheld from one door and not the other — which is
        // the state this whole increment is fixing.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            if (field.disclosure() == Disclosure.INTERNAL_ONLY
                    && field.cmisPropertyId() != null) {
                assertEquals(CatalogPropertyMappingResolver.Rejection.FORBIDDEN_SOURCE_PROPERTY,
                        CatalogPropertyMappingResolver.rejectionFor(field.cmisPropertyId(), "x"),
                        field.v1Key() + " is withheld from the sink but its object copy is still "
                                + "mappable to the catalogue");
            }
        }
    }
}
