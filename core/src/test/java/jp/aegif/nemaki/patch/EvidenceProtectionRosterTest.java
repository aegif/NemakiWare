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
    @DisplayName("PROTECTED is exactly the five types the patches declare")
    void protectedMatchesThePatches() {
        assertEquals(Set.of(Patch_ChatContextMetadataSecondaryType.TYPE_ID,
                        Patch_ExternalIntegrationSecondaryType.TYPE_ID,
                        Patch_MessageMetadataSecondaryType.TYPE_ID,
                        Patch_NoteMetadataSecondaryType.TYPE_ID,
                        Patch_BusinessRecordMetadataSecondaryType.TYPE_ID),
                EvidenceTypes.PROTECTED,
                "a type patch and the protection registry disagree about WHICH types are "
                        + "evidence");
        assertEquals(Patch_ExternalIntegrationSecondaryType.TYPE_ID,
                Patch_ExternalIntegrationEvidenceReadOnly.TYPE_ID,
                "the read-only patch protects a different type than the creator made");
    }

    @Test
    @DisplayName("every PROTECTED type has a read-only patch covering its whole roster")
    void everyProtectedTypeHasAReadOnlyPatch() {
        // The trap (c) §8.1 names: a type in PROTECTED but with no READONLY patch is protected
        // against detach-by-type while every property stays writable — the "中途半端" state the
        // canon says NOT to create. This asserts the pairing for the three archetype types in
        // both directions.
        java.util.Map<String, java.util.List<String>> rosters =
                Patch_ArchetypeMetadataEvidenceReadOnly.EVIDENCE_PROPERTIES_BY_TYPE;
        assertEquals(Set.of(Patch_MessageMetadataSecondaryType.TYPE_ID,
                        Patch_NoteMetadataSecondaryType.TYPE_ID,
                        Patch_BusinessRecordMetadataSecondaryType.TYPE_ID),
                rosters.keySet(),
                "the archetype read-only patch covers a different set of types than the three "
                        + "that joined PROTECTED");
        for (String typeId : rosters.keySet()) {
            assertTrue(EvidenceTypes.isProtected(typeId),
                    typeId + " is made read-only but is not PROTECTED — a client can still "
                            + "detach the whole aspect and take the evidence with it");
        }

        assertEquals(new TreeSet<>(concat(
                        Patch_MessageMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_MessageMetadataSecondaryType.DATETIME_PROPERTY_IDS)),
                new TreeSet<>(rosters.get(Patch_MessageMetadataSecondaryType.TYPE_ID)),
                "the mail roster drifted from what the type patch declares");
        assertEquals(new TreeSet<>(concat(
                        Patch_NoteMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_NoteMetadataSecondaryType.DATETIME_PROPERTY_IDS)),
                new TreeSet<>(rosters.get(Patch_NoteMetadataSecondaryType.TYPE_ID)),
                "the note roster drifted");
        assertEquals(new TreeSet<>(concat(
                        Patch_BusinessRecordMetadataSecondaryType.STRING_PROPERTY_IDS,
                        Patch_BusinessRecordMetadataSecondaryType.DATETIME_PROPERTY_IDS)),
                new TreeSet<>(rosters.get(Patch_BusinessRecordMetadataSecondaryType.TYPE_ID)),
                "the business-record roster drifted");
    }

    private static java.util.List<String> concat(java.util.List<String> a,
            java.util.List<String> b) {
        java.util.List<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
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
        // The datetime pin now spans all FOUR hashed homes (chat + the three archetypes that
        // joined on 2026-08-24). A datetime declared by a type patch but missing here hashes as
        // raw text, so a Calendar from a cache hit and a Long from the CouchDB round trip give
        // different digests for the same stored instant — the F1 regression.
        TreeSet<String> declaredDatetimes = new TreeSet<>();
        declaredDatetimes.addAll(Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        declaredDatetimes.addAll(Patch_MessageMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        declaredDatetimes.addAll(Patch_NoteMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        declaredDatetimes.addAll(
                Patch_BusinessRecordMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        assertEquals(new TreeSet<>(EvidenceMetadataHash.DATETIME_EVIDENCE_PROPERTIES),
                declaredDatetimes,
                "the hash normalizes datetimes for a different set than the patches declare "
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
        roster.addAll(Patch_ArchetypeMetadataEvidenceReadOnly.EVIDENCE_PROPERTIES);

        assertEquals(roster, new TreeSet<>(ZipImporter.EVIDENCE_PROPERTY_IDS),
                "an archive can smuggle a declared evidence property past the import strip "
                        + "(or the strip eats a property no patch declares)");
    }

    @Test
    @DisplayName("the journal's closed compartment key sets equal the evidence table's — exactly")
    void compartmentKeySetsMatchTheTable() {
        // The journal must not import the ingest layer, so it owns literal key sets; this
        // equality (BOTH directions) is what keeps the one-table principle across the layering
        // boundary (P1-1(e) §2.2). A key in the journal's set no enum entry claims is dead
        // weight that validation would accept; an enum entry the set lacks throws at
        // construction.
        java.util.TreeSet<String> tableProcess = new java.util.TreeSet<>();
        java.util.TreeSet<String> tableJournal = new java.util.TreeSet<>();
        for (jp.aegif.nemaki.rest.ingest.CaptureEvidenceField field
                : jp.aegif.nemaki.rest.ingest.CaptureEvidenceField.values()) {
            switch (field.v2Home().placement()) {
                case PROCESS_FACT -> tableProcess.add(field.v1Key());
                case JOURNAL_FACT -> tableJournal.add(field.v1Key());
                default -> { }
            }
        }
        assertEquals(new java.util.TreeSet<>(
                        jp.aegif.nemaki.rest.purview.journal.LineageEventV2.PROCESS_FACT_KEYS),
                tableProcess, "the process compartment drifted from the table");
        assertEquals(new java.util.TreeSet<>(
                        jp.aegif.nemaki.rest.purview.journal.LineageEventV2.JOURNAL_FACT_KEYS),
                tableJournal, "the journal compartment drifted from the table");
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
        assertEquals(11 + 8 + 8, Patch_ArchetypeMetadataEvidenceReadOnly
                        .EVIDENCE_PROPERTIES.size(),
                "the archetype rosters shrank — mail 11 + note 8 + record 8. A derived list "
                        + "going empty would make every assertion above vacuously true");
    }
}
