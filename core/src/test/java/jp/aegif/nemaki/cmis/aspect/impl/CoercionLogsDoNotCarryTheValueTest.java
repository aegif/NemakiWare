package jp.aegif.nemaki.cmis.aspect.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The property-compilation logs name the property, not its value.
 *
 * <p>Two things made this worth measuring rather than just fixing. The deployed configuration
 * is {@code log4j.logger.jp.aegif.nemaki=INFO} ({@code docker/core/log4j.properties}), so a
 * WARN is not a thing an operator opts into — it is what normal running produces. And the
 * values here are document metadata: the coercion arms fire on whatever a repository happens
 * to hold, which in this product is customer numbers, case references, salaries.
 *
 * <p>CodeQL raised {@code java/sensitive-log} on three of these lines. It did not raise the
 * worst ones: two blocks dumped EVERY compiled property value of every object at DEBUG, and
 * three WARN arms printed the rejected String verbatim. Fixing what was flagged would not have
 * reached them, and dismissing what was flagged would not have reached them either — which is
 * why this lock reads the source rather than the alert list.
 *
 * <p>Read as text on purpose. The alternative — capture an appender and assert on the emitted
 * line — measures one arm per test and needs a repository, a document and a property of the
 * wrong type to reach each one. This says the thing directly: no coercion log line interpolates
 * the value it is complaining about.
 *
 * <p>NOT covered, deliberately: {@link jp.aegif.nemaki.util.CoercionAuditLogger} does emit
 * {@code originalValue} (truncated to 200 characters, JSON-escaped) at WARN. That is a designed
 * audit record with its own event type, not stray logging, and removing the value would empty
 * the record of the thing it exists to preserve. So the value still reaches a log through that
 * channel, by decision. This lock does not claim otherwise.
 */
class CoercionLogsDoNotCarryTheValueTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java");

    /** {@code log.warn(...)} / {@code log.debug(...)} calls, with their whole argument list. */
    private static final Pattern LOG_CALL =
            Pattern.compile("log\\.(warn|debug|info|error)\\((.*?)\\);", Pattern.DOTALL);

    /** The locals that hold the property's own value in the coercion arms. */
    private static final List<String> VALUE_HOLDERS = List.of("element", "timestamp", "value");

    @Test
    @DisplayName("no coercion log line interpolates the property value")
    void noCoercionLogLineCarriesTheValue() throws Exception {
        String source = Files.readString(SOURCE);
        List<String> offenders = new ArrayList<>();

        Matcher m = LOG_CALL.matcher(source);
        while (m.find()) {
            String args = m.group(2);
            if (!args.contains("COERCION") && !args.contains("coercion")
                    && !args.contains("compiled properties")) {
                continue;
            }
            for (String holder : VALUE_HOLDERS) {
                // " + element +" — the value concatenated into the message.
                if (args.matches("(?s).*\\+\\s*" + holder + "\\s*\\+.*")
                        || args.matches("(?s).*\\+\\s*" + holder + "\\s*\\)?$")) {
                    offenders.add(holder + " in: " + args.replaceAll("\\s+", " ").trim());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "a coercion log line puts the property's own value into the log — the deployed "
                        + "level is INFO, so this is what normal running writes:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the blanket property dumps are gone")
    void theBlanketPropertyDumpsAreGone() throws Exception {
        // The over-throw guard's opposite: these two blocks are not something to reduce, they
        // are something that should not exist. Named by their own headers so a reinstated copy
        // under a different variable name is still caught.
        String source = Files.readString(SOURCE);

        assertTrue(!source.contains("TCK DEBUG: Final compiled properties")
                        && !source.contains("TCK PROPERTIES AFTER COMPILATION"),
                "a blanket dump of every compiled property value is back in CompileServiceImpl");
    }
}
