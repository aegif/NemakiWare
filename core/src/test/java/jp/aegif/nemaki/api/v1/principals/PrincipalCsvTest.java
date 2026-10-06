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
package jp.aegif.nemaki.api.v1.principals;

import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Kind;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict reader, on inputs that tell a right reading from a wrong one (design §4).
 */
class PrincipalCsvTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("quoted cells keep commas, newlines and doubled quotes; a BOM is allowed")
    void rfc4180QuotingIsHonoured() {
        String csv = "﻿userId,name,email\r\n"
                + "u001,\"Doe, Jane\",jane@example.com\n"
                + "u002,\"Say \"\"hi\"\"\nthen\",joe@example.com\n";
        PrincipalCsv.Parsed parsed = PrincipalCsv.parse(utf8(csv), Kind.USERS, Operation.UPDATE);
        assertEquals(2, parsed.rows().size());
        assertEquals("Doe, Jane", parsed.rows().get(0).cell("name"));
        assertEquals("Say \"hi\"\nthen", parsed.rows().get(1).cell("name"));
        assertEquals(3, parsed.rows().get(1).line(), "the line number counts the FILE's lines");
    }

    @Test
    @DisplayName("a blank cell is 'not stated' (null), not an empty string")
    void aBlankCellIsNotStated() {
        PrincipalCsv.Parsed parsed = PrincipalCsv.parse(utf8("userId,name,groups\nu001,,\n"),
                Kind.USERS, Operation.UPDATE);
        assertNull(parsed.rows().get(0).cell("name"));
        assertNull(parsed.rows().get(0).cell("groups"),
                "an empty groups column is 'do not touch' — the planner must never read it as 'no groups'");
    }

    @Test
    @DisplayName("an unknown column is refused, not dropped")
    void anUnknownColumnIsRefused() {
        PrincipalBatchRequestException e = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("userId,grups\nu001,g1\n"), Kind.USERS, Operation.UPDATE));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("grups"), e.getMessage());
    }

    @Test
    @DisplayName("the same id twice in one file is refused")
    void aDuplicateIdIsRefused() {
        PrincipalBatchRequestException e = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("userId\nu001\nu001\n"), Kind.USERS, Operation.DELETE));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    @DisplayName("memberships add / remove may name one group on many rows, replace may not")
    void membershipRowsAreUniquePerPairOrPerGroup() {
        PrincipalCsv.Parsed add = PrincipalCsv.parse(
                utf8("groupId,memberId,memberType\ng1,u1,user\ng1,u2,user\n"),
                Kind.MEMBERSHIPS, Operation.ADD);
        assertEquals(2, add.rows().size());
        PrincipalBatchRequestException e = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("groupId,members\ng1,user:u1\ng1,user:u2\n"),
                        Kind.MEMBERSHIPS, Operation.REPLACE));
        assertEquals(400, e.status());
    }

    @Test
    @DisplayName("a replace file without a members column is refused; a present but blank cell is the stated 'empty'")
    void aReplaceFileWithoutAMembersColumnIsRefused() {
        PrincipalBatchRequestException dropped = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("groupId\ng1\n"), Kind.MEMBERSHIPS, Operation.REPLACE));
        assertEquals(400, dropped.status());
        assertTrue(dropped.getMessage().contains("members"), dropped.getMessage());
        PrincipalCsv.Parsed stated = PrincipalCsv.parse(utf8("groupId,members\ng1,\n"),
                Kind.MEMBERSHIPS, Operation.REPLACE);
        assertEquals(1, stated.rows().size());
        assertNull(stated.rows().get(0).cell("members"),
                "the blank cell is null like any other — only the HEADER tells a dropped column from a stated empty one");
    }

    @Test
    @DisplayName("one byte over the byte limit is 413 before the text is even decoded")
    void overTheByteLimitIs413() {
        byte[] oneOver = new byte[(int) PrincipalBatch.MAX_BYTES + 1];
        java.util.Arrays.fill(oneOver, (byte) 'u');
        PrincipalBatchRequestException e = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(oneOver, Kind.USERS, Operation.DELETE));
        assertEquals(413, e.status());
    }

    @Test
    @DisplayName("a row's line is the FILE's line it starts on, also after a cell that spans lines")
    void lineNumbersArePhysicalAfterAMultiLineCell() {
        PrincipalCsv.Parsed parsed = PrincipalCsv.parse(utf8("userId,name\nu1,\"two\nlines\"\nu2,x\n"),
                Kind.USERS, Operation.UPDATE);
        assertEquals(2, parsed.rows().get(0).line());
        assertEquals(4, parsed.rows().get(1).line(), "u2 starts on line 4: the header, u1, u1's second line, then u2");
    }

    @Test
    @DisplayName("over the row limit is 413 before any row is read into a plan")
    void tooManyRowsIs413() {
        StringBuilder csv = new StringBuilder("userId\n");
        for (int i = 0; i <= PrincipalBatch.MAX_ROWS; i++) {
            csv.append("u").append(i).append('\n');
        }
        PrincipalBatchRequestException e = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8(csv.toString()), Kind.USERS, Operation.DELETE));
        assertEquals(413, e.status());
    }

    @Test
    @DisplayName("invalid UTF-8, an unclosed quote and a missing id column are each a 400 with a line")
    void malformedInputIsA400() {
        assertEquals(400, assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(new byte[] {'u', 's', 'e', 'r', 'I', 'd', '\n', (byte) 0xC3, 0x28},
                        Kind.USERS, Operation.DELETE)).status());
        PrincipalBatchRequestException unclosed = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("userId,name\nu1,\"open\n"), Kind.USERS, Operation.UPDATE));
        assertEquals(400, unclosed.status());
        assertTrue(unclosed.getMessage().contains("line"), unclosed.getMessage());
        PrincipalBatchRequestException noId = assertThrows(PrincipalBatchRequestException.class,
                () -> PrincipalCsv.parse(utf8("name\nx\n"), Kind.USERS, Operation.UPDATE));
        assertEquals(400, noId.status());
        assertTrue(noId.getMessage().contains("userId"), noId.getMessage());
    }

    @Test
    @DisplayName("exactly the row limit followed by blank lines is not 413 — a blank line is not a row")
    void blankLinesDoNotCountTowardsTheLimit() {
        StringBuilder csv = new StringBuilder("userId\n");
        for (int i = 0; i < PrincipalBatch.MAX_ROWS; i++) {
            csv.append("u").append(i).append('\n');
        }
        csv.append("\n\n");

        // assertDoesNotThrow, so the 413 fails THIS assertion rather than escaping the test as an
        // exception the runner cannot tell from harness breakage.
        PrincipalCsv.Parsed parsed = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> PrincipalCsv.parse(utf8(csv.toString()), Kind.USERS, Operation.DELETE),
                "a file of exactly the limit with a trailing blank line was 413: the blank line "
                        + "was skipped as a row and counted towards the limit (9-6 review, P3)");

        assertEquals(PrincipalBatch.MAX_ROWS, parsed.rows().size());
    }
}
