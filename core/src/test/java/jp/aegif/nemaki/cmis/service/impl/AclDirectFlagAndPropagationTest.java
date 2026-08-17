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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * {@code applyAcl} stores ACEs that are, by definition, direct.
 *
 * <h2>What was wrong and what it did (and did not) break</h2>
 *
 * <p>The third constructor argument of {@code Ace} is {@code direct}. {@code applyAcl} passed
 * {@code aclPropagation == OBJECTONLY} into that slot, so a PROPAGATE request — the normal case,
 * and the only value this repository declares support for — stored every ACE with
 * {@code direct=false}, exactly inverting CMIS 1.1 §2.1.12.1.
 *
 * <p>It was tempting to call that a data-loss bug: the browser binding's add/remove form of
 * applyACL keeps only the ACEs whose {@code isDirect()} is true, so an inverted flag looks like
 * it would drop a just-granted ACE on the next call. Verified against a live server — it does
 * not, because the flag never survives long enough to be read: CouchDB persists only principal
 * and permissions, and the wire value is recomputed by {@code compileAcl} from list membership.
 *
 * <p>So this is a correctness-of-representation fix, not a hotfix. These assertions exist to stop
 * the inversion coming back, and to record the two mechanisms that made it survivable — because
 * the next person to read the code will otherwise reach the same alarming and wrong conclusion.
 */
class AclDirectFlagAndPropagationTest {

    private static String read(String relativePath) throws Exception {
        return JavaSource.read(relativePath);
    }

    @Test
    @DisplayName("applyAcl が保存する Ace は常に direct=true (伝播値を流し込まない)")
    void applyAclStoresDirectAces() throws Exception {
        String src = read("src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java");
        // Bounded by brace matching, with comments stripped. The two boundaries tried before —
        // a fixed character count, then the next @Override — each silently stopped describing
        // this method: one truncated it, the other ran past it into methods with no annotation.
        String body = JavaSource.withoutComments(
                JavaSource.methodBody(src, "public Acl applyAcl(CallContext callContext"));

        Pattern aceCtor = Pattern.compile(
                "new jp\\.aegif\\.nemaki\\.model\\.Ace\\([^;]*?,\\s*([A-Za-z0-9_]+)\\s*\\)");
        Matcher m = aceCtor.matcher(body);
        int found = 0;
        while (m.find()) {
            found++;
            assertEquals("true", m.group(1),
                    "applyAcl must store direct=true; passing the propagation flag here inverts"
                            + " the CMIS meaning of isDirect");
        }
        assertEquals(1, found,
                "applyAcl now has exactly one Ace construction: the requested entries. The two"
                        + " others belonged to a 'break with no ACL supplied, keep the current"
                        + " effective ACL' fallback that was unreachable — breakingInheritance is"
                        + " only ever set from acl.getExtensions(), so a null ACL never got"
                        + " there. Found " + found);
    }

    @Test
    @DisplayName("永続層は direct を保存せず、読み戻しで true に再生成する")
    void theFlagIsNotPersisted() throws Exception {
        String couchContent = read("src/main/java/jp/aegif/nemaki/model/couch/CouchContent.java");
        String method = JavaSource.withoutComments(
                JavaSource.methodBody(couchContent, "private CouchAcl convertToCouchAcl"));
        assertFalse(method.contains("isDirect") || method.contains("direct"),
                "convertToCouchAcl must not persist the direct flag — if it starts doing so,"
                        + " the no-migration claim in AclServiceImpl's comment stops holding");

        String couchAcl = read("src/main/java/jp/aegif/nemaki/model/couch/CouchAcl.java");
        assertTrue(couchAcl.contains("new Ace(principal, permissions, true)"),
                "CouchAcl.convert must reconstruct stored entries as direct");
    }

    @Test
    @DisplayName("REST の継承ブレークが OBJECTONLY を強制しない")
    void breakingInheritanceDoesNotForceObjectOnly() throws Exception {
        String src = read("src/main/java/jp/aegif/nemaki/rest/PermissionResource.java");
        assertFalse(src.contains("aclPropagation = AclPropagation.OBJECTONLY"),
                "forcing OBJECTONLY made this repository's own UI the main user of a propagation"
                        + " value the server does not implement separately; the break is carried"
                        + " by the inherited=false extension instead");
        assertTrue(src.contains("\"inherited\", null, \"false\""),
                "the inherited=false extension is what actually breaks inheritance — if it goes,"
                        + " breaking inheritance silently stops working");
    }
}
