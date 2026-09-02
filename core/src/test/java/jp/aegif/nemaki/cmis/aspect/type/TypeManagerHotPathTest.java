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
package jp.aegif.nemaki.cmis.aspect.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl;

/**
 * {@code getTypeDefinition} runs once per object in every response — it must not serialise.
 *
 * <h2>What went wrong, and why a test</h2>
 *
 * <p>Two lines on that path each cost the whole server. One was a {@code log.warn} with no
 * {@code isDebugEnabled} guard, which a production configuration really does emit: the console
 * appender is process-wide, and a thread dump under load found 11 of 16 request threads queued
 * inside that single write. The other was {@code cleanupTimedOutTypes()}, which took the
 * process-wide {@code initLock} on every call just to discover that nothing was being deleted —
 * and on virtual threads a {@code synchronized} block that blocks pins its carrier.
 *
 * <p>Neither shows up as a failing test; both show up as a server that will not scale past one
 * core's worth of console I/O. So the checks here are (a) the counter that lets the fast path
 * skip the monitor really does track the map it stands for — if a future mutation site forgets
 * to update it, cleanup silently stops happening — and (b) the unguarded log line has not
 * come back.
 */
class TypeManagerHotPathTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java");

    private static int pendingDeletions(TypeManagerImpl tm) throws ReflectiveOperationException {
        Field f = TypeManagerImpl.class.getDeclaredField("pendingDeletions");
        f.setAccessible(true);
        return f.getInt(tm);
    }

    @Test
    @DisplayName("pendingDeletions が削除中マップと一致し続ける (ずれると掃除が止まる)")
    void theFastPathCounterTracksTheMap() throws ReflectiveOperationException {
        TypeManagerImpl tm = new TypeManagerImpl();
        assertEquals(0, pendingDeletions(tm), "a fresh manager is deleting nothing");

        tm.markTypeBeingDeleted("test:alpha");
        assertEquals(1, pendingDeletions(tm));
        tm.markTypeBeingDeleted("test:beta");
        assertEquals(2, pendingDeletions(tm));

        tm.unmarkTypeBeingDeleted("test:alpha");
        assertEquals(1, pendingDeletions(tm));
        tm.unmarkTypeBeingDeleted("test:beta");
        assertEquals(0, pendingDeletions(tm),
                "back to zero, so getTypeDefinition stops taking initLock again");
    }

    @Test
    @DisplayName("何も削除中でなければ cleanupTimedOutTypes は monitor を取らずに帰る")
    void cleanupReturnsWithoutTheMonitorWhenIdle() throws Exception {
        TypeManagerImpl tm = new TypeManagerImpl();

        // Hold initLock from another thread. If cleanup still tried to acquire it, this call
        // would block until the holder let go — which is exactly the contention being removed.
        Field lockField = TypeManagerImpl.class.getDeclaredField("initLock");
        lockField.setAccessible(true);
        Object initLock = lockField.get(null);

        Thread holder = new Thread(() -> {
            synchronized (initLock) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(100L); // let the holder actually get in

        long start = System.nanoTime();
        tm.cleanupTimedOutTypes();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 500L,
                "cleanup waited " + elapsedMs + "ms for initLock — the idle fast path is gone");
        holder.join(3000L);
    }

    @Test
    @DisplayName("getTypeDefinition にガード無しの WARN が戻っていない")
    void theUnguardedWarnHasNotComeBack() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertFalse(src.contains("log.warn(\"INHERITANCE DEBUG"),
                "a per-call WARN on this path serialises every request thread on the console"
                        + " appender — put diagnostics behind log.isDebugEnabled()");

        // Conditional logging on an anomaly branch is fine — it fires when something is wrong.
        // What must not exist is a logging call in the method body itself, because that one runs
        // on every call, and "every call" here means once per object in every response.
        String body = bodyOf(src,
                "public TypeDefinition getTypeDefinition(String repositoryId, String typeId) {");
        assertEquals(List.of(), unconditionalLogging(body),
                "these run on every getTypeDefinition call — nest them in a guard or a branch");
    }

    /** Logging calls sitting directly in a method body, i.e. inside no {@code if} at all. */
    private static List<String> unconditionalLogging(String body) {
        List<String> found = new ArrayList<>();
        int depth = 0;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            // Depth 1 is the method body itself; anything deeper is inside a branch or guard.
            if (depth == 1 && trimmed.startsWith("log.")) {
                found.add(trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed);
            }
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return found;
    }

    /** The text between a method's opening brace and its matching close. */
    private static String bodyOf(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found — the test needs updating: " + signature);
        int i = source.indexOf('{', start);
        int depth = 0;
        for (int j = i; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(i, j + 1);
                }
            }
        }
        throw new jp.aegif.nemaki.util.test.HarnessBroken(
                "unbalanced braces after " + signature);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
