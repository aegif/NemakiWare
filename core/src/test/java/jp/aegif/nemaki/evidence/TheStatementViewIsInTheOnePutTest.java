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
package jp.aegif.nemaki.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement a package ships has to be findable, and it has to be the current one.
 *
 * <p>Both properties live in code a unit test cannot execute without a database, so both are
 * measured where they are decided: the view registration, and the row the lookup picks.
 */
class TheStatementViewIsInTheOnePutTest {

    @Test
    @DisplayName("the statement view is deployed in the same put as the others")
    void theStatementViewIsDeployedWithTheOthers() {
        assertTrue(CouchEvidenceLedgerStore.viewSources()
                        .containsKey(CouchContentWriteJournal.VIEW_STATEMENTS),
                "the statement view is not in the single design-document put. Either it is "
                        + "never deployed — and no package can find a statement to ship — or a "
                        + "second put deploys it and discards the index CouchDB has just built "
                        + "for the ledger");
    }

    @Test
    @DisplayName("the NEWEST statement for a version is the one shipped")
    void theNewestStatementForAVersionIsTheOneShipped() throws java.io.IOException {
        // Structural: choosing a row from a view result needs a database, and the property is a
        // one-line decision. A version can be rewritten in place (W3 / W7 / W9), so its rows
        // accumulate; taking the first would ship the digest of content that has since been
        // replaced, and the package would then fail its own CONTENT_BINDING check.
        Path source = Path.of("src/main/java/jp/aegif/nemaki/evidence/"
                + "CouchContentWriteJournal.java");
        assertTrue(Files.exists(source), "the journal is not at " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int at = text.indexOf("public Map<String, Object> statementFor(");
        assertTrue(at >= 0, "statementFor has moved, so this lock reads a decision that is gone "
                + "rather than one that is wrong");
        String body = text.substring(at, text.indexOf("\n    }", at))
                .replaceAll("(?m)//.*$", "");
        assertTrue(body.contains("result.getRows().size() - 1"),
                "the statement lookup no longer takes the last row for a version. A version "
                        + "rewritten in place has more than one, and the newest is the one that "
                        + "describes the bytes stored now.\nBody reads:\n" + body);
    }
}
