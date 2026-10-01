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

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The first version of this lock held that the lookup takes the LAST ROW — and it did,
     * and that was wrong: rows are keyed by a random intent id, so the last row is the last
     * UUID, not the newest statement. The lock measured that the code did what it did, not that
     * what it did was right (found while wiring the transitions, 2026-09-22). "Newest" is now
     * the highest ledger sequence, measured behaviourally in
     * {@code TheNewestStatementIsByLedgerSequenceTest}; what this structural half keeps is
     * that {@code statementFor} still goes through that one decision rather than growing a
     * second one.
     */
    @Test
    @DisplayName("the NEWEST statement for a version is the one shipped — by ledger sequence, through one lookup")
    void theNewestStatementForAVersionIsTheOneShipped() throws java.io.IOException {
        Path source = Path.of("src/main/java/jp/aegif/nemaki/evidence/"
                + "CouchContentWriteJournal.java");
        assertTrue(Files.exists(source), "the journal is not at " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "");
        int at = text.indexOf("public Map<String, Object> statementFor(");
        assertTrue(at >= 0, "statementFor has moved, so this lock reads a decision that is gone "
                + "rather than one that is wrong");
        String body = text.substring(at, text.indexOf("\n    }", at));
        assertTrue(body.contains("latestRecorded(repositoryId, versionObjectId)"),
                "statementFor no longer goes through latestRecorded, so 'newest' has two "
                        + "definitions in one class.\nBody reads:\n" + body);
        assertFalse(body.contains("getRows()"),
                "statementFor reads view rows itself again; the row order is UUID order and "
                        + "the last row is not the newest statement");

        int latest = text.indexOf("public Recorded latestRecorded(");
        assertTrue(latest >= 0, "latestRecorded has moved");
        String decision = text.substring(latest, text.indexOf("\n    }", latest));
        assertTrue(decision.contains("at > newest.entrySequence()"),
                "latestRecorded no longer picks the HIGHEST ledger sequence. The ledger sequence "
                        + "is the one order that is time; a transition copies its prior from "
                        + "whichever statement this returns.\nBody reads:\n" + decision);
    }
}
