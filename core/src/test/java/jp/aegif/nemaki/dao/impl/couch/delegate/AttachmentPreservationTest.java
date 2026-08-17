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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every metadata write on an attachment document must carry its binary forward.
 *
 * <h2>The failure mode</h2>
 *
 * <p>{@code CloudantClientWrapper.update(Object)} serialises the POJO and posts it, which
 * REPLACES the stored document. None of the model classes carry {@code _attachments}, so any
 * metadata update through that method deletes the binary as a side effect. The delegate then
 * re-uploads it — which is why the code appeared to work, and why a read landing between the two
 * writes failed with "Content stream InputStream is null!".
 *
 * <p>Fixing the first site was not enough, and a reviewer caught the rest. Two of the remaining
 * three were worse than the original:
 *
 * <ul>
 * <li>The <b>compensating rollback</b> for a failed binary upload. It restores the previous
 *     metadata — and, through a plain update, deletes the previous binary. The compensation for
 *     "the new content did not upload" became "the old content is gone too".</li>
 * <li>The <b>metadata-only</b> branch, taken specifically when the binary is NOT changing. A path
 *     whose entire purpose is to leave the binary alone was deleting it.</li>
 * </ul>
 *
 * <p>The rule is not "the entry point is guarded" but "no write on this document may use the
 * replacing update", so the assertion is over the whole file rather than over one method.
 */
class AttachmentPreservationTest {

    private static final Path DELEGATE = Path.of(
            "src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java");
    private static final Path WRAPPER = Path.of(
            "src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java");

    @Test
    @DisplayName("添付文書への書き込みに、置換型の update(can) が 1 箇所も残っていない")
    void noWriteOnAnAttachmentDocumentUsesTheReplacingUpdate() throws Exception {
        String src = Files.readString(DELEGATE, StandardCharsets.UTF_8);

        Pattern plain = Pattern.compile("(?<!Preserving[A-Za-z]{0,20})\\bclient\\.update\\(\\s*can\\s*\\)");
        Matcher m = plain.matcher(src);
        List<String> offenders = new ArrayList<>();
        while (m.find()) {
            int line = 1 + (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            offenders.add("AttachmentDaoDelegate.java:" + line);
        }
        assertEquals(List.of(), offenders,
                "these writes would post a body without _attachments and delete the binary;"
                        + " use updatePreservingAttachments");
    }

    @Test
    @DisplayName("補償ロールバックが添付を保持する経路を使う")
    void theCompensatingRollbackPreservesTheBinary() throws Exception {
        String src = Files.readString(DELEGATE, StandardCharsets.UTF_8);
        int at = src.indexOf("Rollback successful");
        assertTrue(at > 0, "the rollback branch moved — this test needs updating");
        String around = src.substring(Math.max(0, at - 900), at);
        assertTrue(around.contains("updatePreservingAttachments"),
                "a rollback that deletes the previous binary is not a rollback");
    }

    @Test
    @DisplayName("バイナリを変えないメタデータ専用更新でも添付を保持する")
    void theMetadataOnlyBranchPreservesTheBinary() throws Exception {
        String src = Files.readString(DELEGATE, StandardCharsets.UTF_8);
        int at = src.indexOf("Metadata-only update");
        assertTrue(at > 0, "the metadata-only branch moved — this test needs updating");
        String branch = src.substring(at, src.indexOf("\n\t\t\t}", at));
        assertTrue(branch.contains("updatePreservingAttachments"),
                "the branch that exists BECAUSE the binary is unchanged must not delete it");
    }

    @Test
    @DisplayName("preserving 経路は失敗しても置換型 update にフォールバックしない")
    void thePreservingPathNeverFallsBackToTheDestructiveUpdate() throws Exception {
        String src = Files.readString(WRAPPER, StandardCharsets.UTF_8);
        int at = src.indexOf("public void updatePreservingAttachments");
        assertTrue(at > 0, "updatePreservingAttachments not found");
        String method = src.substring(at, src.indexOf("\n\tpublic ", at + 10));
        int catchAt = method.indexOf("} catch (Exception e) {");
        assertTrue(catchAt > 0, "expected a catch block guarding the stub construction");
        // Strip comments before judging. The handler DESCRIBES the forbidden call in prose —
        // deliberately, so the next reader knows why it is absent — and a naive contains() check
        // matches that description instead of the code. Asserting on source text at all is a
        // compromise; asserting on source text that includes its own commentary is a trap.
        String handler = stripComments(method.substring(catchAt));
        assertFalse(handler.contains("update(document)"),
                "falling back to the replacing update re-opens exactly the window this method"
                        + " exists to close, and the caller carries on believing it succeeded");
        assertTrue(handler.contains("throw"),
                "the failure must reach the caller so its compensating rollback can run");
    }

    /** Removes // line comments and block comments, leaving code. */
    private static String stripComments(String java) {
        String noBlocks = java.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder();
        for (String line : noBlocks.split("\n")) {
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }
}
