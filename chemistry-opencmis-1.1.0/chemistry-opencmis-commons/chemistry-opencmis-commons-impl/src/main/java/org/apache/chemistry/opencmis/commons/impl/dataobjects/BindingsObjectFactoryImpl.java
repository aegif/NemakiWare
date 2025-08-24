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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.MutableAce;
import org.apache.chemistry.opencmis.commons.data.MutableAcl;
import org.apache.chemistry.opencmis.commons.data.MutableContentStream;
import org.apache.chemistry.opencmis.commons.data.MutableProperties;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyData;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyDateTime;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyDecimal;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyHtml;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyId;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyInteger;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyString;
import org.apache.chemistry.opencmis.commons.data.MutablePropertyUri;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;

/**
 * CMIS binding object factory implementation.
 */
public class BindingsObjectFactoryImpl implements BindingsObjectFactory, Serializable {

    private static final long serialVersionUID = 1L;

    public BindingsObjectFactoryImpl() {
    }

    @Override
    public MutableAce createAccessControlEntry(String principal, List<String> permissions) {
        return new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(principal), permissions);
    }

    @Override
    public MutableAcl createAccessControlList(List<Ace> aces) {
        return new AccessControlListImpl(aces);
    }

    @Override
    public MutableContentStream createContentStream(String filename, BigInteger length, String mimetype,
            InputStream stream) {
        return new ContentStreamImpl(filename, length, mimetype, stream);
    }

    @Override
    public MutableProperties createPropertiesData(List<PropertyData<?>> properties) {
        return new PropertiesImpl(properties);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> MutablePropertyData<T> createPropertyData(PropertyDefinition<T> pd, Object value) {
        String id = pd.getId();
        boolean single = pd.getCardinality() == Cardinality.SINGLE;
        if (pd instanceof PropertyBooleanDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyBooleanData(id, (Boolean) value);
            } else {
                return (MutablePropertyData<T>) createPropertyBooleanData(id, (List<Boolean>) value);
            }
        } else if (pd instanceof PropertyDateTimeDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyDateTimeData(id, (GregorianCalendar) value);
            } else {
                return (MutablePropertyData<T>) createPropertyDateTimeData(id, (List<GregorianCalendar>) value);
            }
        } else if (pd instanceof PropertyDecimalDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyDecimalData(id, (BigDecimal) value);
            } else {
                return (MutablePropertyData<T>) createPropertyDecimalData(id, (List<BigDecimal>) value);
            }
        } else if (pd instanceof PropertyHtmlDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyHtmlData(id, (String) value);
            } else {
                return (MutablePropertyData<T>) createPropertyHtmlData(id, (List<String>) value);
            }
        } else if (pd instanceof PropertyIdDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyIdData(id, (String) value);
            } else {
                return (MutablePropertyData<T>) createPropertyIdData(id, (List<String>) value);
            }
        } else if (pd instanceof PropertyIntegerDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyIntegerData(id, (BigInteger) value);
            } else {
                return (MutablePropertyData<T>) createPropertyIntegerData(id, (List<BigInteger>) value);
            }
        } else if (pd instanceof PropertyStringDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyStringData(id, (String) value);
            } else {
                return (MutablePropertyData<T>) createPropertyStringData(id, (List<String>) value);
            }
        } else if (pd instanceof PropertyUriDefinition) {
            if (single) {
                return (MutablePropertyData<T>) createPropertyUriData(id, (String) value);
            } else {
                return (MutablePropertyData<T>) createPropertyUriData(id, (List<String>) value);
            }
        }
        throw new CmisRuntimeException("Unknown property definition: " + pd);
    }

    @Override
    public MutablePropertyBoolean createPropertyBooleanData(String id, List<Boolean> values) {
        return new PropertyBooleanImpl(id, values);
    }

    @Override
    public MutablePropertyBoolean createPropertyBooleanData(String id, Boolean value) {
        return new PropertyBooleanImpl(id, value);
    }

    @Override
    public MutablePropertyDateTime createPropertyDateTimeData(String id, List<GregorianCalendar> values) {
        return new PropertyDateTimeImpl(id, values);
    }

    @Override
    public MutablePropertyDateTime createPropertyDateTimeData(String id, GregorianCalendar value) {
        return new PropertyDateTimeImpl(id, value);
    }

    @Override
    public MutablePropertyDecimal createPropertyDecimalData(String id, List<BigDecimal> values) {
        return new PropertyDecimalImpl(id, values);
    }

    @Override
    public MutablePropertyDecimal createPropertyDecimalData(String id, BigDecimal value) {
        return new PropertyDecimalImpl(id, value);
    }

    @Override
    public MutablePropertyHtml createPropertyHtmlData(String id, List<String> values) {
        return new PropertyHtmlImpl(id, values);
    }

    @Override
    public MutablePropertyHtml createPropertyHtmlData(String id, String value) {
        return new PropertyHtmlImpl(id, value);
    }

    @Override
    public MutablePropertyId createPropertyIdData(String id, List<String> values) {
        return new PropertyIdImpl(id, values);
    }

    @Override
    public MutablePropertyId createPropertyIdData(String id, String value) {
        return new PropertyIdImpl(id, value);
    }

    @Override
    public MutablePropertyInteger createPropertyIntegerData(String id, List<BigInteger> values) {
        return new PropertyIntegerImpl(id, values);
    }

    @Override
    public MutablePropertyInteger createPropertyIntegerData(String id, BigInteger value) {
        return new PropertyIntegerImpl(id, value);
    }

    @Override
    public MutablePropertyString createPropertyStringData(String id, List<String> values) {
        return new PropertyStringImpl(id, values);
    }

    @Override
    public MutablePropertyString createPropertyStringData(String id, String value) {
        return new PropertyStringImpl(id, value);
    }

    @Override
    public MutablePropertyUri createPropertyUriData(String id, List<String> values) {
        return new PropertyUriImpl(id, values);
    }

    @Override
    public MutablePropertyUri createPropertyUriData(String id, String value) {
        return new PropertyUriImpl(id, value);
    }
}
