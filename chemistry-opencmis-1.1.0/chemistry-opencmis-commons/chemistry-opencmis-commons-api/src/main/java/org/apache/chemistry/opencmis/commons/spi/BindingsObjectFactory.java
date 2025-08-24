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
package org.apache.chemistry.opencmis.commons.spi;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyDateTime;
import org.apache.chemistry.opencmis.commons.data.PropertyDecimal;
import org.apache.chemistry.opencmis.commons.data.PropertyHtml;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyInteger;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.PropertyUri;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;

/**
 * Factory for CMIS binding objects.
 */
public interface BindingsObjectFactory {

    Ace createAccessControlEntry(String principal, List<String> permissions);

    Acl createAccessControlList(List<Ace> aces);

    <T> PropertyData<T> createPropertyData(PropertyDefinition<T> pd, Object value);

    PropertyBoolean createPropertyBooleanData(String id, List<Boolean> values);

    PropertyBoolean createPropertyBooleanData(String id, Boolean value);

    PropertyId createPropertyIdData(String id, List<String> values);

    PropertyId createPropertyIdData(String id, String value);

    PropertyInteger createPropertyIntegerData(String id, List<BigInteger> values);

    PropertyInteger createPropertyIntegerData(String id, BigInteger value);

    PropertyDateTime createPropertyDateTimeData(String id, List<GregorianCalendar> values);

    PropertyDateTime createPropertyDateTimeData(String id, GregorianCalendar value);

    PropertyDecimal createPropertyDecimalData(String id, List<BigDecimal> values);

    PropertyDecimal createPropertyDecimalData(String id, BigDecimal value);

    PropertyHtml createPropertyHtmlData(String id, List<String> values);

    PropertyHtml createPropertyHtmlData(String id, String value);

    PropertyString createPropertyStringData(String id, List<String> values);

    PropertyString createPropertyStringData(String id, String value);

    PropertyUri createPropertyUriData(String id, List<String> values);

    PropertyUri createPropertyUriData(String id, String value);

    Properties createPropertiesData(List<PropertyData<?>> properties);

    ContentStream createContentStream(String filename, BigInteger length, String mimetype, InputStream stream);
}
