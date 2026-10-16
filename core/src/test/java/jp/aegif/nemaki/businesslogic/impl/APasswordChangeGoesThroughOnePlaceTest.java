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
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * A password change raises the credential generation, and it happens in one place (R170, 3.4.1).
 *
 * <p>The generation is what makes the change end the sessions it opened. It only works if EVERY
 * password change raises it, and the password was being written in nine places across five files —
 * so the write and the raise are one method now, and this lock measures both halves: what the method
 * does, and that nothing else writes a password.
 *
 * <p>What this lock deliberately does NOT require: that a user CREATED with a password hash gets a
 * generation. Creation writes the hash into a new document whose generation is absent, and absent
 * reads as 0 — the state every account starts in. The inventory arm below is what keeps that
 * distinction honest: a new hash writer has to be looked at and classified, not assumed.
 */
class APasswordChangeGoesThroughOnePlaceTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private UserItem user(Object generation) {
        UserItem item = new UserItem("alice-doc", "nemaki:user", "alice", "alice", "old-hash", false, null);
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("nemaki:allowedAuthMethods", "password"));
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        item.setSubTypeProperties(properties);
        return item;
    }

    private ContentServiceImpl service() {
        return new ContentServiceImpl();
    }

    @Test
    @DisplayName("the hash is written and the generation goes up by one")
    void theHashIsWrittenAndTheGenerationGoesUp() {
        UserItem alice = user(2);

        service().stampPasswordChange(alice, "new-hash", true);

        assertEquals("new-hash", alice.getPassowrd());
        assertEquals(Long.valueOf(3L), AuthenticationUtil.accountGeneration(alice),
                "the generation did not move, so every session and MCP session the old password"
                        + " opened keeps working — the whole point of the field");
    }

    @Test
    @DisplayName("an account with no generation yet starts at 1 after its first change")
    void anAccountWithNoGenerationStartsAtOne() {
        UserItem alice = user(null);

        service().stampPasswordChange(alice, "new-hash", true);

        assertEquals(Long.valueOf(1L), AuthenticationUtil.accountGeneration(alice),
                "absent reads as 0, so the first change has to write 1; leaving it absent would"
                        + " invalidate nothing");
    }

    @Test
    @DisplayName("the MD5-to-BCrypt upgrade writes the hash and does NOT move the generation")
    void theUpgradeDoesNotMoveTheGeneration() {
        UserItem alice = user(2);

        service().stampPasswordChange(alice, "bcrypt-hash", false);

        assertEquals("bcrypt-hash", alice.getPassowrd());
        assertEquals(Long.valueOf(2L), AuthenticationUtil.accountGeneration(alice),
                "the upgrade raised the generation, which ends the session of the person who just"
                        + " logged in — they did not change their password");
    }

    @Test
    @DisplayName("a generation nobody can read refuses the change instead of inventing a number")
    void anUnreadableGenerationRefuses() {
        UserItem alice = user("nonsense");

        assertThrows(IllegalStateException.class,
                () -> service().stampPasswordChange(alice, "new-hash", true),
                "the change went ahead from a value nobody can read, so what it invalidates is a"
                        + " guess — and the save guard refuses such a document anyway");
        assertEquals("old-hash", alice.getPassowrd(),
                "the password was written even though the change was refused");
    }

    @Test
    @DisplayName("nothing else writes a password: setPassowrd is called in one place")
    void nothingElseWritesAPassword() throws IOException {
        List<String> writers = new ArrayList<>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                if (file.toString().contains("/model/")) {
                    continue;   // the setter itself and the model's own uses
                }
                String code = JavaSource.withoutComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                int from = 0;
                while ((from = code.indexOf("setPassowrd(", from)) >= 0) {
                    writers.add(file.getFileName().toString());
                    from += "setPassowrd(".length();
                }
            }
        }
        assertEquals(List.of("ContentServiceImpl.java"), writers,
                "a password is written outside the one method that also raises the credential"
                        + " generation, so that change invalidates nothing: " + writers);
    }

    @Test
    @DisplayName("every place that makes a BCrypt hash is accounted for — a new one has to be classified")
    void everyHashWriterIsAccountedFor() throws IOException {
        // The second vocabulary. Counting setPassowrd alone was how the earlier inventory missed
        // the two setup paths, which write a hash straight into the user document (batch-19 design,
        // round-2 review). Frozen by file with a count, so a new writer fails this and gets read:
        // a CHANGE has to go through the stamper, a CREATE does not (absent generation reads as 0),
        // and a hash that is not a password at all (an API key) is neither.
        Map<String, Integer> expected = new TreeMap<>(Map.of(
                "ApiKeyServiceImpl.java", 1,          // an API key, not a password
                "AuthenticationUtil.java", 1,         // the MD5-to-BCrypt upgrade's new hash
                "ContentServiceImpl.java", 1,         // hashPassword itself
                "AuthResource.java", 1,               // creates a user (generation absent = 0)
                "SetupAdminResource.java", 1,         // Setup Mode only; a work item of this batch
                "SetupApplyResource.java", 1,         // Setup Mode only; a work item of this batch
                "DirectorySyncServiceImpl.java", 2,   // creates users from LDAP
                "AuthTokenResource.java", 1));        // creates an SSO user with a random password

        Map<String, Integer> found = new TreeMap<>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String code = JavaSource.withoutComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                int count = 0;
                int from = 0;
                while ((from = code.indexOf("BCrypt.hashpw(", from)) >= 0) {
                    count++;
                    from += "BCrypt.hashpw(".length();
                }
                if (count > 0) {
                    found.put(file.getFileName().toString(), count);
                }
            }
        }
        assertEquals(expected, found,
                "the places that make a password hash changed. A CHANGE must go through"
                        + " stampPasswordChange so the credential generation moves with it; a CREATE"
                        + " may write the hash directly (the generation is absent, which reads as 0);"
                        + " an API key hash is neither. Classify the new one and update this list: "
                        + found);
        assertTrue(found.containsKey("ContentServiceImpl.java"),
                "hashPassword is gone from the service, so this inventory is measuring something"
                        + " else now");
    }
}
