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
 *
 * Authors:
 *     Florent Guillaume, Nuxeo
 */
package org.apache.chemistry.opencmis.server.support.query;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper dealing with conversion of {@link Calendar} to and from the string
 * format specified by CMISQL.
 */
public final class CalendarHelper {

    private static final Pattern CMISQL_PATTERN = Pattern.compile( //
            "(\\d{4})-(\\d{2})-(\\d{2})[Tt]" + "(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?"
                    + "(?:[Zz]|([+-]\\d{2}:\\d{2}))?");

    private CalendarHelper() {
        // utility class
    }

    /**
     * Converts a CMISQL date string representation to a
     * {@link GregorianCalendar}.
     * <p>
     * Parses {@code YYYY-HH-MMThh:mm:ss.sss+hh:mm}, or a {@code Z} for the
     * timezone, and with {@code .sss} being optional.
     *
     * @param datetime
     *            the string representation in CMISQL format
     * @return the created instance
     */
    public static GregorianCalendar fromString(String datetime) {
        Matcher m = CMISQL_PATTERN.matcher(datetime);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid datetime format: " + datetime);
        }
        String tz = m.group(8);
        GregorianCalendar cal = (GregorianCalendar) Calendar.getInstance(TimeZone.getTimeZone("GMT"
                + (tz == null ? "" : tz)));
        cal.set(Calendar.YEAR, Integer.parseInt(m.group(1)));
        cal.set(Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
        cal.set(Calendar.DATE, Integer.parseInt(m.group(3)));
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
        cal.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
        cal.set(Calendar.SECOND, Integer.parseInt(m.group(6)));
        String decimals = m.group(7);
        int ms = decimals == null ? 0 : Integer.parseInt((decimals + "00").substring(0, 3));
        cal.set(Calendar.MILLISECOND, ms);
        return cal;
    }

    /**
     * Converts a Calendar to its CMISQL string representation.
     *
     * @param cal
     *            a {@link Calendar}
     * @return the CMISQL string representation
     */
    public static String toString(Calendar cal) {
        StringBuilder buf = new StringBuilder(28);
        toString(cal, buf);
        return buf.toString();
    }

    /**
     * Converts a Calendar to its CMISQL string representation.
     *
     * @param cal
     *            a {@link Calendar}
     * @param buf
     *            a buffer in which to add the CMISQL string representation
     */
    public static void toString(Calendar cal, StringBuilder buf) {
        buf.append(cal.get(Calendar.YEAR));
        buf.append('-');
        int f = cal.get(Calendar.MONTH);
        if (f < 9) {
            buf.append('0');
        }
        buf.append(f + 1);
        buf.append('-');
        f = cal.get(Calendar.DATE);
        if (f < 10) {
            buf.append('0');
        }
        buf.append(f);
        buf.append('T');
        f = cal.get(Calendar.HOUR_OF_DAY);
        if (f < 10) {
            buf.append('0');
        }
        buf.append(f);
        buf.append(':');
        f = cal.get(Calendar.MINUTE);
        if (f < 10) {
            buf.append('0');
        }
        buf.append(f);
        buf.append(':');
        f = cal.get(Calendar.SECOND);
        if (f < 10) {
            buf.append('0');
        }
        buf.append(f);
        buf.append('.');
        f = cal.get(Calendar.MILLISECOND);
        if (f < 100) {
            buf.append('0');
        }
        if (f < 10) {
            buf.append('0');
        }
        buf.append(f);
        int offset = cal.getTimeZone().getOffset(cal.getTimeInMillis()) / 60000;
        if (offset == 0) {
            buf.append('Z');
        } else {
            char sign;
            if (offset < 0) {
                offset = -offset;
                sign = '-';
            } else {
                sign = '+';
            }
            buf.append(sign);
            f = offset / 60;
            if (f < 10) {
                buf.append('0');
            }
            buf.append(f);
            buf.append(':');
            f = offset % 60;
            if (f < 10) {
                buf.append('0');
            }
            buf.append(f);
        }
    }

}
