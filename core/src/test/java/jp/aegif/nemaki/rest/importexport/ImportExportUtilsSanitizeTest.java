package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImportExportUtils#sanitizeExportName} — the export-side
 * name hardening (security audit follow-up). CMIS object names are
 * user-controllable and were previously used verbatim to build filesystem
 * paths and ZIP entries, allowing path traversal / unsafe entries. The
 * sanitized result must always be a single safe segment.
 */
class ImportExportUtilsSanitizeTest {

    private static String s(String in) {
        return ImportExportUtils.sanitizeExportName(in);
    }

    @Test
    void stripsForwardAndBackSlashes() {
        assertFalse(s("a/b/c").contains("/"));
        assertFalse(s("a\\b\\c").contains("\\"));
        assertEquals("a_b_c", s("a/b/c"));
    }

    @Test
    void neutralizesTraversalTokens() {
        // No ".." token may survive in any form.
        assertFalse(s("../../etc/passwd").contains(".."));
        assertFalse(s("..").contains(".."));
        assertFalse(s("foo/../bar").contains(".."));
        // And the result never starts with a dot.
        assertFalse(s("../x").startsWith("."));
    }

    @Test
    void stripsColonAndControlChars() {
        assertFalse(s("C:evil").contains(":"));
        assertFalse(s("a\r\nb").contains("\r"));
        assertFalse(s("a\r\nb").contains("\n"));
        // A NUL (ISO control char) must be replaced, not preserved.
        String nulName = "a" + '\0' + "b";
        assertFalse(s(nulName).contains("\0"), "NUL must be stripped");
    }

    @Test
    void spacesArePreservedAsLegitimate() {
        // A space is not dangerous; legitimate names keep their spaces.
        assertEquals("a b", s("a b"));
    }

    @Test
    void blankFallsBackToPlaceholder() {
        assertEquals("_unnamed", s(null));
        assertEquals("_unnamed", s(""));
        assertEquals("_unnamed", s("   "));    // whitespace only -> trimmed -> placeholder
    }

    @Test
    void dotOnlyNamesAreNeutralizedToSafeSegment() {
        // The exact value isn't important; the security properties are: no
        // traversal token, no leading dot, non-empty single segment.
        for (String in : new String[]{".", "..", "...", "...."}) {
            String out = s(in);
            assertFalse(out.isEmpty(), "non-empty for: " + in);
            assertFalse(out.contains(".."), "no '..' for: " + in);
            assertFalse(out.startsWith("."), "no leading dot for: " + in);
        }
    }

    @Test
    void legitimateNamesPassThroughMostlyUnchanged() {
        assertEquals("report.pdf", s("report.pdf"));
        assertEquals("My Document 2026.docx", s("My Document 2026.docx"));
        assertEquals("画像.png", s("画像.png"));
    }

    @Test
    void resultIsAlwaysSingleNonEmptySegment() {
        for (String in : new String[]{"../../x", "a/b", "C:\\Windows\\x", "..", ".",
                "normal", "a..b", "...."}) {
            String out = s(in);
            assertFalse(out.isEmpty(), "must be non-empty for: " + in);
            assertFalse(out.contains("/"), "no '/' for: " + in);
            assertFalse(out.contains("\\"), "no '\\\\' for: " + in);
            assertFalse(out.contains(".."), "no '..' for: " + in);
            assertFalse(out.startsWith("."), "no leading dot for: " + in);
            assertFalse(out.contains("\0"), "no NUL for: " + in);
        }
    }
}
