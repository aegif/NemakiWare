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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A strict reader for the batch's CSV: UTF-8 (a leading BOM is allowed), RFC 4180 quoting, a
 * header row that names only the columns the (kind, operation) accepts, and one target id per
 * row across the file.
 *
 * <p>Strict on purpose. A column the reader does not know is a 400, not silently dropped — a
 * misspelt {@code groups} would otherwise make an update "leave the groups alone" while the
 * operator believes it set them. A blank cell means "not stated" (design §4); the planner reads
 * that as "do not touch" for updates, and only {@code memberships / replace}'s {@code members}
 * treats an EMPTY value as "make it empty".
 */
final class PrincipalCsv {

    private PrincipalCsv() {
    }

    /** The parsed file: the header as read, and the rows keyed by column. */
    record Parsed(List<String> header, List<PrincipalBatch.Row> rows) { }

    static Parsed parse(byte[] bytes, PrincipalBatch.Kind kind, PrincipalBatch.Operation operation) {
        if (bytes == null || bytes.length == 0) {
            throw new PrincipalBatchRequestException(400, "the CSV is empty");
        }
        if (bytes.length > PrincipalBatch.MAX_BYTES) {
            throw new PrincipalBatchRequestException(413, "the CSV is " + bytes.length
                    + " bytes; the limit is " + PrincipalBatch.MAX_BYTES);
        }
        String text = decodeUtf8(bytes);
        List<Record> records = records(text);
        if (records.isEmpty()) {
            throw new PrincipalBatchRequestException(400, "the CSV has no header row");
        }
        List<String> header = records.get(0).cells().stream().map(String::trim).toList();
        Set<String> allowed = PrincipalBatch.columnsFor(kind, operation);
        Set<String> seen = new LinkedHashSet<>();
        for (String column : header) {
            if (column.isEmpty()) {
                throw new PrincipalBatchRequestException(400, "the header has an empty column name");
            }
            if (!allowed.contains(column)) {
                throw new PrincipalBatchRequestException(400, "line 1: unknown column '" + column
                        + "' for " + kind.name().toLowerCase() + " / " + operation.name().toLowerCase()
                        + "; the columns are " + new java.util.TreeSet<>(allowed));
            }
            if (!seen.add(column)) {
                throw new PrincipalBatchRequestException(400, "line 1: column '" + column
                        + "' appears twice");
            }
        }
        String idColumn = PrincipalBatch.idColumnFor(kind);
        // The columns the operation cannot do without. For memberships / replace that is
        // `members`: a file that dropped the column must not read as "empty every group".
        for (String required : new java.util.TreeSet<>(PrincipalBatch.requiredColumnsFor(kind, operation))) {
            if (!seen.contains(required)) {
                throw new PrincipalBatchRequestException(400, "line 1: the column '" + required
                        + "' is required");
            }
        }
        // Rows, not records: a trailing or stray empty line is skipped below and is not a row,
        // so it must not count towards the limit either — 5,000 rows and a final blank line
        // were 413 (9-6 review, P3).
        long rowCount = 0;
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i).cells();
            if (!(cells.size() == 1 && cells.get(0).isEmpty())) {
                rowCount++;
            }
        }
        if (rowCount > PrincipalBatch.MAX_ROWS) {
            throw new PrincipalBatchRequestException(413, "the CSV has " + rowCount
                    + " rows; the limit is " + PrincipalBatch.MAX_ROWS);
        }
        List<PrincipalBatch.Row> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i).cells();
            // The FILE's line the record starts on — a quoted cell may span lines, so this is
            // not the record's index (c39 subagent P3).
            int line = records.get(i).line();
            if (cells.size() == 1 && cells.get(0).isEmpty()) {
                continue; // a trailing or stray empty line is not a row
            }
            if (cells.size() != header.size()) {
                throw new PrincipalBatchRequestException(400, "line " + line + ": " + cells.size()
                        + " cell(s) for " + header.size() + " column(s)");
            }
            Map<String, String> byColumn = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                byColumn.put(header.get(c), cells.get(c));
            }
            String id = byColumn.get(idColumn);
            if (id == null || id.isBlank()) {
                throw new PrincipalBatchRequestException(400, "line " + line + ": '" + idColumn
                        + "' is blank");
            }
            // Memberships name a (group, member) pair per row; the same group may appear on many
            // rows for add / remove, but replace states the WHOLE list once.
            String uniqueness = kind == PrincipalBatch.Kind.MEMBERSHIPS
                    && operation != PrincipalBatch.Operation.REPLACE
                    ? id.trim() + "\u0000" + String.valueOf(byColumn.get("memberId")).trim()
                    : id.trim();
            if (!ids.add(uniqueness)) {
                throw new PrincipalBatchRequestException(400, "line " + line + ": '" + id.trim()
                        + "' appears more than once in this file");
            }
            rows.add(new PrincipalBatch.Row(line, id.trim(), byColumn));
        }
        return new Parsed(header, rows);
    }

    private static String decodeUtf8(byte[] bytes) {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new PrincipalBatchRequestException(400, "the CSV is not valid UTF-8");
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return text;
    }

    /** One record and the file line it starts on. */
    record Record(int line, List<String> cells) { }

    /** RFC 4180 records: quoted cells may hold commas, newlines and doubled quotes. */
    static List<Record> records(String text) {
        List<Record> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellWasQuoted = false;
        int line = 1;
        int recordStart = 1;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    continue;
                }
                if (ch == '\n') {
                    line++;
                }
                cell.append(ch);
                i++;
                continue;
            }
            if (ch == '"') {
                if (cell.length() > 0) {
                    throw new PrincipalBatchRequestException(400, "line " + line
                            + ": a quote may only open a cell");
                }
                quoted = true;
                cellWasQuoted = true;
                i++;
                continue;
            }
            if (ch == ',') {
                record.add(cell.toString());
                cell.setLength(0);
                cellWasQuoted = false;
                i++;
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                record.add(cell.toString());
                cell.setLength(0);
                cellWasQuoted = false;
                records.add(new Record(recordStart, record));
                record = new ArrayList<>();
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                line++;
                recordStart = line;
                i++;
                continue;
            }
            if (cellWasQuoted) {
                throw new PrincipalBatchRequestException(400, "line " + line
                        + ": text after a closing quote");
            }
            cell.append(ch);
            i++;
        }
        if (quoted) {
            throw new PrincipalBatchRequestException(400, "line " + line + ": a quoted cell is not closed");
        }
        if (cell.length() > 0 || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(new Record(recordStart, record));
        }
        return records;
    }
}
