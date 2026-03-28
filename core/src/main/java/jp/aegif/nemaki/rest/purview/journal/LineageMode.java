package jp.aegif.nemaki.rest.purview.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum LineageMode {

    DISABLED,
    DIRECT,
    JOURNALED;

    private static final Logger logger = LoggerFactory.getLogger(LineageMode.class);

    public static LineageMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid lineage.mode value '{}', falling back to DISABLED", value);
            return DISABLED;
        }
    }
}
