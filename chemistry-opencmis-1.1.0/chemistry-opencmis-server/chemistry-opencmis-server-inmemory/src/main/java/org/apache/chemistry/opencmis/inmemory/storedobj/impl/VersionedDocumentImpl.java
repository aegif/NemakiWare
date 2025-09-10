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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

public class VersionedDocumentImpl extends FilingImpl implements VersionedDocument {

    private boolean fIsCheckedOut;
    private String fCheckedOutUser;
    private final List<DocumentVersion> fVersions;

    public VersionedDocumentImpl() {
        super();
        fVersions = new ArrayList<DocumentVersion>();
        fIsCheckedOut = false;
    }

    @Override
    public DocumentVersion addVersion(VersioningState verState, String user) {

        if (isCheckedOut()) {
            throw new CmisConstraintException("Cannot add a version to document, document is checked out.");
        }

        DocumentVersionImpl ver = new DocumentVersionImpl(fRepositoryId, this, verState);
        ver.setSystemBasePropertiesWhenCreatedDirect(getName(), getTypeId(), user); // copy
        // name and type id from version series.
        fVersions.add(ver);
        if (verState == VersioningState.CHECKEDOUT) {
            fCheckedOutUser = user;
            fIsCheckedOut = true;
        }

        return ver;
    }

    @Override
    public boolean deleteVersion(DocumentVersion version) {
        if (fIsCheckedOut) {
            // Note: Do not throw an exception here if the document is
            // checked-out. In AtomPub binding cancelCheckout
            // mapped to a deleteVersion() call!
            DocumentVersion pwc = getPwc();
            if (pwc == version) { // NOSONAR
                cancelCheckOut(false); // note object is already deleted from
                                       // map in ObjectStore
                return !fVersions.isEmpty();
            }
        }
        boolean found = fVersions.remove(version);
        if (!found) {
            throw new CmisInvalidArgumentException("Version is not contained in the document:" + version.getId());
        }

        return !fVersions.isEmpty();
    }

    @Override
    public void cancelCheckOut(String user) {
        cancelCheckOut(true);
    }

    @Override
    public void checkIn(boolean isMajor, Properties properties, ContentStream content, String checkinComment,
            List<String> policyIds, String user) {
        if (fIsCheckedOut) {
            if (fCheckedOutUser.equals(user)) {
                fIsCheckedOut = false;
                fCheckedOutUser = null;
            } else {
                throw new CmisConstraintException("Error: Can't checkin. Document " + getId() + " user " + user
                        + " has not checked out the document");
            }
        } else {
            throw new CmisConstraintException("Error: Can't cancel checkout, Document " + getId()
                    + " is not checked out.");
        }

        DocumentVersion pwc = getPwc();

        if (null != content) {
            pwc.setContent(content);
        }

        if (null != properties && null != properties.getProperties()) {
            ((DocumentVersionImpl) pwc).setCustomProperties(properties.getProperties());
        }

        pwc.setCheckinComment(checkinComment);
        pwc.commit(isMajor);
        if (policyIds != null && policyIds.size() > 0) {
            ((DocumentVersionImpl) pwc).setAppliedPolicies(policyIds);
        }
    }

    @Override
    public DocumentVersion checkOut(String user) {
        if (fIsCheckedOut) {
            throw new CmisConstraintException("Error: Can't checkout, Document " + getId() 
                    + " is already checked out.");
        }

        // create PWC
        DocumentVersion pwc = addVersion(VersioningState.CHECKEDOUT, user); // will
        // set check-out flag
        return pwc;
    }

    @Override
    public List<DocumentVersion> getAllVersions() {
        return fVersions;
    }

    @Override
    public DocumentVersion getLatestVersion(boolean major) {

        DocumentVersion latest = null;
        if (fVersions.size() == 0) {
            return null;
        }

        if (major) {
            for (DocumentVersion ver : fVersions) {
                if (ver.isMajor() && !ver.isPwc()) {
                    latest = ver;
                }
            }
        } else {
            if (null == getPwc()) {
                latest = fVersions.get(fVersions.size() - 1);
            } else if (fVersions.size() > 1) {
                latest = fVersions.get(fVersions.size() - 2);
            } else {
                latest = null;
            }
            if (null == getPwc()) {
                latest = fVersions.get(fVersions.size() - 1);
            } else if (fVersions.size() > 1) {
                latest = fVersions.get(fVersions.size() - 2);
            } else {
                latest = null;
            }
        }
        return latest;
    }

    @Override
    public boolean isCheckedOut() {
        return fIsCheckedOut;
    }

    @Override
    public String getCheckedOutBy() {
        return fCheckedOutUser;
    }

    @Override
    public DocumentVersion getPwc() {
        for (DocumentVersion ver : fVersions) {
            if (ver.isPwc()) {
                return ver;
            }
        }
        return null;
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        DocumentVersion pwc = getPwc();

        super.fillProperties(properties, objFactory, requestedIds);

        if (FilterParser.isContainedInFilter(PropertyIds.IS_IMMUTABLE, requestedIds)) {
            properties.put(PropertyIds.IS_IMMUTABLE,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_IMMUTABLE, false));
        }

        // overwrite the version related properties
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_ID, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_ID, getId()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, requestedIds)) {
            properties.put(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                    objFactory.createPropertyBooleanData(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, isCheckedOut()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, requestedIds)) {
            properties.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                    objFactory.createPropertyStringData(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, getCheckedOutBy()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, requestedIds)) {
            properties.put(
                    PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                    objFactory.createPropertyIdData(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                            pwc == null ? null : pwc.getId()));
        }

    }

    private void cancelCheckOut(boolean deleteInObjectStore) {
        DocumentVersion pwc = getPwc();
        fIsCheckedOut = false;
        fCheckedOutUser = null;
        fVersions.remove(pwc);
        if (fVersions.size() > 0) {
            String nameLatestVer = getLatestVersion(false).getName();
            if (!getName().equals(nameLatestVer)) {
                setName(nameLatestVer);
            }
        }
        if (deleteInObjectStore) {
            // TODO:
        }

    }

}
