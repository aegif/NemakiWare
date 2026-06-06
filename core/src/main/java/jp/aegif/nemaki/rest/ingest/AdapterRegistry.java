package jp.aegif.nemaki.rest.ingest;

import java.util.*;

/**
 * Registry of all supported external ingest adapters.
 *
 * <p>This is the single source of truth for adapter metadata, replacing
 * hardcoded switch statements, free-text sourceSystem input, and
 * hand-maintained Help text.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@link #get(String)} — look up by sourceSystem</li>
 *   <li>{@link #all()} — enumerate all adapters (for UI dropdowns, Help)</li>
 *   <li>{@link #forArchetype(SourceArchetype)} — filter by archetype</li>
 *   <li>{@link #isRegistered(String)} — check if a sourceSystem is valid</li>
 * </ul>
 */
public final class AdapterRegistry {

    private static final Map<String, AdapterDescriptor> DESCRIPTORS;

    static {
        var map = new LinkedHashMap<String, AdapterDescriptor>();

        // ── MESSAGE_CONTEXT ──
        map.put("imap", new AdapterDescriptor(
                "imap", "IMAP",
                SourceArchetype.MESSAGE_CONTEXT,
                Set.of(),
                Set.of("mailbox", "limit"),
                List.of(),
                3, "{\"mailbox\":\"INBOX\"}"));

        map.put("gmail_mail", new AdapterDescriptor(
                "gmail_mail", "Gmail",
                SourceArchetype.MESSAGE_CONTEXT,
                Set.of(),
                Set.of("query", "limit"),
                List.of(),
                3, "{\"query\":\"in:inbox is:unread\"}"));

        // userId is optional (absent → /me delegated auth, present → /users/{id} client credentials).
        // folderId is optional (defaults to "inbox" at runtime).
        // Webhook scope: profiles must declare at least one scope key to prevent wildcard matching.
        map.put("m365_mail", new AdapterDescriptor(
                "m365_mail", "M365 Mail",
                SourceArchetype.MESSAGE_CONTEXT,
                Set.of(),
                Set.of("userId", "folderId", "limit"),
                List.of("userId", "folderId"),
                3, "{\"userId\":\"user@contoso.com\",\"folderId\":\"inbox\"}"));

        // ── CHAT_CONTEXT ──
        map.put("slack", new AdapterDescriptor(
                "slack", "Slack",
                SourceArchetype.CHAT_CONTEXT,
                Set.of("channelId"),
                Set.of("limit"),
                List.of("channelId"),
                3, "{\"channelId\":\"C01ABCD2345\"}"));

        map.put("teams", new AdapterDescriptor(
                "teams", "Microsoft Teams",
                SourceArchetype.CHAT_CONTEXT,
                Set.of("teamId", "channelId"),
                Set.of("limit"),
                List.of("teamId", "channelId"),
                3, "{\"teamId\":\"...\",\"channelId\":\"...\"}"));

        map.put("mattermost", new AdapterDescriptor(
                "mattermost", "Mattermost",
                SourceArchetype.CHAT_CONTEXT,
                Set.of("channelId"),
                Set.of("limit"),
                List.of("channelId"),
                3, "{\"channelId\":\"...\"}"));

        map.put("chatwork", new AdapterDescriptor(
                "chatwork", "Chatwork",
                SourceArchetype.CHAT_CONTEXT,
                Set.of("roomId"),
                Set.of("limit"),
                List.of("roomId"),
                3, "{\"roomId\":\"123456\"}"));

        // ── COMPOUND_NOTE ──
        map.put("notion", new AdapterDescriptor(
                "notion", "Notion",
                SourceArchetype.COMPOUND_NOTE,
                Set.of(),
                Set.of("query", "limit"),
                List.of(),
                5, "{\"query\":\"status:published\"}"));

        // ── BUSINESS_RECORD ──
        map.put("salesforce", new AdapterDescriptor(
                "salesforce", "Salesforce",
                SourceArchetype.BUSINESS_RECORD,
                Set.of(),
                Set.of("soql", "limit"),
                List.of(),
                2, "{\"soql\":\"SELECT Id,Name,LastModifiedDate FROM Account\"}"));

        // ── FILE_SHARE ──
        // Box webhooks carry the parent folder id, so folderId is a webhook
        // scope key — a notification only triggers a profile watching that folder.
        map.put("box", new AdapterDescriptor(
                "box", "Box",
                SourceArchetype.FILE_SHARE,
                Set.of(),
                Set.of("folderId", "limit"),
                List.of("folderId"),
                2, "{\"folderId\":\"0\"}"));

        // Dropbox notifications carry no path (just "something changed for these
        // accounts"), so there are no webhook scope keys — every profile on the
        // connector does an incremental (cursor) fetch.
        map.put("dropbox", new AdapterDescriptor(
                "dropbox", "Dropbox",
                SourceArchetype.FILE_SHARE,
                Set.of(),
                Set.of("folderPath", "limit"),
                List.of(),
                2, "{\"folderPath\":\"/Documents\"}"));

        // google_drive and onedrive use cloud drive sync, not scheduled fetch
        map.put("google_drive", new AdapterDescriptor(
                "google_drive", "Google Drive",
                SourceArchetype.FILE_SHARE,
                Set.of(), Set.of(), List.of(), 2, "{}"));

        map.put("onedrive", new AdapterDescriptor(
                "onedrive", "OneDrive",
                SourceArchetype.FILE_SHARE,
                Set.of(), Set.of(), List.of(), 2, "{}"));

        DESCRIPTORS = Collections.unmodifiableMap(map);
    }

    private AdapterRegistry() {}

    /** Get descriptor by sourceSystem, or null if not registered. */
    public static AdapterDescriptor get(String sourceSystem) {
        return DESCRIPTORS.get(sourceSystem);
    }

    /** Check if a sourceSystem is registered. */
    public static boolean isRegistered(String sourceSystem) {
        return DESCRIPTORS.containsKey(sourceSystem);
    }

    /** All registered adapters (insertion order). */
    public static Collection<AdapterDescriptor> all() {
        return DESCRIPTORS.values();
    }

    /** All registered source system identifiers. */
    public static Set<String> allSourceSystems() {
        return DESCRIPTORS.keySet();
    }

    /** Adapters for a specific archetype. */
    public static List<AdapterDescriptor> forArchetype(SourceArchetype archetype) {
        return DESCRIPTORS.values().stream()
                .filter(d -> d.archetype() == archetype)
                .toList();
    }
}
