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
package jp.aegif.nemaki.evidence.anchor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code anchor.schedule.*} keys: names, defaults, and the ONE validation that both the
 * settings endpoint (before it saves) and the scheduler (on every tick) apply
 * (design {@code docs/design/anchor-scheduler.md} §4.1).
 *
 * <p>Both sides run the same rules because the store is not the only source: a {@code -D} or an
 * environment variable reaches the scheduler without passing the endpoint's check, and "the
 * endpoint refuses it" would otherwise say nothing about what runs.
 *
 * <p>Deliberately NOT here: the destinations ({@code anchor.rfc3161.*},
 * {@code anchor.opentimestamps.sidecar.url}). They stay start-up system properties, and the
 * endpoint refuses them by name (§4.2).
 */
public final class AnchorScheduleSettings {

    public static final String PREFIX = "anchor.schedule.";
    public static final String ENABLED = PREFIX + "enabled";
    public static final String INTERVAL_MINUTES = PREFIX + "interval-minutes";
    public static final String MAX_UNANCHORED_ENTRIES = PREFIX + "max-unanchored-entries";
    public static final String MIN_INTERVAL_MINUTES = PREFIX + "min-interval-minutes";
    public static final String UPGRADE_INTERVAL_MINUTES = PREFIX + "upgrade-interval-minutes";
    public static final String RETRY_UNSETTLED_INTERVAL_MINUTES = PREFIX + "retry-unsettled-interval-minutes";

    /** Every key the settings endpoint accepts, in the order the screen shows them. */
    public static final List<String> KEYS = List.of(ENABLED, INTERVAL_MINUTES, MAX_UNANCHORED_ENTRIES,
            MIN_INTERVAL_MINUTES, UPGRADE_INTERVAL_MINUTES, RETRY_UNSETTLED_INTERVAL_MINUTES);

    static final int MIN_INTERVAL_FLOOR = 5;
    static final int DEFAULT_MIN_INTERVAL = 5;
    static final int DEFAULT_UPGRADE_INTERVAL = 60;
    static final int UPGRADE_INTERVAL_FLOOR = 5;
    /** Retrying buys a TSA token per failed rung; a failed TSA is not asked every minute. */
    static final int RETRY_INTERVAL_FLOOR = 60;

    private AnchorScheduleSettings() {
    }

    /**
     * The settings as the scheduler acts on them. Nullable fields are "not set" — never zero:
     * an interval nobody chose must not read as "every 0 minutes".
     */
    public record Effective(boolean enabled, Integer intervalMinutes, Integer maxUnanchoredEntries,
                            int minIntervalMinutes, int upgradeIntervalMinutes,
                            Integer retryUnsettledIntervalMinutes) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("intervalMinutes", intervalMinutes);
            m.put("maxUnanchoredEntries", maxUnanchoredEntries);
            m.put("minIntervalMinutes", minIntervalMinutes);
            m.put("upgradeIntervalMinutes", upgradeIntervalMinutes);
            m.put("retryUnsettledIntervalMinutes", retryUnsettledIntervalMinutes);
            return m;
        }
    }

    /**
     * @param errors key → why, in {@link #KEYS} order; empty when every value is acceptable.
     *        When non-empty, {@code effective} is what the defaults and the parsable values say,
     *        and the scheduler does NOT act on it.
     */
    public record Parsed(Effective effective, Map<String, String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * Parses and checks the raw values (blank or absent = not set).
     */
    public static Parsed parse(Map<String, String> raw) {
        Map<String, String> errors = new LinkedHashMap<>();
        Boolean enabled = parseBoolean(raw.get(ENABLED), ENABLED, errors);
        Integer interval = parseInt(raw.get(INTERVAL_MINUTES), INTERVAL_MINUTES, MIN_INTERVAL_FLOOR, errors);
        Integer max = parseInt(raw.get(MAX_UNANCHORED_ENTRIES), MAX_UNANCHORED_ENTRIES, 1, errors);
        Integer minInterval = parseInt(raw.get(MIN_INTERVAL_MINUTES), MIN_INTERVAL_MINUTES, 1, errors);
        Integer upgrade = parseInt(raw.get(UPGRADE_INTERVAL_MINUTES), UPGRADE_INTERVAL_MINUTES,
                UPGRADE_INTERVAL_FLOOR, errors);
        Integer retry = parseInt(raw.get(RETRY_UNSETTLED_INTERVAL_MINUTES), RETRY_UNSETTLED_INTERVAL_MINUTES,
                RETRY_INTERVAL_FLOOR, errors);

        boolean on = Boolean.TRUE.equals(enabled);
        if (on && interval == null && !errors.containsKey(INTERVAL_MINUTES)) {
            errors.put(INTERVAL_MINUTES, "required when " + ENABLED + " is true: an interval nobody "
                    + "chose is not \"every 0 minutes\"");
        }
        int effectiveMin = minInterval == null ? DEFAULT_MIN_INTERVAL : minInterval;
        if (interval != null && effectiveMin > interval && !errors.containsKey(MIN_INTERVAL_MINUTES)) {
            errors.put(MIN_INTERVAL_MINUTES, "must not exceed " + INTERVAL_MINUTES + " (" + interval
                    + "); it is " + effectiveMin);
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : KEYS) {
            if (errors.containsKey(key)) {
                ordered.put(key, errors.get(key));
            }
        }
        return new Parsed(new Effective(on, interval, max, effectiveMin,
                upgrade == null ? DEFAULT_UPGRADE_INTERVAL : upgrade, retry), ordered);
    }

    private static Boolean parseBoolean(String raw, String key, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) {
            return Boolean.TRUE;
        }
        if (v.equals("false")) {
            return Boolean.FALSE;
        }
        errors.put(key, "must be true or false, not '" + raw.trim() + "'");
        return null;
    }

    private static Integer parseInt(String raw, String key, int floor, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            errors.put(key, "must be a whole number of " + (key.endsWith("entries") ? "entries" : "minutes")
                    + ", not '" + raw.trim() + "'");
            return null;
        }
        if (value < floor) {
            errors.put(key, "must be at least " + floor + "; it is " + value);
            return null;
        }
        return value;
    }
}
