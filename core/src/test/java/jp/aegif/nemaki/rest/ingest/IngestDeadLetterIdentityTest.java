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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dead-letter entry identifies the SOURCE ITEM, not the attempt.
 *
 * <p>The id used to be {@code "dlq-" + a random suffix}. A persistent failure — a journal outage,
 * an unreachable catalogue — therefore wrote a NEW document on every poll for the same item,
 * every five minutes, each with the ingested payload attached, into the configuration database,
 * with no deduplication and no expiry (external review). The queue grew without bound while
 * describing the same handful of items.
 *
 * <p>Deriving the id from the item's identity makes a repeat an update. The identity is the one
 * the ingest path already dedupes on, so two genuinely different items cannot collide.
 */
class IngestDeadLetterIdentityTest {

    private static String idFor(String repositoryId, String profileId, String sourceObjectType,
            String sourceObjectId) throws Exception {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setRepositoryId(repositoryId);
        req.setProfileId(profileId);
        req.setSourceObjectType(sourceObjectType);
        req.setSourceObjectId(sourceObjectId);
        Method m = IngestJobService.class.getDeclaredMethod("deadLetterIdFor",
                ExternalIngestRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(new IngestJobService(), req);
    }

    @Test
    @DisplayName("the same source item always maps to the same entry")
    void sameItemSameEntry() throws Exception {
        String first = idFor("bedroom", "p1", "message", "1720000000.000200");
        String second = idFor("bedroom", "p1", "message", "1720000000.000200");

        assertEquals(first, second,
                "a repeat failure must UPDATE the entry. With a random id it wrote a new document "
                        + "every poll, each carrying the payload, for as long as the outage lasted");
    }

    @Test
    @DisplayName("different items, repositories, profiles and types do not collide")
    void differentItemsDoNotCollide() throws Exception {
        String base = idFor("bedroom", "p1", "message", "msg-1");

        assertNotEquals(base, idFor("bedroom", "p1", "message", "msg-2"), "different source item");
        assertNotEquals(base, idFor("canopy", "p1", "message", "msg-1"), "different repository");
        assertNotEquals(base, idFor("bedroom", "p2", "message", "msg-1"), "different profile");
        assertNotEquals(base, idFor("bedroom", "p1", "attachment", "msg-1"),
                "different source object type — the same id can name a message and an attachment");
    }

    @Test
    @DisplayName("an item with no source id still gets its own entry rather than collapsing")
    void anonymousFailuresDoNotCollapse() throws Exception {
        // Collapsing every anonymous failure into one row would hide all but the last of them.
        String first = idFor("bedroom", "p1", "files", null);
        String second = idFor("bedroom", "p1", "files", null);

        assertNotEquals(first, second,
                "with no identity to key on there is nothing to deduplicate against; sharing one "
                        + "row would discard every failure but the most recent");
        assertTrue(first.startsWith("dlq-") && second.startsWith("dlq-"));
    }

    @Test
    @DisplayName("the id is stable across separate JVM runs, not just within one")
    void idIsDerivedNotRemembered() throws Exception {
        // A hash of the identity, not a counter or a cached map — so a restart mid-outage does
        // not start a second pile of entries for the items already recorded.
        String id = idFor("bedroom", "p1", "message", "msg-9");
        assertEquals("dlq-", id.substring(0, 4));
        assertEquals(28, id.length(), "dlq- plus 12 bytes of SHA-256 as hex");
        assertTrue(id.substring(4).matches("[0-9a-f]{24}"), id);
    }
}
