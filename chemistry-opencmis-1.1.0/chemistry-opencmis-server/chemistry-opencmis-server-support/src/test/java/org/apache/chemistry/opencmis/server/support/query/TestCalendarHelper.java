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

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.junit.Test;

public class TestCalendarHelper {

    @Test
    public void testToString1() {
        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT+07:30"));
        cal.set(Calendar.YEAR, 2009);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DATE, 2);
        cal.set(Calendar.HOUR_OF_DAY, 3);
        cal.set(Calendar.MINUTE, 4);
        cal.set(Calendar.SECOND, 5);
        cal.set(Calendar.MILLISECOND, 6);
        assertEquals("2009-01-02T03:04:05.006+07:30", CalendarHelper.toString(cal));
    }

    @Test
    public void testToString2() {
        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT-06:00"));
        cal.set(Calendar.YEAR, 2008);
        cal.set(Calendar.MONTH, 11);
        cal.set(Calendar.DATE, 31);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        assertEquals("2008-12-31T23:59:59.999-06:00", CalendarHelper.toString(cal));
    }

    @Test
    public void testToString3() {
        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.set(Calendar.YEAR, 2008);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DATE, 1);
        cal.set(Calendar.HOUR_OF_DAY, 1);
        cal.set(Calendar.MINUTE, 1);
        cal.set(Calendar.SECOND, 1);
        cal.set(Calendar.MILLISECOND, 1);
        assertEquals("2008-01-01T01:01:01.001Z", CalendarHelper.toString(cal));
    }

    @Test
    public void testFromString() {
        Calendar cal;
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.123-06:30");
        assertEquals("2009-07-14T12:00:00.123-06:30", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00Z");
        assertEquals("2009-07-14T12:00:00.000Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.5Z");
        assertEquals("2009-07-14T12:00:00.500Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.44Z");
        assertEquals("2009-07-14T12:00:00.440Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.333Z");
        assertEquals("2009-07-14T12:00:00.333Z", CalendarHelper.toString(cal));
        // no timezone
        cal = CalendarHelper.fromString("2009-07-14T12:00:00");
        assertEquals("2009-07-14T12:00:00.000Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.5");
        assertEquals("2009-07-14T12:00:00.500Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.44");
        assertEquals("2009-07-14T12:00:00.440Z", CalendarHelper.toString(cal));
        cal = CalendarHelper.fromString("2009-07-14T12:00:00.333");
        assertEquals("2009-07-14T12:00:00.333Z", CalendarHelper.toString(cal));
    }

}
