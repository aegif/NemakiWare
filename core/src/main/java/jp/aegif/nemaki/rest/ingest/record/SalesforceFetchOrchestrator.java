package jp.aegif.nemaki.rest.ingest.record;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Salesforce: the records the profile's SOQL selects are read in key order — {@code SystemModstamp},
 * then {@code Id} — from the checkpoint on.
 *
 * <p>The previous shape ran the profile's SOQL as written (the default template has a LIMIT and
 * no ORDER BY, so a poll took an arbitrary {@code limit} of the records), added the checkpoint's
 * filter only to a SOQL without a WHERE, read the first batch of the result only, and wrote a
 * checkpoint (the LastModifiedDate as Salesforce writes it, {@code …+0000}) in a form it did not
 * read back: every stored checkpoint was ignored as invalid (R107).
 *
 * <p>Now the profile's SOQL must be {@code SELECT … FROM … [WHERE …]}. The connector adds {@code Id}
 * and {@code SystemModstamp} to the fields when they are missing, puts the profile's condition in
 * parentheses, adds the key condition {@code (SystemModstamp > T OR (SystemModstamp = T AND Id > 'I'))}
 * and orders by {@code SystemModstamp, Id}; a SOQL with its own ORDER BY, LIMIT, OFFSET, GROUP BY,
 * HAVING, FOR, WITH or USING at the top level is refused, and so is one that carries a statement
 * separator, a comment or a word that changes data ({@code DELETE}, {@code UPDATE}, {@code INSERT})
 * outside its quoted strings — as the profile's error, before anything is sent. Only the top-level
 * fields are read for Id and SystemModstamp: a sub-query's or a TYPEOF's own Id is not the record's.
 * SystemModstamp rather than
 * LastModifiedDate: every change sets it, it is indexed, and it cannot be back-dated — a data load
 * can set LastModifiedDate below a watermark. Salesforce stores these timestamps to the second and
 * SOQL writes them to the second; {@code Id} breaks ties, so a second holding any number of records
 * is walked through.
 *
 * <p>The query asks for one record more than a run may try ({@code limit × 4 + 1}, at most 2,000)
 * and every batch of the answer is read ({@code nextRecordsUrl}), up to
 * {@code salesforceQueryMaxRequests} (default 50) requests; the records come in key order, so what
 * a cut request cap leaves read is a prefix and is taken. The answer is checked whole before any
 * record is taken — every record with an Id and a SystemModstamp, in key order from the checkpoint
 * on — and one that is not is refused: nothing is taken and the checkpoint does not move. Within one
 * second the Ids are compared case-sensitively: Salesforce documents its Ids as case-sensitive, and
 * the key's {@code Id > 'I'} is taken to continue in that order (not measured against a real org).
 * Each
 * record settles (imported, or skipped by the import service) or is dead-lettered as read with its
 * JSON — the replay imports it — and the checkpoint moves over it; a failure whose row could not be
 * written stops the run with the checkpoint before it. The budget counts settled records; at most
 * {@code limit × 4} are tried. The checkpoint stops at the listing's start minus
 * {@code salesforceCheckpointLagMinutes} (default 5): Salesforce can make a transaction visible
 * after its timestamp. The records above are imported and come again (the dedupe answers).
 *
 * <p>Where it starts: with no checkpoint, from the first record the query selects. With the
 * LastModifiedDate an earlier version wrote, from the first record too — that checkpoint was never
 * honoured, so it records nothing; the import service's dedupe answers for what was imported. Not
 * covered: deleted records, and records the profile's WHERE stops selecting after they changed.
 */
public class SalesforceFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_QUERY_REQUESTS = "salesforceQueryMaxRequests";
    static final int DEFAULT_MAX_QUERY_REQUESTS = 50;
    static final int MAX_QUERY_REQUESTS = 1_000_000;
    static final String PARAM_CHECKPOINT_LAG_MINUTES = "salesforceCheckpointLagMinutes";
    static final int DEFAULT_CHECKPOINT_LAG_MINUTES = 5;
    static final int MAX_CHECKPOINT_LAG_MINUTES = 30 * 24 * 60;
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** Salesforce's own cap on the records one query batch carries. */
    static final int MAX_QUERY_LIMIT = 2000;
    static final String KEY = "salesforce";
    static final String KEY_PREFIX = "key:";
    static final String DEFAULT_SOQL = "SELECT Id, Name FROM Account";
    private static final Pattern SALESFORCE_ID = Pattern.compile("[a-zA-Z0-9]{15,18}");
    /** A SOQL dateTime literal: to the second, in UTC, unquoted. */
    private static final java.time.format.DateTimeFormatter SOQL_DATETIME = java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC);

    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = new tools.jackson.databind.ObjectMapper();

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by endpoint and token; tests point it at a local stub of the Salesforce API. */
    java.util.function.BiFunction<String, String, SalesforceConnectorAdapter> adapterFactory = SalesforceConnectorAdapter::new;
    /** The clock the cap is taken from; tests fix it. */
    java.time.Clock clock = java.time.Clock.systemUTC();

    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCheckpointManager(CheckpointManager checkpointManager) { this.checkpointManager = checkpointManager; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }

    @Override
    public String sourceSystem() { return "salesforce"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Salesforce connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    /** A SOQL query of the one shape this connector can page by key. */
    record Soql(String fields, String from, String where) {}

    /**
     * {@code SELECT <fields> FROM <object> [WHERE <condition>]}, read at the top level — outside
     * parentheses and quoted strings, so a sub-query or a literal is not taken for a clause. Anything
     * after the condition that is a clause of its own is refused: the order, the limit and the
     * position are the connector's.
     */
    static Soql parse(String soql) {
        String text = soql == null ? "" : soql.trim();
        // The adapter refuses these too, before it sends; refused HERE they are the profile's error.
        // Refused there, the run reported a "connection failed" for a SOQL the operator wrote (review, P2).
        String prohibited = SalesforceConnectorAdapter.prohibitedIn(text);
        if (prohibited != null) {
            throw new IllegalArgumentException("the profile's Salesforce SOQL carries '" + prohibited
                    + "' outside a quoted string; a query is one read-only statement, and nothing was read");
        }
        List<int[]> words = topLevelWords(text);
        int select = -1, from = -1, where = -1;
        for (int[] w : words) {
            String word = text.substring(w[0], w[1]).toUpperCase(Locale.ROOT);
            switch (word) {
                case "SELECT" -> { if (select < 0 && w[0] == 0) select = w[0]; }
                case "FROM" -> { if (from < 0) from = w[0]; }
                case "WHERE" -> { if (from >= 0 && where < 0) where = w[0]; }
                case "ORDER", "LIMIT", "OFFSET", "GROUP", "HAVING", "FOR", "WITH", "USING", "UPDATE", "ALL" ->
                        throw new IllegalArgumentException("the Salesforce SOQL must be SELECT … FROM … [WHERE …]; it carries "
                                + word + " at the top level, and the order, the limit and the position are the connector's");
                default -> { }
            }
        }
        if (select != 0 || from < 0) {
            throw new IllegalArgumentException("the Salesforce SOQL must be SELECT … FROM … [WHERE …]: '" + text + "'");
        }
        String fields = text.substring("SELECT".length(), from).trim();
        String object = (where < 0 ? text.substring(from + 4) : text.substring(from + 4, where)).trim();
        String condition = where < 0 ? null : text.substring(where + 5).trim();
        if (fields.isEmpty() || object.isEmpty() || !object.matches("[A-Za-z_][A-Za-z0-9_]*") || (condition != null && condition.isEmpty())) {
            throw new IllegalArgumentException("the Salesforce SOQL must be SELECT <fields> FROM <object> [WHERE <condition>]: '" + text + "'");
        }
        return new Soql(fields, object, condition);
    }

    /** The words at the top level of a SOQL string: [start, end) of each, outside parentheses and quotes. */
    private static List<int[]> topLevelWords(String text) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '\'') quoted = false;
                continue;
            }
            if (c == '\'') { quoted = true; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && Character.isLetter(c) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)) && text.charAt(i - 1) != '_')) {
                int end = i;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
                out.add(new int[] {i, end});
                i = end - 1;
            }
        }
        return out;
    }

    /**
     * The fields with Id and SystemModstamp added when the profile's list does not name them at the
     * top level. Split at every comma, the list took a sub-query's {@code Id}
     * ({@code (SELECT Name, Id FROM Contacts)}) for the record's, added none, and the answer — its
     * records without an Id — was refused on every poll.
     */
    static String withKeyFields(String fields) {
        List<String> names = new ArrayList<>();
        for (String f : topLevelFields(fields)) names.add(f.toUpperCase(Locale.ROOT));
        StringBuilder out = new StringBuilder(fields.trim());
        if (!names.contains("ID")) out.append(", Id");
        if (!names.contains("SYSTEMMODSTAMP")) out.append(", SystemModstamp");
        return out.toString();
    }

    /**
     * The items of a SELECT list at the top level: split at the commas outside parentheses, quoted
     * strings and {@code TYPEOF … END} — whose {@code THEN} lists are the related object's fields.
     */
    static List<String> topLevelFields(String fields) {
        List<String> out = new ArrayList<>();
        int depth = 0, typeOf = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < fields.length(); i++) {
            char c = fields.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '\'') quoted = false;
                continue;
            }
            if (c == '\'') { quoted = true; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && Character.isLetter(c) && (i == 0 || !isWordChar(fields.charAt(i - 1)))) {
                int end = i;
                while (end < fields.length() && isWordChar(fields.charAt(end))) end++;
                String word = fields.substring(i, end).toUpperCase(Locale.ROOT);
                if (word.equals("TYPEOF")) typeOf++;
                else if (word.equals("END") && typeOf > 0) typeOf--;
                i = end - 1;
                continue;
            }
            if (c == ',' && depth == 0 && typeOf == 0) {
                out.add(fields.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(fields.substring(start).trim());
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The key a checkpoint names: the SystemModstamp to the second, and the Id. */
    record Key(Instant at, String id) {}

    /** A SystemModstamp as Salesforce writes it ({@code …+0000}), or null when it cannot be read. */
    static Instant readTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XX"))
                    .toInstant();
        } catch (java.time.format.DateTimeParseException notSalesforcesForm) {
            try {
                return java.time.OffsetDateTime.parse(value).toInstant();
            } catch (java.time.format.DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    /** The SOQL the connector sends: the profile's, in key order, from the checkpoint, at most {@code n}. */
    static String keyed(Soql soql, Key from, int n) {
        StringBuilder q = new StringBuilder("SELECT ").append(withKeyFields(soql.fields())).append(" FROM ").append(soql.from());
        List<String> conditions = new ArrayList<>();
        if (soql.where() != null) conditions.add("(" + soql.where() + ")");
        if (from != null) {
            String at = SOQL_DATETIME.format(from.at());
            conditions.add("(SystemModstamp > " + at + " OR (SystemModstamp = " + at + " AND Id > '" + from.id() + "'))");
        }
        if (!conditions.isEmpty()) q.append(" WHERE ").append(String.join(" AND ", conditions));
        q.append(" ORDER BY SystemModstamp ASC, Id ASC LIMIT ").append(n);
        return q.toString();
    }

    private enum Outcome { SETTLED, FAILED_RECORDED, FAILED_UNRECORDED }

    /** The per-record counts one run adds to — per run, not per orchestrator: runs share it. */
    private static final class Counts { int imported, skipped; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String rawSoql = params == null ? DEFAULT_SOQL : params.getOrDefault("soql", DEFAULT_SOQL);

        // resolvePasswordOrRefuse: a configuration read that FAILED used to arrive here as
        // "no token", which this method states as a fact. The scheduler counts that towards
        // opening the connector's circuit breaker and the folder endpoint turns it into
        // authError=true, prompting an admin to overwrite a credential that was never wrong.
        //
        // The refusal is NOT caught here. An earlier version of this comment said it lands in
        // this orchestrator's outer catch — it does not, this call is above the try — and a
        // review found the sentence false in all eleven copies. The outer catch below rethrows
        // it explicitly, so the scheduler can tell a configuration outage from the connector
        // failing and leave the circuit breaker alone.
        String token = fetchSupport.resolvePasswordOrRefuse(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Salesforce connector"));

        int maxRequests;
        int lagMinutes;
        Soql soql;
        try {
            maxRequests = intParam(params, PARAM_MAX_QUERY_REQUESTS, DEFAULT_MAX_QUERY_REQUESTS, 1, MAX_QUERY_REQUESTS);
            lagMinutes = intParam(params, PARAM_CHECKPOINT_LAG_MINUTES, DEFAULT_CHECKPOINT_LAG_MINUTES, 0, MAX_CHECKPOINT_LAG_MINUTES);
            soql = parse(rawSoql);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing would silently read
            // something other than what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        String before = null;
        Key reached = null;
        boolean unrecorded = false;
        try {
            // RC6.8 P2: re-validate the connector endpoint at runtime as
            // defense-in-depth. ConnectorDefinitionServiceImpl validates on
            // save, but an endpoint that was saved before this hardening
            // landed, or modified at storage level, would otherwise reach
            // the adapter without revalidation. sendWithRetry inside the
            // adapter will validate + IP-pin again (RC6.8 P1), but failing
            // early here gives a clearer audit message and avoids building
            // the adapter for an obviously-bad endpoint.
            jp.aegif.nemaki.rest.ingest.AdapterHttpClient.validateExternalUrl(connector.getEndpoint());
            var sf = adapterFactory.apply(connector.getEndpoint(), token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), KEY);
            before = stored;
            String value = stored == null ? "" : stored.trim();
            Key from = null;
            if (value.startsWith(KEY_PREFIX)) {
                String rest = value.substring(KEY_PREFIX.length());
                int bar = rest.indexOf('|');
                Instant at = bar < 0 ? null : readTimestamp(rest.substring(0, bar));
                String id = bar < 0 ? null : rest.substring(bar + 1);
                // The Id goes into the SOQL as a literal: only a Salesforce Id is written there.
                if (at == null || id == null || !SALESFORCE_ID.matcher(id).matches()) {
                    FetchSupport.addError(errors, "Salesforce checkpoint '" + stored + "' is not a key this connector wrote; "
                            + "correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                from = new Key(at, id);
            } else if (!value.isEmpty()) {
                // The LastModifiedDate an earlier version wrote. It never matched the form that
                // version read back, so it was ignored on every poll and records nothing: the first
                // record the query selects is where this version starts. A value that is not even a
                // timestamp is NOT "no checkpoint" — reported, and nothing is read.
                if (readTimestamp(value) == null) {
                    FetchSupport.addError(errors, "Salesforce checkpoint '" + stored + "' is neither a key nor a timestamp this "
                            + "connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
            }
            Instant listingStartedAt = clock.instant();
            Instant cap = listingStartedAt.minus(lagMinutes, java.time.temporal.ChronoUnit.MINUTES);
            // One more than a run may try: the extra record says whether newer ones are left.
            int n = Math.max(1, Math.min(MAX_QUERY_LIMIT, limit * ATTEMPTS_PER_BUDGET + 1));

            // The answer, every batch of it up to the request cap. The records come in key order,
            // so a cut leaves a prefix read, and a prefix is taken.
            List<SalesforceConnectorAdapter.SalesforceRecord> records = new ArrayList<>();
            SalesforceConnectorAdapter.QueryPage page = sf.queryPage(keyed(soql, from, n));
            int requests = 1;
            records.addAll(page.records());
            while (page.nextRecordsUrl() != null) {
                if (requests >= maxRequests) {
                    incompleteReads.add("Salesforce: the cap of " + maxRequests + " query request(s) was reached with the answer "
                            + "still going (raise the profile's " + PARAM_MAX_QUERY_REQUESTS + " parameter); the records read "
                            + "are taken, and the next poll continues from the checkpoint");
                    break;
                }
                page = sf.nextPage(page.nextRecordsUrl());
                requests++;
                records.addAll(page.records());
            }
            fetched = records.size();

            // The answer, checked whole before anything on it is taken: a record the key cannot
            // place, or one out of key order, means the answer is not the prefix it must be — taking
            // part of it would move the checkpoint over a record not yet taken.
            List<Instant> stamps = new ArrayList<>();
            Instant last = from == null ? null : from.at();
            String lastId = from == null ? null : from.id();
            for (var rec : records) {
                Instant stamp = readTimestamp(asText(rec.fields().get("SystemModstamp")));
                // To the second: what Salesforce stores and what a SOQL literal carries.
                Instant at = stamp == null ? null : stamp.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                if (rec.id() == null || rec.id().isBlank() || at == null) {
                    FetchSupport.addError(errors, "Salesforce answered a record without an Id or a SystemModstamp this connector can "
                            + "read ('" + rec.id() + "'); nothing on the answer was taken");
                    return new FetchResult(fetched, 0, 0, errors, List.copyOf(incompleteReads));
                }
                if (last != null && at.isBefore(last)) {
                    FetchSupport.addError(errors, "Salesforce answered record " + rec.id() + " out of SystemModstamp order; "
                            + "nothing on the answer was taken");
                    return new FetchResult(fetched, 0, 0, errors, List.copyOf(incompleteReads));
                }
                // Within one second, the Id: an answer whose records of one second — or whose first
                // record, against the checkpoint — are not in Id order would move the checkpoint past
                // a record not yet taken; the next query's Id > 'I' would never select it (review, P1).
                if (last != null && at.equals(last) && lastId != null && rec.id().compareTo(lastId) <= 0) {
                    FetchSupport.addError(errors, "Salesforce answered record " + rec.id() + " out of Id order within its "
                            + "SystemModstamp second (after " + lastId + "); nothing on the answer was taken");
                    return new FetchResult(fetched, 0, 0, errors, List.copyOf(incompleteReads));
                }
                last = at;
                lastId = rec.id();
                stamps.add(at);
            }

            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            int settled = 0, attempted = 0;
            reached = from;
            boolean stopped = false;
            for (int i = 0; i < records.size(); i++) {
                var rec = records.get(i);
                Instant at = stamps.get(i);
                if (settled >= limit) {
                    incompleteReads.add("Salesforce: the run's limit of " + limit + " record(s) was reached; the newer records "
                            + "are left for the next poll");
                    stopped = true;
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    incompleteReads.add("Salesforce: " + attempted + " record(s) were attempted (" + ATTEMPTS_PER_BUDGET
                            + " × the limit of " + limit + ") with only " + settled + " settled — the failures are in the "
                            + "dead-letter queue; the newer records are reached on a later poll");
                    stopped = true;
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                Outcome outcome = attempt(callContext, profile, connector, rec, errors, counts);
                if (outcome == Outcome.FAILED_UNRECORDED) {
                    unrecorded = true;
                    stopped = true;
                    break;
                }
                if (outcome == Outcome.SETTLED) settled++;
                // Passed — settled, or recorded. The checkpoint follows while the record is at or
                // below the cap; above it, the record comes again next poll.
                if (!at.isAfter(cap)) reached = new Key(at, rec.id());
            }
            if (!stopped && records.size() >= n && page.nextRecordsUrl() == null) {
                // Every record of a full answer was taken: the query's own limit stopped it.
                incompleteReads.add("Salesforce: the query's limit of " + n + " record(s) was reached; the newer records are "
                        + "left for the next poll");
            }
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker, and which the folder and trigger endpoints
            // repeat back as the connector being in trouble. The credential half was exempted
            // a round earlier by catching it in the scheduler; a review found the checkpoint
            // half never reaching there because this catch stood in the way.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Salesforce connection failed: " + e.getMessage());
        }
        if (unrecorded) {
            FetchSupport.addError(errors, "a Salesforce failure could not be dead-lettered; the checkpoint holds before it "
                    + "so that it is offered again");
        }
        if (reached != null) {
            String next = KEY_PREFIX + SOQL_DATETIME.format(reached.at()) + "|" + reached.id();
            if (!next.equals(before)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), KEY, next);
            }
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * One record: its fields as JSON, imported. A failure — the import refused, or threw — is
     * dead-lettered as read WITH the JSON, so the replay imports it; RECORDED / UNRECORDED says
     * whether the row was written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            SalesforceConnectorAdapter.SalesforceRecord rec, List<String> errors, Counts counts) {
        byte[] json;
        try {
            json = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rec.fields()).getBytes(StandardCharsets.UTF_8);
        } catch (Exception unwritable) {
            json = null;
        }
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId(profile.getProfileId());
        req.setConnectorId(connector.getConnectorId());
        req.setRepositoryId(profile.getRepositoryId());
        req.setSourceObjectId(rec.id());
        req.setSourceObjectType("record");
        req.setFileName(FetchSupport.sanitizeSubject(rec.name()) + ".json");
        req.setMimeType("application/json");
        req.setExecutionMode("scheduled");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recordType", rec.type());
        metadata.put("recordId", rec.id());
        metadata.put("recordUrl", connector.getEndpoint() + "/" + rec.id());
        req.setMetadata(metadata);
        if (json == null) {
            FetchSupport.addError(errors, "SF record " + rec.id() + ": its fields could not be written as JSON");
            return fetchSupport.saveSourceReadToDlq(req, "SF record " + rec.id() + ": its fields could not be written as JSON")
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        }
        req.setContentStream(new ByteArrayInputStream(json));
        try {
            ExternalIngestResult result = canonicalImportService.executeBusinessRecordImport(callContext, req);
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) {
                counts.skipped++;
                return Outcome.SETTLED;
            }
            if (result.isSuccess()) {
                counts.imported++;
                return Outcome.SETTLED;
            }
            String why = "SF " + rec.id() + ": " + String.join(", ", result.errors());
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(req, why, json) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } catch (Exception e) {
            String why = "SF record " + rec.id() + ": " + e.getMessage();
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(req, why, json) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        }
    }
}
