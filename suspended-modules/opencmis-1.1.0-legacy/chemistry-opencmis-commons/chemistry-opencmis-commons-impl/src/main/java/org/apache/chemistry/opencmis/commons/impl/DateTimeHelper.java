/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.impl;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateTimeHelper {

    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static final Pattern XML_DATETIME = Pattern
            .compile("(\\d{4,9})-([01]\\d)-([0-3]\\d)T([0-2]\\d):([0-5]\\d):([0-5]\\d)(\\.(\\d+))?(([+-][0-2]\\d:[0-5]\\d)|Z)?");
    private static final BigDecimal BD1000 = new BigDecimal(1000);

    private static final String[] WDAYS = new String[] { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

    private static final String[] MONTHS = new String[] { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
            "Sep", "Oct", "Nov", "Dec" };

    private static final Map<String, Integer> MONTHS_MAP = new HashMap<String, Integer>();
    static {
        for (int i = 0; i < MONTHS.length; i++) {
            MONTHS_MAP.put(MONTHS[i], i);
        }
    }

    private static final Pattern HTTP_DATETIME1 = Pattern
            .compile("\\w{3}, ([0-3]\\d) (\\w{3}) (\\d{4}) ([0-2]\\d):([0-5]\\d):([0-5]\\d) GMT");

    private static final Pattern HTTP_DATETIME2 = Pattern
            .compile("\\w{6,9}, ([0-3]\\d)-(\\w{3})-(\\d{2}) ([0-2]\\d):([0-5]\\d):([0-5]\\d) GMT");

    private static final Pattern HTTP_DATETIME3 = Pattern
            .compile("\\w{3} (\\w{3}) ([0-3 ]\\d) ([0-2]\\d):([0-5]\\d):([0-5]\\d) (\\d{4})");

    private DateTimeHelper() {
    }

    /**
     * Parses a xsd:dateTime string.
     */
    public static GregorianCalendar parseXmlDateTime(String s) {
        if (s == null) {
            return null;
        }

        final Matcher m = XML_DATETIME.matcher(s);

        if (!m.matches()) {
            return null;
        }

        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = Integer.parseInt(m.group(6));
            int millisecond = 0;

            if (m.group(8) != null) {
                millisecond = (new BigDecimal("0." + m.group(8))).multiply(BD1000).intValue();
            }

            TimeZone tz = GMT;

            if (m.group(10) != null) {
                tz = TimeZone.getTimeZone("GMT" + m.group(10));
            }

            final GregorianCalendar result = new GregorianCalendar();
            result.clear();

            result.setTimeZone(tz);
            result.set(year, month - 1, day, hour, minute, second);
            result.set(Calendar.MILLISECOND, millisecond);

            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns a xsd:dateTime string.
     */
    public static String formatXmlDateTime(long millis) {
        final GregorianCalendar cal = new GregorianCalendar(GMT);
        cal.setTimeInMillis(millis);

        return formatXmlDateTime(cal);
    }

    /**
     * Returns a xsd:dateTime string.
     */
    public static String formatXmlDateTime(GregorianCalendar cal) {
        if (cal == null) {
            throw new IllegalArgumentException();
        }

        final StringBuilder sb = new StringBuilder(32);
        add4d(sb, cal.get(Calendar.YEAR));
        sb.append('-');
        add2d(sb, cal.get(Calendar.MONTH) + 1);
        sb.append('-');
        add2d(sb, cal.get(Calendar.DAY_OF_MONTH));
        sb.append('T');
        add2d(sb, cal.get(Calendar.HOUR_OF_DAY));
        sb.append(':');
        add2d(sb, cal.get(Calendar.MINUTE));
        sb.append(':');
        add2d(sb, cal.get(Calendar.SECOND));

        int ms = cal.get(Calendar.MILLISECOND);
        if (ms > 0) {
            sb.append('.');
            add3d(sb, ms);
            while (sb.charAt(sb.length() - 1) == '0') {
                sb.deleteCharAt(sb.length() - 1);
            }
        }

        int tz = cal.getTimeZone().getOffset(cal.getTimeInMillis());
        if (tz == 0) {
            sb.append('Z');
        } else {
            if (tz > 0) {
                sb.append('+');
            } else {
                sb.append('-');
                tz *= -1;
            }
            add2d(sb, tz / 3600000);
            sb.append(':');
            int tzm = tz % 3600000;
            add2d(sb, tzm == 0 ? 0 : tzm / 60000);
        }

        return sb.toString();
    }

    /**
     * Parses a HTTP date.
     */
    public static Date parseHttpDateTime(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();
        if (s.length() > 1 && s.charAt(0) == '\'' && s.endsWith("'")) {
            s = s.substring(1, s.length() - 1);
        }

        final GregorianCalendar cal = new GregorianCalendar(GMT);
        cal.set(Calendar.MILLISECOND, 0);

        Matcher m = null;

        m = HTTP_DATETIME1.matcher(s);
        if (m.matches()) {
            final Integer month = MONTHS_MAP.get(m.group(2));
            if (month == null) {
                return null;
            }

            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.YEAR, Integer.parseInt(m.group(3)));
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
            cal.set(Calendar.SECOND, Integer.parseInt(m.group(6)));

            return cal.getTime();
        }

        m = HTTP_DATETIME2.matcher(s);
        if (m.matches()) {
            final Integer month = MONTHS_MAP.get(m.group(2));
            if (month == null) {
                return null;
            }

            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
            cal.set(Calendar.MONTH, month);
            int year = Integer.parseInt(m.group(3));
            if (year < 100) {
                final int thisYear = (new GregorianCalendar(GMT)).get(Calendar.YEAR);
                final int testYear = year + thisYear - thisYear % 100;
                year = testYear < thisYear + 20 ? testYear : testYear - 100;
            }
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
            cal.set(Calendar.SECOND, Integer.parseInt(m.group(6)));

            return cal.getTime();
        }

        m = HTTP_DATETIME3.matcher(s);
        if (m.matches()) {
            final Integer month = MONTHS_MAP.get(m.group(1));
            if (month == null) {
                return null;
            }

            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(2).trim()));
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.YEAR, Integer.parseInt(m.group(6)));
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(3)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(4)));
            cal.set(Calendar.SECOND, Integer.parseInt(m.group(5)));

            return cal.getTime();
        }

        return null;
    }

    /**
     * Returns a HTTP date.
     */
    public static String formatHttpDateTime(long millis) {
        final GregorianCalendar cal = new GregorianCalendar(GMT);
        cal.setTimeInMillis(millis);

        final StringBuilder sb = new StringBuilder(64);
        sb.append(WDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]);
        sb.append(", ");
        add2d(sb, cal.get(Calendar.DAY_OF_MONTH));
        sb.append(' ');
        sb.append(MONTHS[cal.get(Calendar.MONTH)]);
        sb.append(' ');
        add4d(sb, cal.get(Calendar.YEAR));
        sb.append(' ');
        add2d(sb, cal.get(Calendar.HOUR_OF_DAY));
        sb.append(':');
        add2d(sb, cal.get(Calendar.MINUTE));
        sb.append(':');
        add2d(sb, cal.get(Calendar.SECOND));
        sb.append(" GMT");

        return sb.toString();
    }

    /**
     * Returns a HTTP date.
     */
    public static String formatHttpDateTime(final Date date) {
        return formatHttpDateTime(date.getTime());
    }

    /**
     * Returns a HTTP date.
     */
    public static String formatHttpDateTime(final GregorianCalendar cal) {
        return formatHttpDateTime(cal.getTimeInMillis());
    }

    private static void add2d(final StringBuilder sb, int value) {
        assert sb != null;
        assert value >= 0;

        if (value < 10) {
            sb.append('0');
        }
        sb.append(value);
    }

    private static void add3d(final StringBuilder sb, int value) {
        assert sb != null;
        assert value >= 0;

        if (value < 10) {
            sb.append('0');
        }
        if (value < 100) {
            sb.append('0');
        }
        sb.append(value);
    }

    private static void add4d(final StringBuilder sb, int value) {
        assert sb != null;
        assert value >= 0;

        if (value < 10) {
            sb.append('0');
        }
        if (value < 100) {
            sb.append('0');
        }
        if (value < 1000) {
            sb.append('0');
        }
        sb.append(value);
    }

}
