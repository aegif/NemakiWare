package jp.aegif.nemaki.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Thread-safe date formatting utility to replace unsafe SimpleDateFormat usages.
 * 
 * SimpleDateFormat is not thread-safe and can cause corruption when used concurrently.
 * This utility provides thread-safe formatting using ThreadLocal pattern.
 */
public class DateUtil {
    
    /**
     * Thread-local SimpleDateFormat for the standard system datetime format
     */
    private static final ThreadLocal<SimpleDateFormat> SYSTEM_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat(SystemConst.DATETIME_FORMAT);
        }
    };
    
    /**
     * Thread-local SimpleDateFormat for ISO datetime format with timezone
     */
    private static final ThreadLocal<SimpleDateFormat> ISO_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        }
    };
    
    /**
     * Thread-local SimpleDateFormat for standard log timestamp format
     */
    private static final ThreadLocal<SimpleDateFormat> LOG_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        }
    };
    
    /**
     * Thread-local SimpleDateFormat for bulk operations format
     */
    private static final ThreadLocal<SimpleDateFormat> BULK_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss.sssXXX");
        }
    };
    
    /**
     * Format a GregorianCalendar using the system datetime format (thread-safe)
     * 
     * @param cal The calendar to format
     * @return Formatted datetime string
     */
    public static String formatSystemDateTime(GregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return SYSTEM_FORMAT.get().format(cal.getTime());
    }
    
    /**
     * Format a Date using the system datetime format (thread-safe)
     * 
     * @param date The date to format
     * @return Formatted datetime string
     */
    public static String formatSystemDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return SYSTEM_FORMAT.get().format(date);
    }
    
    /**
     * Format a GregorianCalendar using ISO format with timezone (thread-safe)
     * 
     * @param cal The calendar to format
     * @return Formatted datetime string
     */
    public static String formatISODateTime(GregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return ISO_FORMAT.get().format(cal.getTime());
    }
    
    /**
     * Format a Date using log timestamp format (thread-safe)
     * 
     * @param date The date to format
     * @return Formatted datetime string
     */
    public static String formatLogTimestamp(Date date) {
        if (date == null) {
            return null;
        }
        return LOG_FORMAT.get().format(date);
    }
    
    /**
     * Format a Date using bulk operations format (thread-safe)
     * 
     * @param date The date to format
     * @return Formatted datetime string
     */
    public static String formatBulkDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return BULK_FORMAT.get().format(date);
    }
    
    /**
     * Create a thread-safe DateFormat instance for a specific pattern
     * 
     * @param pattern The date format pattern
     * @return A new SimpleDateFormat instance (not thread-safe - caller must manage)
     * @deprecated Use specific format methods or ThreadLocal pattern instead
     */
    @Deprecated
    public static DateFormat createDateFormat(String pattern) {
        return new SimpleDateFormat(pattern);
    }
    
    /**
     * Format a date with a custom pattern (thread-safe but creates new formatter each time)
     * For performance-critical code, consider using ThreadLocal pattern
     * 
     * @param date The date to format
     * @param pattern The format pattern
     * @return Formatted datetime string
     */
    public static String formatCustomPattern(Date date, String pattern) {
        if (date == null || pattern == null) {
            return null;
        }
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        return formatter.format(date);
    }
    
    /**
     * Clean up ThreadLocal instances to prevent memory leaks
     * Should be called when thread is finishing
     */
    public static void cleanupThreadLocal() {
        SYSTEM_FORMAT.remove();
        ISO_FORMAT.remove();
        LOG_FORMAT.remove();
        BULK_FORMAT.remove();
    }
}