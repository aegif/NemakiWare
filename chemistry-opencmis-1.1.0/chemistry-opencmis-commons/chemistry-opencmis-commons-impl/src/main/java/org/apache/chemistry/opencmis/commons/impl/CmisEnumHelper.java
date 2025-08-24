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

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

/**
 * Helper methods to turn a value into a CMIS enum.
 */
public final class CmisEnumHelper {

    private CmisEnumHelper() {
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<?>> E fromValue(final String value, final Class<E> clazz) {
        if (value == null || value.length() == 0) {
            return null;
        }

        try {
            if (Action.class == clazz) {
                return (E) Action.fromValue(value);
            } else if (BaseTypeId.class == clazz) {
                return (E) BaseTypeId.fromValue(value);
            } else if (CmisVersion.class == clazz) {
                return (E) CmisVersion.fromValue(value);
            } else if (IncludeRelationships.class == clazz) {
                return (E) IncludeRelationships.fromValue(value);
            } else if (ReturnVersion.class == clazz) {
                return (E) ReturnVersion.fromValue(value);
            } else if (VersioningState.class == clazz) {
                return (E) VersioningState.fromValue(value);
            } else if (RelationshipDirection.class == clazz) {
                return (E) RelationshipDirection.fromValue(value);
            } else if (AclPropagation.class == clazz) {
                return (E) AclPropagation.fromValue(value);
            } else if (UnfileObject.class == clazz) {
                return (E) UnfileObject.fromValue(value);
            } else if (PropertyType.class == clazz) {
                return (E) PropertyType.fromValue(value);
            } else if (Cardinality.class == clazz) {
                return (E) Cardinality.fromValue(value);
            } else if (Updatability.class == clazz) {
                return (E) Updatability.fromValue(value);
            } else if (ContentStreamAllowed.class == clazz) {
                return (E) ContentStreamAllowed.fromValue(value);
            } else if (DateTimeResolution.class == clazz) {
                return (E) DateTimeResolution.fromValue(value);
            } else if (ChangeType.class == clazz) {
                return (E) ChangeType.fromValue(value);
            } else if (SupportedPermissions.class == clazz) {
                return (E) SupportedPermissions.fromValue(value);
            } else if (CapabilityAcl.class == clazz) {
                return (E) CapabilityAcl.fromValue(value);
            } else if (CapabilityChanges.class == clazz) {
                return (E) CapabilityChanges.fromValue(value);
            } else if (CapabilityContentStreamUpdates.class == clazz) {
                return (E) CapabilityContentStreamUpdates.fromValue(value);
            } else if (CapabilityJoin.class == clazz) {
                return (E) CapabilityJoin.fromValue(value);
            } else if (CapabilityOrderBy.class == clazz) {
                return (E) CapabilityOrderBy.fromValue(value);
            } else if (CapabilityQuery.class == clazz) {
                return (E) CapabilityQuery.fromValue(value);
            } else if (CapabilityRenditions.class == clazz) {
                return (E) CapabilityRenditions.fromValue(value);
            }
        } catch (IllegalArgumentException e) {
            throw new CmisInvalidArgumentException("Invalid enum value '" + value + "'!", e);
        }

        throw new CmisRuntimeException(clazz.getSimpleName() + " is not a CMIS enum!");
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<?>> E fromValue(final BigInteger value, final Class<E> clazz) {
        if (value == null) {
            return null;
        }

        try {
            if (DecimalPrecision.class == clazz) {
                return (E) DecimalPrecision.fromValue(value);
            }
        } catch (IllegalArgumentException e) {
            throw new CmisInvalidArgumentException("Invalid enum value '" + value + "'!", e);
        }

        throw new CmisRuntimeException(clazz.getSimpleName() + " is not a CMIS enum!");
    }
}
