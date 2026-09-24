package jp.aegif.nemaki.rest.ingest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The path of a Microsoft Graph link as OData segments — what the Teams and the M365 mail
 * connectors compare when they decide whether a link Graph answered, or one a checkpoint holds, is
 * their own feed.
 *
 * <p>The RAW path is split at {@code /} first, and each segment is percent-decoded on its own. The
 * two copies this replaces decoded the whole path first, so an encoded {@code /} inside a resource
 * id ({@code %2F}) became a separator: a link naming such an id no longer had its shape, and the
 * feed was refused on every poll (review, P2). A {@code +} stays a {@code +} — a path is not a form.
 */
public final class GraphLinkPath {

    private static final Pattern KEY_SEGMENT = Pattern.compile("^([^(]+)\\('(.*)'\\)$");

    private GraphLinkPath() {}

    /**
     * The path's OData segments: {@code name('key')} is two segments (the key's doubled quotes
     * read), {@code delta()} and {@code microsoft.graph.delta()} are {@code delta}, empty segments
     * are dropped. A malformed percent escape is refused with {@link IllegalArgumentException}.
     */
    public static List<String> odataSegments(URI uri) {
        List<String> out = new ArrayList<>();
        String raw = uri == null ? null : uri.getRawPath();
        if (raw == null) return out;
        for (String rawPart : raw.split("/")) {
            if (rawPart.isEmpty()) continue;
            String part = URLDecoder.decode(rawPart.replace("+", "%2B"), StandardCharsets.UTF_8);
            Matcher key = KEY_SEGMENT.matcher(part);
            if (key.matches()) {
                out.add(key.group(1));
                out.add(key.group(2).replace("''", "'"));
                continue;
            }
            String segment = part.endsWith("()") ? part.substring(0, part.length() - 2) : part;
            if (segment.startsWith("microsoft.graph.")) {
                segment = segment.substring("microsoft.graph.".length());
            }
            out.add(segment);
        }
        return out;
    }
}
