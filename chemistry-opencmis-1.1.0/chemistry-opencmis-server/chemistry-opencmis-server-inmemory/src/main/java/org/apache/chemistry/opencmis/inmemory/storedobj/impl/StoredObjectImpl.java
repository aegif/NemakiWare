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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.DataObjectCreator;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;

/**
 * StoredObject is the common superclass of all objects hold in the repository
 * Documents, Folders, Relationships and Policies.
 * 
 */
public class StoredObjectImpl implements StoredObject {

    private static final int BUFFER_SIZE = 65536;
    private static final String UNKNOWN_USER = "unknown";

    protected String fId;
    protected String fName;
    protected String fTypeId;
    protected String fCreatedBy;
    protected String fModifiedBy;
    protected GregorianCalendar fCreatedAt;
    protected GregorianCalendar fModifiedAt;
    protected String fRepositoryId;
    protected Map<String, PropertyData<?>> fProperties;
    protected int fAclId;
    protected String description; // CMIS 1.1
    protected List<String> secondaryTypeIds; // CMIS 1.1
    protected List<String> policyIds;

    StoredObjectImpl() { // visibility should be package
        GregorianCalendar now = getNow();
        now.setTime(new Date());
        fCreatedAt = now;
        fModifiedAt = now;
        secondaryTypeIds = new ArrayList<String>();
        policyIds = null;
    }

    @Override
    public String getId() {
        return fId;
    }

    @Override
    public void setId(String id) {
        fId = id;
    }

    @Override
    public String getName() {
        return fName;
    }

    @Override
    public void setName(String name) {
        fName = name;
    }

    @Override
    public String getTypeId() {
        return fTypeId;
    }

    @Override
    public void setTypeId(String type) {
        fTypeId = type;
    }

    @Override
    public String getCreatedBy() {
        return fCreatedBy;
    }

    @Override
    public void setCreatedBy(String createdBy) {
        this.fCreatedBy = createdBy;
    }

    @Override
    public String getModifiedBy() {
        return fModifiedBy;
    }

    @Override
    public void setModifiedBy(String modifiedBy) {
        this.fModifiedBy = modifiedBy;
    }

    @Override
    public GregorianCalendar getCreatedAt() {
        return fCreatedAt;
    }

    @Override
    public void setCreatedAt(GregorianCalendar createdAt) {
        this.fCreatedAt = createdAt;
    }

    @Override
    public GregorianCalendar getModifiedAt() {
        return fModifiedAt;
    }

    @Override
    public void setModifiedAtNow() {
    	GregorianCalendar now = getNow();
    	// ensure a larger time for modification date and change token:
    	while (now.getTimeInMillis() == fModifiedAt.getTimeInMillis()) {
    		try {
    			Thread.sleep(1);
    		} catch (InterruptedException ex) {    			
    		}
    		now = getNow();
    	}
        this.fModifiedAt = now;
    }

    @Override
    public void setModifiedAt(GregorianCalendar cal) {
        this.fModifiedAt = cal;
    }

    @Override
    public void setRepositoryId(String repositoryId) {
        fRepositoryId = repositoryId;
    }

    @Override
    public String getRepositoryId() {
        return fRepositoryId;
    }

    @Override
    public List<String> getAppliedPolicies() {
        if (null == policyIds) {
            return null;
        } else {
            return Collections.unmodifiableList(policyIds);
        }
    }

    public void setAppliedPolicies(List<String> newPolicies) {
        if (null == newPolicies) {
            policyIds = null;
        } else {
            if (null == policyIds) {
                policyIds = new ArrayList<String>();
            }
            policyIds.addAll(newPolicies);
        }
    }

    @Override
    public void addAppliedPolicy(String policyId) {
        if (null == policyIds) {
            policyIds = new ArrayList<String>();
        }
        if (!policyIds.contains(policyId)) {
            policyIds.add(policyId);
        }
    }

    @Override
    public void removePolicy(String policyId) {
        if (null != policyIds && policyIds.contains(policyId)) {
            policyIds.remove(policyId);
            if (policyIds.isEmpty()) {
                policyIds = null;
            }
        }
    }

    // CMIS 1.1:
    @Override
    public void setDescription(String descr) {
        description = descr;
    }

    // CMIS 1.1:
    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public List<String> getSecondaryTypeIds() {
        return Collections.unmodifiableList(secondaryTypeIds);
    }

    @Override
    public void setProperties(Map<String, PropertyData<?>> props) {
        fProperties = props;
    }

    @Override
    public Map<String, PropertyData<?>> getProperties() {
        return fProperties;
    }

    @Override
    public String getChangeToken() {
        GregorianCalendar lastModified = getModifiedAt();
        String token = Long.valueOf(lastModified.getTimeInMillis()).toString();
        return token;
    }

    @Override
    public void createSystemBasePropertiesWhenCreated(Map<String, PropertyData<?>> properties, String user) {
        addSystemBaseProperties(properties, user, true);
    }

    @Override
    public void updateSystemBasePropertiesWhenModified(Map<String, PropertyData<?>> properties, String user) {
        addSystemBaseProperties(properties, user, false);
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        if (FilterParser.isContainedInFilter(PropertyIds.NAME, requestedIds)) {
            properties.put(PropertyIds.NAME, objFactory.createPropertyStringData(PropertyIds.NAME, getName()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.OBJECT_ID, requestedIds)) {
            properties.put(PropertyIds.OBJECT_ID, objFactory.createPropertyIdData(PropertyIds.OBJECT_ID, getId()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.OBJECT_TYPE_ID, requestedIds)) {
            properties.put(PropertyIds.OBJECT_TYPE_ID,
                    objFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, getTypeId()));
        }
        // set the base type id PropertyIds.CMIS_BASE_TYPE_ID outside because it
        // requires the type definition
        if (FilterParser.isContainedInFilter(PropertyIds.CREATED_BY, requestedIds)) {
            properties.put(PropertyIds.CREATED_BY,
                    objFactory.createPropertyStringData(PropertyIds.CREATED_BY, getCreatedBy()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CREATION_DATE, requestedIds)) {
            properties.put(PropertyIds.CREATION_DATE,
                    objFactory.createPropertyDateTimeData(PropertyIds.CREATION_DATE, getCreatedAt()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.LAST_MODIFIED_BY, requestedIds)) {
            properties.put(PropertyIds.LAST_MODIFIED_BY,
                    objFactory.createPropertyStringData(PropertyIds.LAST_MODIFIED_BY, getModifiedBy()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.LAST_MODIFICATION_DATE, requestedIds)) {
            properties.put(PropertyIds.LAST_MODIFICATION_DATE,
                    objFactory.createPropertyDateTimeData(PropertyIds.LAST_MODIFICATION_DATE, getModifiedAt()));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.CHANGE_TOKEN, requestedIds)) {
            String token = getChangeToken();
            properties.put(PropertyIds.CHANGE_TOKEN,
                    objFactory.createPropertyStringData(PropertyIds.CHANGE_TOKEN, token));
        }

        // CMIS 1.1 properties:
        if (FilterParser.isContainedInFilter(PropertyIds.DESCRIPTION, requestedIds)) {
            properties.put(PropertyIds.DESCRIPTION,
                    objFactory.createPropertyStringData(PropertyIds.DESCRIPTION, description));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, requestedIds)) {
            properties.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                    objFactory.createPropertyIdData(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryTypeIds));
        }

        // add custom properties of type definition to the collection
        if (null != fProperties) {
            for (Entry<String, PropertyData<?>> prop : fProperties.entrySet()) {
                if (FilterParser.isContainedInFilter(prop.getKey(), requestedIds)) {
                    properties.put(prop.getKey(), prop.getValue());
                }
            }
        }

    }

    // ///////////////////////////////////////////
    // private helper methods

    @Override
    public void setCustomProperties(Map<String, PropertyData<?>> properties) {
        Map<String, PropertyData<?>> propertiesNew = new HashMap<String, PropertyData<?>>(properties);
        // get a writablecollection
        removeAllSystemProperties(propertiesNew);
        setProperties(propertiesNew);
    }

    private static GregorianCalendar getNow() {
        GregorianCalendar now = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        return now;
    }

    /*
     * Add CMIS_CREATED_BY, CMIS_CREATION_DATE, CMIS_LAST_MODIFIED_BY,
     * CMIS_LAST_MODIFICATION_DATE, CMIS_CHANGE_TOKEN system properties to the
     * list of properties with current values.
     */
    @SuppressWarnings("unchecked")
    private void addSystemBaseProperties(Map<String, PropertyData<?>> properties, String user, boolean isCreated) {
        if (user == null) {
            user = UNKNOWN_USER;
        }

        // Note that initial creation and modification date is set in
        // constructor.
        setModifiedBy(user);
        if (null != properties) {
            if (null != properties.get(PropertyIds.DESCRIPTION)) {
                setDescription((String) properties.get(PropertyIds.DESCRIPTION).getFirstValue());
            }

            if (properties.containsKey(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
                secondaryTypeIds.clear();
            }
            PropertyData<?> secondaryTypeProp = properties.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
            if (null != secondaryTypeProp) {
                secondaryTypeIds.addAll((List<String>) secondaryTypeProp.getValues());
            }
        }
        if (isCreated) {
            setCreatedBy(user);
            setName((String) properties.get(PropertyIds.NAME).getFirstValue());
            setTypeId((String) properties.get(PropertyIds.OBJECT_TYPE_ID).getFirstValue());
        } else {
            setModifiedAtNow();
        }
    }

    /*
     * Add CMIS_CREATED_BY, CMIS_CREATION_DATE, CMIS_LAST_MODIFIED_BY,
     * CMIS_LAST_MODIFICATION_DATE, CMIS_CHANGE_TOKEN system properties to the
     * list of properties with current values
     */
    protected void setSystemBasePropertiesWhenCreatedDirect(String name, String typeId, String user) {
        // Note that initial creation and modification date is set in
        // constructor.
        setModifiedBy(user);
        setCreatedBy(user);
        setName(name);
        setTypeId(typeId);
    }

    /*
     * CMIS_NAME CMIS_OBJECT_ID CMIS_OBJECT_TYPE_ID CMIS_BASE_TYPE_ID
     * CMIS_CREATED_BY CMIS_CREATION_DATE CMIS_LAST_MODIFIED_BY
     * CMIS_LAST_MODIFICATION_DATE CMIS_CHANGE_TOKEN
     * 
     * // ---- document ---- CMIS_IS_IMMUTABLE CMIS_IS_LATEST_VERSION
     * CMIS_IS_MAJOR_VERSION CMIS_IS_LATEST_MAJOR_VERSION CMIS_VERSION_LABEL
     * CMIS_VERSION_SERIES_ID CMIS_IS_VERSION_SERIES_CHECKED_OUT
     * CMIS_VERSION_SERIES_CHECKED_OUT_BY CMIS_VERSION_SERIES_CHECKED_OUT_ID
     * CMIS_CHECKIN_COMMENT CMIS_CONTENT_STREAM_LENGTH
     * CMIS_CONTENT_STREAM_MIME_TYPE CMIS_CONTENT_STREAM_FILE_NAME
     * CMIS_CONTENT_STREAM_ID
     * 
     * // ---- folder ---- CMIS_PARENT_ID CMIS_ALLOWED_CHILD_OBJECT_TYPE_IDS
     * CMIS_PATH
     * 
     * // ---- relationship ---- CMIS_SOURCE_ID CMIS_TARGET_ID
     * 
     * // ---- policy ---- CMIS_POLICY_TEXT
     */
    private static void removeAllSystemProperties(Map<String, PropertyData<?>> properties) {
        // ---- base ----
        if (properties.containsKey(PropertyIds.NAME)) {
            properties.remove(PropertyIds.NAME);
        }
        if (properties.containsKey(PropertyIds.OBJECT_ID)) {
            properties.remove(PropertyIds.OBJECT_ID);
        }
        if (properties.containsKey(PropertyIds.OBJECT_TYPE_ID)) {
            properties.remove(PropertyIds.OBJECT_TYPE_ID);
        }
        if (properties.containsKey(PropertyIds.BASE_TYPE_ID)) {
            properties.remove(PropertyIds.BASE_TYPE_ID);
        }
        if (properties.containsKey(PropertyIds.CREATED_BY)) {
            properties.remove(PropertyIds.CREATED_BY);
        }
        if (properties.containsKey(PropertyIds.CREATION_DATE)) {
            properties.remove(PropertyIds.CREATION_DATE);
        }
        if (properties.containsKey(PropertyIds.LAST_MODIFIED_BY)) {
            properties.remove(PropertyIds.LAST_MODIFIED_BY);
        }
        if (properties.containsKey(PropertyIds.LAST_MODIFICATION_DATE)) {
            properties.remove(PropertyIds.LAST_MODIFICATION_DATE);
        }
        if (properties.containsKey(PropertyIds.CHANGE_TOKEN)) {
            properties.remove(PropertyIds.CHANGE_TOKEN);
        }
        // ---- document ----
        if (properties.containsKey(PropertyIds.IS_IMMUTABLE)) {
            properties.remove(PropertyIds.IS_IMMUTABLE);
        }
        if (properties.containsKey(PropertyIds.IS_LATEST_VERSION)) {
            properties.remove(PropertyIds.IS_LATEST_VERSION);
        }
        if (properties.containsKey(PropertyIds.IS_MAJOR_VERSION)) {
            properties.remove(PropertyIds.IS_MAJOR_VERSION);
        }
        if (properties.containsKey(PropertyIds.IS_LATEST_MAJOR_VERSION)) {
            properties.remove(PropertyIds.IS_LATEST_MAJOR_VERSION);
        }
        if (properties.containsKey(PropertyIds.VERSION_LABEL)) {
            properties.remove(PropertyIds.VERSION_LABEL);
        }
        if (properties.containsKey(PropertyIds.VERSION_SERIES_ID)) {
            properties.remove(PropertyIds.VERSION_SERIES_ID);
        }
        if (properties.containsKey(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT)) {
            properties.remove(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
        }
        if (properties.containsKey(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY)) {
            properties.remove(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY);
        }
        if (properties.containsKey(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID)) {
            properties.remove(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
        }
        if (properties.containsKey(PropertyIds.CHECKIN_COMMENT)) {
            properties.remove(PropertyIds.CHECKIN_COMMENT);
        }
        if (properties.containsKey(PropertyIds.CONTENT_STREAM_LENGTH)) {
            properties.remove(PropertyIds.CONTENT_STREAM_LENGTH);
        }
        if (properties.containsKey(PropertyIds.CONTENT_STREAM_MIME_TYPE)) {
            properties.remove(PropertyIds.CONTENT_STREAM_MIME_TYPE);
        }
        if (properties.containsKey(PropertyIds.CONTENT_STREAM_FILE_NAME)) {
            properties.remove(PropertyIds.CONTENT_STREAM_FILE_NAME);
        }
        if (properties.containsKey(PropertyIds.CONTENT_STREAM_ID)) {
            properties.remove(PropertyIds.CONTENT_STREAM_ID);
        }
        // ---- folder ----
        if (properties.containsKey(PropertyIds.PARENT_ID)) {
            properties.remove(PropertyIds.PARENT_ID);
        }
        if (properties.containsKey(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS)) {
            properties.remove(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS);
        }
        if (properties.containsKey(PropertyIds.PATH)) {
            properties.remove(PropertyIds.PATH);
        }
        // ---- relationship ----
        if (properties.containsKey(PropertyIds.SOURCE_ID)) {
            properties.remove(PropertyIds.SOURCE_ID);
        }
        if (properties.containsKey(PropertyIds.TARGET_ID)) {
            properties.remove(PropertyIds.TARGET_ID);
        }
        // ---- policy ----
        if (properties.containsKey(PropertyIds.POLICY_TEXT)) {
            properties.remove(PropertyIds.POLICY_TEXT);
        }
    }

    @Override
    public int getAclId() {
        return fAclId;
    }

    public void setAclId(int aclId) {
        fAclId = aclId;
    }

    @Override
    public AllowableActions getAllowableActions(CallContext context, String user) {
        AllowableActions actions = DataObjectCreator.fillAllowableActions(context, this, user);
        return actions;
    }

    @Override
    public boolean hasRendition(String user) {
        return false;
    }

    protected ContentStream getIconFromResourceDir(String name) throws IOException {

        InputStream imageStream = StoredObjectImpl.class.getResourceAsStream(name);
        ByteArrayOutputStream ba = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int noBytesRead = 0;

        try {
            while ((noBytesRead = imageStream.read(buffer)) >= 0) {
                ba.write(buffer, 0, noBytesRead);
            }
        } finally {
            IOUtils.closeQuietly(ba);
            IOUtils.closeQuietly(imageStream);
        }

        ContentStreamDataImpl content = new ContentStreamDataImpl(0);
        content.setFileName(name);
        content.setMimeType("image/png");
        content.setContent(new ByteArrayInputStream(ba.toByteArray()));
        return content;
    }

    protected boolean testRenditionFilterForImage(String[] formats) {
        if (formats.length == 1 && null != formats[0] && formats[0].equals("cmis:none")) {
            return false;
        } else {
            return arrayContainsString(formats, "*") || arrayContainsString(formats, "image/*")
                    || arrayContainsString(formats, "image/jpeg");
        }
    }

    private boolean arrayContainsString(String[] formats, String val) {
        for (String s : formats) {
            if (val.equals(s)) {
                return true;
            }
        }
        return false;
    }

}
