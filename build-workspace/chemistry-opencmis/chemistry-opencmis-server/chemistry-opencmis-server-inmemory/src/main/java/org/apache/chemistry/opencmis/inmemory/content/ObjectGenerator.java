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
package org.apache.chemistry.opencmis.inmemory.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;
import org.apache.chemistry.opencmis.inmemory.content.fractal.FractalGenerator;
import org.apache.chemistry.opencmis.inmemory.content.loremipsum.LoremIpsum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple helper class for the tests that generates a sample folder hierarchy
 * and optionally documents in it.
 */
public class ObjectGenerator {

    private static final int KILO = 1024;
    private static final Logger LOG = LoggerFactory.getLogger(ObjectGenerator.class);
    private final BindingsObjectFactory fFactory;
    NavigationService fNavSvc;
    ObjectService fObjSvc;
    RepositoryService fRepSvc;
    private final String fRepositoryId;
    private boolean fCleanup;
    List<String> fTopLevelDocsCreated; // list of ids created on first level
    List<String> fTopLevelFoldersCreated; // list of ids created on first level

    /**
     * supported kinds of content
     * 
     */
    public enum ContentKind {
        STATIC_TEXT, LOREM_IPSUM_TEXT, LOREM_IPSUM_HTML, IMAGE_FRACTAL_JPEG
    }

    /**
     * Indicates if / how many documents are created in each folder
     */
    private int fNoDocumentsToCreate;

    /**
     * The type id of the document id that is created.
     */
    private String fDocTypeId = BaseTypeId.CMIS_DOCUMENT.value();

    /**
     * The type id of the folder that is created.
     */
    private String fFolderTypeId = BaseTypeId.CMIS_FOLDER.value();

    /**
     * A list of property ids. For each element in this list a String property
     * value is created for each creation of a document. All ids must be valid
     * string property id of the type fDocTypeId
     */
    private List<String> fStringPropertyIdsToSetForDocument;

    /**
     * A list of property ids. For each element in this list a String property
     * value is created for each creation of a folder. All ids must be valid
     * string property id of the type fFolderTypeId
     */
    private List<String> fStringPropertyIdsToSetForFolder;

    /**
     * number of documents created in total
     */
    private int fDocumentsInTotalCount = 0;

    /**
     * number of folders created in total
     */
    private int fFoldersInTotalCount = 0;

    /**
     * size of content in KB, if 0 create documents without content
     */
    private int fContentSizeInK = 0;

    /**
     * Kind of content to create
     */
    private ContentKind fContentKind;

    private static final String NAMEPROPVALPREFIXDOC = "My_Document-";
    private static final String NAMEPROPVALPREFIXFOLDER = "My_Folder-";
    private static final String STRINGPROPVALPREFIXDOC = "My Doc StringProperty ";
    private static final String STRINGPROPVALPREFIXFOLDER = "My Folder StringProperty ";
    private int propValCounterDocString = 0;
    private int propValCounterFolderString = 0;
    /**
     * use UUIDs to generate folder and document names
     */
    private boolean fUseUuids;

    /**
     * generator for images
     */
    private FractalGenerator fractalGenerator = null;

    public ObjectGenerator(BindingsObjectFactory factory, NavigationService navSvc, ObjectService objSvc,
            RepositoryService repSvc, String repositoryId, ContentKind contentKind) {
        super();
        fFactory = factory;
        fNavSvc = navSvc;
        fObjSvc = objSvc;
        fRepSvc = repSvc;
        fRepositoryId = repositoryId;
        // create an empty list of properties to generate by default for folder
        // and document
        fStringPropertyIdsToSetForDocument = new ArrayList<String>();
        fStringPropertyIdsToSetForFolder = new ArrayList<String>();
        fNoDocumentsToCreate = 0;
        fUseUuids = false;
        fCleanup = false;
        fTopLevelDocsCreated = new ArrayList<String>();
        fTopLevelFoldersCreated = new ArrayList<String>();
        fContentKind = contentKind;
    }

    public void setNumberOfDocumentsToCreatePerFolder(int noDocumentsToCreate) {
        fNoDocumentsToCreate = noDocumentsToCreate;
    }

    public void setFolderTypeId(String folderTypeId) {
        fFolderTypeId = folderTypeId;
    }

    public void setDocumentTypeId(String docTypeId) {
        fDocTypeId = docTypeId;
    }

    public void setDocumentPropertiesToGenerate(List<String> propertyIds) {
        fStringPropertyIdsToSetForDocument = propertyIds;
    }

    public void setFolderPropertiesToGenerate(List<String> propertyIds) {
        fStringPropertyIdsToSetForFolder = propertyIds;
    }

    public void setContentSizeInKB(int sizeInK) {
        fContentSizeInK = sizeInK;
    }

    public ContentKind getContentKind() {
        return fContentKind;
    }

    public void setLoreIpsumGenerator(ContentKind contentKind) {
        fContentKind = contentKind;
    }

    public void setCleanUpAfterCreate(boolean doCleanup) {
        fCleanup = doCleanup;
    }

    public void createFolderHierachy(int levels, int childrenPerLevel, String rootFolderId) {
        resetCounters();
        fTopLevelFoldersCreated.clear();
        fTopLevelDocsCreated.clear();
        createFolderHierachy(rootFolderId, 0, levels, childrenPerLevel);
        if (fCleanup) {
            deleteTree();
        }
    }

    public void setUseUuidsForNames(boolean useUuids) {
        /**
         * use UUIDs to generate folder and document names
         */
        fUseUuids = useUuids;
    }

    /**
     * Retrieves the index-th folder from given level of the hierarchy starting
     * at rootId.
     */
    public String getFolderId(String rootId, int level, int index) {
        String objectId = rootId;
        final String requiredProperties = PropertyIds.OBJECT_ID + "," + PropertyIds.OBJECT_TYPE_ID + ","
                + PropertyIds.BASE_TYPE_ID;
        // Note: This works because first folders are created then documents
        for (int i = 0; i < level; i++) {
            ObjectInFolderList result = fNavSvc.getChildren(fRepositoryId, objectId, requiredProperties,
                    PropertyIds.OBJECT_TYPE_ID, false, IncludeRelationships.NONE, null, true, BigInteger.valueOf(-1),
                    BigInteger.valueOf(-1), null);
            List<ObjectInFolderData> children = result.getObjects();
            ObjectData child = children.get(index).getObject();
            objectId = (String) child.getProperties().getProperties().get(PropertyIds.OBJECT_ID).getFirstValue();
        }
        return objectId;
    }

    /**
     * Retrieves the index-th document from given folder.
     * 
     * @param folderId
     *            folder to retrieve document from
     * @param index
     *            index of document to retrieve from this folder
     */
    public String getDocumentId(String folderId, int index) {
        String docId = null;
        final String requiredProperties = PropertyIds.OBJECT_ID + "," + PropertyIds.OBJECT_TYPE_ID + ","
                + PropertyIds.BASE_TYPE_ID;
        ObjectInFolderList result = fNavSvc.getChildren(fRepositoryId, folderId, requiredProperties,
                PropertyIds.OBJECT_TYPE_ID, false, IncludeRelationships.NONE, null, true, BigInteger.valueOf(-1),
                BigInteger.valueOf(-1), null);
        List<ObjectInFolderData> children = result.getObjects();
        int numDocsFound = 0;
        for (int i = 0; i < children.size(); i++) {
            ObjectData child = children.get(i).getObject();
            docId = (String) child.getProperties().getProperties().get(PropertyIds.OBJECT_ID).getFirstValue();
            if (child.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
                if (numDocsFound == index) {
                    return docId;
                } else {
                    numDocsFound++;
                }
            }
        }
        return docId;
    }

    /**
     * Returns the total number of documents created.
     */
    public int getDocumentsInTotal() {
        return fDocumentsInTotalCount;
    }

    /**
     * Returns the total number of folders created.
     */
    public int getFoldersInTotal() {
        return fFoldersInTotalCount;
    }

    /**
     * Returns the total number of objects created.
     */
    public int getObjectsInTotal() {
        return fDocumentsInTotalCount + fFoldersInTotalCount;
    }

    public String createSingleDocument(String folderId) {
        String objectId = createDocument(folderId, 0, 0);
        if (fCleanup) {
            deleteObject(objectId);
        }
        return objectId;
    }

    public String[] createDocuments(String folderId, int count) {
        String[] result;

        for (int i = 0; i < count; i++) {
            String id = createDocument(folderId, 0, 0);
            fTopLevelDocsCreated.add(id);
        }
        if (fCleanup) {
            deleteTree();
            result = null;
        } else {
            result = new String[count];
            for (int i = 0; i < fTopLevelDocsCreated.size(); i++) {
                result[i] = fTopLevelDocsCreated.get(i);
            }
        }
        return result;
    }

    public String[] createFolders(String folderId, int count) {
        String[] result;

        for (int i = 0; i < count; i++) {
            createFolder(folderId);
        }
        if (fCleanup) {
            deleteTree();
            result = null;
        } else {
            result = new String[count];
            for (int i = 0; i < fTopLevelFoldersCreated.size(); i++) {
                result[i] = fTopLevelFoldersCreated.get(i);
            }
        }
        return result;
    }

    public void resetCounters() {
        fDocumentsInTotalCount = 0;
        fFoldersInTotalCount = 0;
    }

    private void createFolderHierachy(String parentId, int level, int levels, int childrenPerLevel) {
        String id = null;

        if (level >= levels) {
            return;
        }

        LOG.debug(" create folder for parent id: " + parentId + ", in level " + level + ", max levels " + levels);

        for (int i = 0; i < childrenPerLevel; i++) {
            Properties props = createFolderProperties(i, level);
            id = fObjSvc.createFolder(fRepositoryId, props, parentId, null, null, null, null);
            if (level == 0) {
                fTopLevelFoldersCreated.add(id);
            }

            if (id != null) {
                ++fFoldersInTotalCount;
                createFolderHierachy(id, level + 1, levels, childrenPerLevel);
            }
        }
        for (int j = 0; j < fNoDocumentsToCreate; j++) {
            id = createDocument(parentId, j, level);
            if (level == 0) {
                fTopLevelDocsCreated.add(id);
            }
        }
    }

    private String createFolder(String parentId) {
        Properties props = createFolderProperties(0, 0);
        String id = null;
        id = fObjSvc.createFolder(fRepositoryId, props, parentId, null, null, null, null);
        fTopLevelFoldersCreated.add(id);
        return id;
    }

    private String createDocument(String folderId, int no, int level) {
        ContentStream contentStream = null;
        VersioningState versioningState = VersioningState.NONE;
        List<String> policies = null;
        Acl addACEs = null;
        Acl removeACEs = null;
        ExtensionsData extension = null;

        LOG.debug("create document in folder " + folderId);
        Properties props = createDocumentProperties(no, level);
        String id = null;

        if (fContentSizeInK > 0) {
            switch (fContentKind) {
            case STATIC_TEXT:
                contentStream = createContentStaticText();
                break;
            case LOREM_IPSUM_TEXT:
                contentStream = createContentLoremIpsumText();
                break;
            case LOREM_IPSUM_HTML:
                contentStream = createContentLoremIpsumHtml();
                break;
            case IMAGE_FRACTAL_JPEG:
                contentStream = createContentFractalimageJpeg();
                break;
            }
        }

        id = fObjSvc.createDocument(fRepositoryId, props, folderId, contentStream, versioningState, policies, addACEs,
                removeACEs, extension);

        if (null == id) {
            LOG.error("createDocument failed.");
        }
        ++fDocumentsInTotalCount;
        return id;
    }

    private void deleteTree() {

        // delete all documents from first level
        for (String id : fTopLevelDocsCreated) {
            deleteObject(id);
        }

        // delete recursively all folders from first level
        for (String id : fTopLevelFoldersCreated) {
            fObjSvc.deleteTree(fRepositoryId, id, true, UnfileObject.DELETE, true, null);
        }
    }

    private void deleteObject(String objectId) {
        fObjSvc.deleteObject(fRepositoryId, objectId, true, null);
    }

    public ContentStream createContentLoremIpsumHtml() {
        ContentStreamImpl content = new ContentStreamImpl();
        content.setFileName("data.html");
        content.setMimeType("text/html");
        int len = fContentSizeInK * KILO; // size of document in K

        LoremIpsum ipsum = new LoremIpsum();
        String text = ipsum.generateParagraphsFullHtml(len, true);

        content.setStream(new ByteArrayInputStream(IOUtils.toUTF8Bytes(text)));
        return content;
    }

    public ContentStream createContentLoremIpsumText() {
        ContentStreamImpl content = new ContentStreamImpl();
        content.setFileName("data.txt");
        content.setMimeType("text/plain");
        int len = fContentSizeInK * 1024; // size of document in K

        LoremIpsum ipsum = new LoremIpsum();
        String text = ipsum.generateParagraphsPlainText(len, 80, true);
        content.setStream(new ByteArrayInputStream(IOUtils.toUTF8Bytes(text)));
        return content;
    }

    public ContentStream createContentStaticText() {
        ContentStreamImpl content = new ContentStreamImpl();
        content.setFileName("data.txt");
        content.setMimeType("text/plain");
        int len = fContentSizeInK * 1024; // size of document in K
        byte[] b = { 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x0c, 0x0a,
                0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x0c, 0x0a }; // 32
        // Bytes
        ByteArrayOutputStream ba = new ByteArrayOutputStream(len);
        try {
            for (int j = 0; j < fContentSizeInK; j++) {
                // write 1K of data
                for (int i = 0; i < 32; i++) {
                    ba.write(b);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to fill content stream with data", e);
        }
        content.setStream(new ByteArrayInputStream(ba.toByteArray()));
        return content;
    }

    public ContentStream createContentFractalimageJpeg() {
        if (null == fractalGenerator) {
            fractalGenerator = new FractalGenerator();
        }

        ContentStreamImpl content = null;

        try {
            ByteArrayOutputStream bos = fractalGenerator.generateFractal();
            content = new ContentStreamImpl();
            content.setFileName("image.jpg");
            content.setMimeType("image/jpeg");
            content.setStream(new ByteArrayInputStream(bos.toByteArray()));
            bos.close();
        } catch (IOException e) {
            System.err.println("Error when generating fractal image: " + e);
            e.printStackTrace();
        }

        return content;
    }

    private Properties createFolderProperties(int no, int level) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyStringData(PropertyIds.NAME, generateFolderNameValue(no, level)));
        properties.add(fFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, fFolderTypeId));
        // Generate some property values for custom attributes
        for (String stringPropId : fStringPropertyIdsToSetForFolder) {
            properties.add(fFactory.createPropertyStringData(stringPropId, generateStringPropValueFolder()));
        }
        Properties props = fFactory.createPropertiesData(properties);
        return props;
    }

    private Properties createDocumentProperties(int no, int level) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyStringData(PropertyIds.NAME, generateDocNameValue(no, level)));
        properties.add(fFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, fDocTypeId));
        // Generate some property values for custom attributes
        for (String stringPropId : fStringPropertyIdsToSetForDocument) {
            properties.add(fFactory.createPropertyStringData(stringPropId, generateStringPropValueDoc()));
        }
        Properties props = fFactory.createPropertiesData(properties);
        return props;
    }

    private synchronized int incrementPropCounterDocStringProp() {
        return propValCounterDocString++;
    }

    private synchronized int incrementPropCounterFolderStringProp() {
        return propValCounterFolderString++;
    }

    private String generateDocNameValue(int no, int level) {
        if (fUseUuids) {
            return UUID.randomUUID().toString();
        } else {
            return NAMEPROPVALPREFIXDOC + level + "-" + no;
        }
    }

    private String generateFolderNameValue(int no, int level) {
        if (fUseUuids) {
            return UUID.randomUUID().toString();
        } else {
            return NAMEPROPVALPREFIXFOLDER + level + "-" + no;
        }
    }

    private String generateStringPropValueDoc() {
        return STRINGPROPVALPREFIXDOC + incrementPropCounterDocStringProp();
    }

    private String generateStringPropValueFolder() {
        return STRINGPROPVALPREFIXFOLDER + incrementPropCounterFolderStringProp();
    }

    public void dumpFolder(String folderId, String propertyFilter) {
        LOG.debug("starting dumpFolder() id " + folderId + " ...");
        boolean allRequiredPropertiesArePresent = propertyFilter != null && propertyFilter.equals("*"); // can
        // be
        // optimized
        final String requiredProperties = allRequiredPropertiesArePresent ? propertyFilter : PropertyIds.OBJECT_ID
                + "," + PropertyIds.NAME + "," + PropertyIds.OBJECT_TYPE_ID + "," + PropertyIds.BASE_TYPE_ID;
        // if all required properties are contained in the filter use we use the
        // filter otherwise
        // we use our own set and get those from the filter later in an extra
        // call
        String propertyFilterIntern = allRequiredPropertiesArePresent ? propertyFilter : requiredProperties;
        dumpFolder(folderId, propertyFilterIntern, 0);
    }

    private void dumpFolder(String folderId, String propertyFilter, int depth) {
        boolean allRequiredPropertiesArePresent = propertyFilter.equals("*"); // can
        // be
        // optimized
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            prefix.append("   ");
        }

        ObjectInFolderList result = fNavSvc.getChildren(fRepositoryId, folderId, propertyFilter, null, false,
                IncludeRelationships.NONE, null, true, BigInteger.valueOf(-1), BigInteger.valueOf(-1), null);
        List<ObjectInFolderData> folders = result.getObjects();
        if (null != folders) {
            LOG.debug(prefix + "found " + folders.size() + " children in folder " + folderId);
            int no = 0;
            for (ObjectInFolderData folder : folders) {
                LOG.debug(prefix.toString() + ++no + ": found object with id: " + folder.getObject().getId()
                        + " and path segment: " + folder.getPathSegment());
                dumpObjectProperties(folder.getObject(), depth, propertyFilter, !allRequiredPropertiesArePresent);
                String objectTypeBaseId = folder.getObject().getBaseTypeId().value();
                if (objectTypeBaseId.equals(BaseTypeId.CMIS_FOLDER.value())) {
                    dumpFolder(folder.getObject().getId(), propertyFilter, depth + 1);
                } else if (objectTypeBaseId.equals(BaseTypeId.CMIS_DOCUMENT.value())) {
                    dumpObjectProperties(folder.getObject(), depth + 1, propertyFilter,
                            !allRequiredPropertiesArePresent);
                }
            }
        }
        LOG.debug(""); // add empty line
    }

    private void dumpObjectProperties(ObjectData object, int depth, String propertyFilter, boolean mustFetchProperties) {
        final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            prefix.append("   ");
        }

        LOG.debug(prefix + "found object id " + object.getId());
        Map<String, PropertyData<?>> propMap;
        if (mustFetchProperties) {
            String objId = (String) object.getProperties().getProperties().get(PropertyIds.OBJECT_ID).getFirstValue();
            Properties props = fObjSvc.getProperties(fRepositoryId, objId, propertyFilter, null);
            propMap = props.getProperties();
        } else {
            propMap = object.getProperties().getProperties();
        }
        StringBuilder valueStr = new StringBuilder("[");
        for (Map.Entry<String, PropertyData<?>> entry : propMap.entrySet()) {
            if (entry.getValue().getValues().size() > 1) {
                if (entry.getValue().getFirstValue() instanceof GregorianCalendar) {
                    for (Object obj : entry.getValue().getValues()) {
                        GregorianCalendar cal = (GregorianCalendar) obj;
                        valueStr.append(df.format(cal.getTime())).append(", ");
                    }
                    valueStr.append("]");
                } else {
                    valueStr = new StringBuilder(entry.getValue().getValues().toString());
                }
            } else {
                Object value = entry.getValue().getFirstValue();
                if (null != value) {
                    valueStr = new StringBuilder(value.toString());
                    if (value instanceof GregorianCalendar) {
                        valueStr = new StringBuilder(df.format(((GregorianCalendar) entry.getValue().getFirstValue())
                                .getTime()));
                    }
                }
            }
            LOG.debug(prefix + entry.getKey() + ": " + valueStr);
        }
        LOG.debug(""); // add empty line
    }

    public void createTypes(TypeDefinitionList typeDefList) {
        // for (TypeDefinition td : typeDefList.getList()) {
        // TODO: enable this if available!
        // fRepSvc.createTypeDefinition(fRepositoryId, td);
        // }
    }
}
