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
package jp.aegif.nemaki.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every user this product creates is filed under {@code /.system/users}.
 *
 * <h2>Why a source scan and not a behaviour test</h2>
 *
 * <p>The last argument of {@code UserItem(id, type, userId, name, password, admin, parentFolderId)}
 * is the PARENT FOLDER. Two creation paths passed {@code null} there — one of them with a
 * comment calling the argument "description", which is presumably how it happened. A user item
 * with no parent is invisible under {@code /.system/users}, because the {@code children} view
 * keys on {@code parentId}: the account exists, authenticates, and cannot be seen or managed
 * through the folder that is supposed to list it.
 *
 * <p>Nothing fails when that happens. No exception, no log, and every test that creates a user
 * through the API still passes, because the API path always passed the folder. What was wrong
 * was the ARGUMENT at a handful of call sites, so that is what is checked: every construction
 * of a user item must name a parent folder.
 */
class UserItemFilingTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** {@code new UserItem(...)} calls with seven arguments, across line breaks. */
    private static final Pattern SEVEN_ARG_CONSTRUCTION = Pattern.compile(
            "new\\s+UserItem\\s*\\(((?:[^()]|\\([^()]*\\))*?)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("UserItem の生成は必ず親フォルダを渡す (null は /.system/users から消える)")
    void everyUserItemNamesItsParentFolder() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!src.contains("new UserItem")) {
                    continue;
                }
                Matcher m = SEVEN_ARG_CONSTRUCTION.matcher(src);
                while (m.find()) {
                    List<String> args = splitArguments(m.group(1));
                    if (args.size() != 7) {
                        continue; // the copy constructor and the no-arg form file nothing
                    }
                    String parent = stripComments(args.get(6)).trim();
                    if (parent.equals("null")) {
                        offenders.add(MAIN.relativize(p) + " → parentFolderId = null");
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a user created with no parent folder never appears under /.system/users —"
                        + " pass contentService.getOrCreateSystemSubFolder(repositoryId,"
                        + " \"users\").getId()");
    }

    /** The scan finds the real call sites, so an empty result cannot pass by accident. */
    @Test
    @DisplayName("走査が実際の生成箇所を捕まえている (空振りで緑にならない)")
    void theScanActuallySeesTheCallSites() throws IOException {
        int seen = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = SEVEN_ARG_CONSTRUCTION.matcher(src);
                while (m.find()) {
                    if (splitArguments(m.group(1)).size() == 7) {
                        seen++;
                    }
                }
            }
        }
        assertTrue(seen >= 5, "expected the product to build user items in several places, saw " + seen);
    }

    /** Splits on top-level commas, so a nested call counts as one argument. */
    private static List<String> splitArguments(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString());
        }
        return out;
    }

    /** Trailing {@code // …} comments are part of the matched text; the value is not. */
    private static String stripComments(String argument) {
        StringBuilder out = new StringBuilder();
        for (String line : argument.split("\n")) {
            int slashes = line.indexOf("//");
            out.append(slashes >= 0 ? line.substring(0, slashes) : line).append(' ');
        }
        return out.toString();
    }
}
