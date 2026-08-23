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

import jp.aegif.nemaki.businesslogic.EvidenceTypes;
import jp.aegif.nemaki.rest.ingest.EvidenceMetadataHash;
import jp.aegif.nemaki.rest.importexport.ZipImporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical containment check the design said "cannot be written today" — written.
 *
 * <p>{@code EvidenceTypes}' javadoc recorded WHY it was unwritable: the type↔property
 * association lived in private method-local arrays inside the type patches, and the database
 * stores detail node ids rather than property ids. The first half is now fixed — every type
 * patch exposes its type id and its derived property-id list — so the static containment can be
 * pinned. The second half stays true: this test proves the CONSTANTS agree, not the database
 * (a DB-side check needs detail→core resolution and is an integration test by nature,
 * evidence-types §2.2's other branch).
 */
class EvidenceProtectionRosterTest {

    @Test
    @DisplayName("PROTECTED is exactly the two types the patches declare")
    void protectedMatchesThePatches() {
        assertEquals(Set.of(Patch_ChatContextMetadataSecondaryType.TYPE_ID,
                        Patch_ExternalIntegrationSecondaryType.TYPE_ID),
                EvidenceTypes.PROTECTED,
                "a type patch and the protection registry disagree about WHICH types are "
                        + "evidence");
        assertEquals(Patch_ExternalIntegrationSecondaryType.TYPE_ID,
                Patch_ExternalIntegrationEvidenceReadOnly.TYPE_ID,
                "the read-only patch protects a different type than the creator made");
    }

    @Test
    @DisplayName("the chat patch's 11 properties are exactly the protected chat evidence")
    void chatRosterMatchesTheProtection() {
        TreeSet<String> patchRoster = new TreeSet<>();
        patchRoster.addAll(Patch_ChatContextMetadataSecondaryType.STRING_PROPERTY_IDS);
        patchRoster.addAll(Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS);

        assertEquals(new TreeSet<>(Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES),
                patchRoster,
                "the type patch declares a property the READONLY patch does not protect (or "
                        + "vice versa) — a new chat property would ship writable, silently");
        assertEquals(new TreeSet<>(EvidenceMetadataHash.DATETIME_EVIDENCE_PROPERTIES),
                new TreeSet<>(Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS),
                "the hash normalizes datetimes for a different set than the patch declares "
                        + "as DATETIME — a datetime added to one but not the other hashes as "
                        + "raw text on one side");
    }

    @Test
    @DisplayName("the externalIntegration roster equals the hash's view of the type")
    void integrationRosterMatchesTheHash() {
        TreeSet<String> patchRoster = new TreeSet<>();
        patchRoster.addAll(Patch_ExternalIntegrationSecondaryType.PROPERTY_IDS);
        patchRoster.addAll(Patch_ExternalIntegrationSourceFields.PROPERTY_IDS);
        patchRoster.add(Patch_ExternalIntegrationEvidenceReadOnly.CONTENT_HASH_PROPERTY_ID);

        TreeSet<String> hashView = new TreeSet<>();
        hashView.addAll(EvidenceMetadataHash.SOURCE_IDENTITY_PROPERTIES);
        hashView.addAll(EvidenceMetadataHash.EXCLUDED_FROM_METADATA_HASH);

        assertEquals(hashView, patchRoster,
                "the three externalIntegration patches declare a property the hash neither "
                        + "covers nor lists as excluded — a new fact would sit outside the "
                        + "hash silently (the exact failure P2-6 already caught once by luck)");
    }

    @Test
    @DisplayName("the import strip covers the full roster of both types")
    void importStripCoversTheRoster() {
        TreeSet<String> roster = new TreeSet<>();
        roster.addAll(Patch_ChatContextMetadataSecondaryType.STRING_PROPERTY_IDS);
        roster.addAll(Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        roster.addAll(Patch_ExternalIntegrationSecondaryType.PROPERTY_IDS);
        roster.addAll(Patch_ExternalIntegrationSourceFields.PROPERTY_IDS);
        roster.add(Patch_ExternalIntegrationEvidenceReadOnly.CONTENT_HASH_PROPERTY_ID);

        assertEquals(roster, new TreeSet<>(ZipImporter.EVIDENCE_PROPERTY_IDS),
                "an archive can smuggle a declared evidence property past the import strip "
                        + "(or the strip eats a property no patch declares)");
    }

    @Test
    @DisplayName("both protected types have a non-empty declared roster — the vacuity control")
    void rostersAreNonEmpty() {
        assertTrue(Patch_ChatContextMetadataSecondaryType.STRING_PROPERTY_IDS.size() == 8
                        && Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS.size() == 3,
                "the chat roster shrank — derived lists went missing");
        assertTrue(Patch_ExternalIntegrationSecondaryType.PROPERTY_IDS.size() == 4
                        && Patch_ExternalIntegrationSourceFields.PROPERTY_IDS.size() == 6,
                "the integration roster shrank");
    }
}
