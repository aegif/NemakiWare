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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Breaking inheritance must apply the ACEs the caller sent, not the ones it already had.
 *
 * <h2>The failure this pins</h2>
 *
 * <p>This repository's own permission UI sends the new ACE list and {@code inherited=false} in a
 * SINGLE {@code applyAcl}. The break branch used to ignore {@code acl.getAces()} completely: it
 * read the object's CURRENT effective ACL — local plus everything inherited — and wrote all of it
 * back as local ACEs. So "detach this folder and set permissions to X" executed as "detach this
 * folder and keep exactly what it had", and the response said success.
 *
 * <p>That is silent over-permission, the worst shape an ACL defect takes: an administrator removes
 * somebody, is told it worked, and the removal is not in the repository. Nothing in the response,
 * the UI, or the log said otherwise.
 *
 * <h2>Why this test reads source</h2>
 *
 * <p>{@code applyAcl} needs the full service graph (content, type manager, exception service,
 * epoch writer, Solr, caches) before it reaches this branch, and a mock-built harness for all of
 * it would assert more about the mocks than about the branch. The two properties that matter are
 * structural and stable: the break path must consult the requested ACEs, and the fallback that
 * materialises the current ACL must be reachable ONLY when nothing was requested.
 */
class BreakInheritanceAppliesRequestedAcesTest {

    private static String applyAclBody() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java"),
                StandardCharsets.UTF_8);
        int from = src.indexOf("public Acl applyAcl(");
        assertTrue(from > 0, "applyAcl not found");
        int to = src.indexOf("\n\t@Override", from);
        return to > from ? src.substring(from, to) : src.substring(from);
    }

    /** The body with comments removed, so a phrase inside an explanation cannot satisfy a check. */
    private static String code(String body) {
        List<String> out = new ArrayList<>();
        boolean block = false;
        for (String line : body.split("\n", -1)) {
            String l = line;
            if (block) {
                int end = l.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                l = l.substring(end + 2);
                block = false;
            }
            int start = l.indexOf("/*");
            int lineComment = l.indexOf("//");
            if (start >= 0 && (lineComment < 0 || start < lineComment)) {
                String before = l.substring(0, start);
                int end = l.indexOf("*/", start);
                if (end < 0) {
                    block = true;
                    l = before;
                } else {
                    l = before + l.substring(end + 2);
                }
                lineComment = l.indexOf("//");
            }
            if (lineComment >= 0) {
                l = l.substring(0, lineComment);
            }
            out.add(l);
        }
        return String.join("\n", out);
    }

    @Test
    @DisplayName("継承ブレーク時も要求された ACE を適用する (捨てない)")
    void theBreakBranchConsultsTheRequestedAces() throws Exception {
        String body = code(applyAclBody());

        assertTrue(body.contains("acl.getAces()"),
                "the requested ACEs must be read on every path through applyAcl; the break branch"
                        + " used to ignore them entirely and echo back the object's existing"
                        + " permissions, silently keeping a principal the caller had removed");

        // The fallback exists, but only when there is nothing to apply.
        int fallback = body.indexOf("contentService.calculateAcl(repositoryId, content)");
        assertTrue(fallback > 0, "the no-ACEs fallback should still exist");
        int guard = body.indexOf("breakingInheritance && requestedDirect.isEmpty()");
        assertTrue(guard > 0 && guard < fallback,
                "materialising the current ACL must be guarded by 'nothing was requested' —"
                        + " unguarded, it is the defect itself");
    }

    @Test
    @DisplayName("ACE を伴う継承ブレークが、現行 ACL の写しにフォールバックしない")
    void aBreakWithAcesDoesNotFallBackToTheCurrentAcl() throws Exception {
        String body = code(applyAclBody());

        int guard = body.indexOf("requestedDirect.isEmpty()");
        int elseBranch = body.indexOf("} else {", guard);
        assertTrue(guard > 0 && elseBranch > guard, "expected the guarded if/else shape");

        String applyBranch = body.substring(elseBranch);
        assertTrue(applyBranch.contains("for (Ace ace : requestedDirect)")
                        || applyBranch.contains("for(Ace ace : requestedDirect)"),
                "the branch taken when ACEs WERE requested must iterate those ACEs");
        assertFalse(applyBranch.contains("getInheritedAces()"),
                "and must not copy inherited entries back in — that is what turned a revocation"
                        + " into a no-op");
    }

    @Test
    @DisplayName("REST 経路は要求リストと inherited=false を 1 回の applyAcl で送る")
    void theRestPathSendsBothInOneCall() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/rest/PermissionResource.java"),
                StandardCharsets.UTF_8);

        // This is why the defect mattered: the UI does not break first and set second. It sends
        // one call carrying both, so discarding the list discarded the whole intent.
        assertTrue(src.contains("convertJsonToCmisAcl(inputJson, breakInheritance)"),
                "the REST layer builds one ACL carrying both the ACEs and the break");
        assertTrue(src.contains("\"inherited\", null, \"false\""),
                "the break travels as the inherited=false extension on that same ACL");
        assertTrue(src.contains("ace.setDirect(direct != null ? direct : true)"),
                "and its ACEs are direct by default — so they are exactly the entries the"
                        + " service-side filter keeps, and were being dropped after that filter");
    }

    @Test
    @DisplayName("ACE 無しの継承ブレークは説明付きでログに残る")
    void theAmbiguousNoAceBreakIsLogged() throws Exception {
        String body = code(applyAclBody());
        assertTrue(body.contains("breaking inheritance with no ACEs supplied"),
                "the one case the wire cannot disambiguate — 'keep what I have' versus 'leave it"
                        + " empty' — must say which reading was taken, or an operator surprised by"
                        + " the outcome has nothing to go on");
    }
}
