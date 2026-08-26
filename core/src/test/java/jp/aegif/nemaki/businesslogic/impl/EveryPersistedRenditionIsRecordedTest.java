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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rendition this service persists has to go through the place that records it (P3-2).
 *
 * <h2>Why a source scan and not a mock</h2>
 *
 * <p>What went wrong was not a wrong answer from a method. It was a SECOND way to persist a
 * rendition — {@code createPreviewRendition} took an already-converted stream — so three REST
 * stacks converted, persisted, and recorded nothing. Every unit test of the recording path
 * passed the whole time, because they were testing the path that had it.
 *
 * <p>A test of behaviour cannot see a bypass; it can only see the road it is driven down. What
 * is checkable is the shape: {@code contentDaoService.createRendition} is called from exactly
 * one method, and that method records. Add a fourth caller and this fails, which is the moment
 * to notice — not after it ships and a design document says the path is out of scope.
 *
 * <p>The scan is deliberately narrow: one file, one method name, an exact string. It is not a
 * lint rule and should not grow into one.
 */
class EveryPersistedRenditionIsRecordedTest {

    private static final Path SOURCE = Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/"
            + "ContentServiceImpl.java");

    private static final String RECORDING_METHOD = "storeRenditionAndRecordDuplication";

    private static String source() throws Exception {
        assertTrue(Files.exists(SOURCE), "the scanned file has moved: " + SOURCE.toAbsolutePath());
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    /** The method a line belongs to, by walking back to the nearest signature. */
    private static String enclosingMethod(String text, int offset) {
        Matcher m = Pattern.compile("(?m)^\\t(?:public |private |protected )[^;{]*?(\\w+)\\(")
                .matcher(text);
        String current = "<top level>";
        while (m.find() && m.start() < offset) {
            current = m.group(1);
        }
        return current;
    }

    @Test
    @DisplayName("only the recording method persists a rendition")
    void oneWayIn() throws Exception {
        String text = source();
        List<String> callers = new ArrayList<>();
        Matcher m = Pattern.compile("contentDaoService\\.createRendition\\(").matcher(text);
        while (m.find()) {
            callers.add(enclosingMethod(text, m.start()));
        }

        assertEquals(List.of(RECORDING_METHOD), callers,
                "a rendition is persisted from somewhere that does not record the duplication. "
                        + "That is exactly how the three REST rendition stacks came to persist "
                        + "derived copies with nothing in the chain. Callers found: " + callers);
    }

    /**
     * Keeps the test above from becoming vacuous if the recording is deleted.
     *
     * <p><b>What it does not catch:</b> a call that is present but unreachable. Wrapping the
     * recording in {@code if (false)} leaves this green — measured, not assumed. Reachability
     * is what {@code ContentServiceImplCreatePreviewRenditionTest.recordsTheDuplicationItJustMade}
     * measures, by running the method and looking at the recorder.
     */
    @Test
    @DisplayName("the recording method does record")
    void andItRecords() throws Exception {
        String text = source();
        int start = text.indexOf("private String " + RECORDING_METHOD + "(");
        assertTrue(start > 0, RECORDING_METHOD + " is gone, so the test above proves nothing");
        int next = text.indexOf("\n\t/**", start);
        String body = next < 0 ? text.substring(start) : text.substring(start, next);

        assertTrue(body.contains("recordFormatDuplication("),
                "the one method that persists renditions no longer records the duplication");
    }
}
