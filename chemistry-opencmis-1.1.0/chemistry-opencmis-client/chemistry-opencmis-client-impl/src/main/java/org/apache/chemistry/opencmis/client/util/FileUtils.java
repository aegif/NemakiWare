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
package org.apache.chemistry.opencmis.client.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;

/**
 * A set of utility methods that simplify file and folder operations.
 */
public final class FileUtils {

    private FileUtils() {
    }

    /**
     * Gets an object by path or object id.
     * 
     * @param pathOrIdOfObject
     *            the path or object id
     * @param session
     *            the session
     * @return the object
     * 
     * @throws CmisBaseException
     *             if something go wrong, for example the object doesn't exist
     */
    public static CmisObject getObject(String pathOrIdOfObject, Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session must be set!");
        }
        if (pathOrIdOfObject == null || pathOrIdOfObject.length() == 0) {
            throw new IllegalArgumentException("pathOrIdOfObject must be set!");
        }

        CmisObject result = null;
        if (pathOrIdOfObject.charAt(0) == '/') {
            result = session.getObjectByPath(pathOrIdOfObject);
        } else {
            result = session.getObject(pathOrIdOfObject);
        }

        return result;
    }

    /**
     * Gets a folder by path or object id.
     * 
     * @param pathOrIdOfObject
     *            the path or folder id
     * @param session
     *            the session
     * @return the folder object
     * 
     * @throws CmisBaseException
     *             if something go wrong, for example the object doesn't exist
     */
    public static Folder getFolder(String pathOrIdOfObject, Session session) {
        CmisObject folder = getObject(pathOrIdOfObject, session);

        if (folder instanceof Folder) {
            return (Folder) folder;
        } else {
            throw new IllegalArgumentException("Object is not a folder!");
        }
    }

    /**
     * Creates a document from a file.
     * 
     * @param parentIdOrPath
     *            the id or path of the parent folder
     * @param file
     *            the source file
     * @param type
     *            the document type (defaults to <code>cmis:document</code>)
     * @param versioningState
     *            the versioning state or <code>null</code>
     * @return the newly created document
     * 
     * @throws FileNotFoundException
     *             if the file does not exist
     * @throws CmisBaseException
     *             if something go wrong, for example the object doesn't exist
     */
    public static Document createDocumentFromFile(String parentIdOrPath, File file, String type,
            VersioningState versioningState, Session session) throws FileNotFoundException {
        if (type == null) {
            type = BaseTypeId.CMIS_DOCUMENT.value(); // "cmis:document";
        }

        Folder parentFolder = getFolder(parentIdOrPath, session);

        String name = file.getName();

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, type);
        properties.put(PropertyIds.NAME, name);

        ContentStream contentStream = ContentStreamUtils.createFileContentStream(name, file);

        try {
            return parentFolder.createDocument(properties, contentStream, versioningState);
        } finally {
            IOUtils.closeQuietly(contentStream);
        }
    }

    /**
     * Creates a text document from a string.
     * 
     * @param parentIdOrPath
     *            the id or path of the parent folder
     * @param name
     *            the document name
     * @param content
     *            the content string
     * @param type
     *            the document type (defaults to <code>cmis:document</code>)
     * @param versioningState
     *            the versioning state or <code>null</code>
     * @param session
     *            the session
     * @return the newly created document
     */
    public static Document createTextDocument(String parentIdOrPath, String name, String content, String type,
            VersioningState versioningState, Session session) {
        if (type == null) {
            type = BaseTypeId.CMIS_DOCUMENT.value(); // "cmis:document";
        }

        Folder parentFolder = getFolder(parentIdOrPath, session);

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, type);
        properties.put(PropertyIds.NAME, name);

        ContentStream contentStream = ContentStreamUtils.createTextContentStream(name, content);

        try {
            return parentFolder.createDocument(properties, contentStream, versioningState);
        } finally {
            IOUtils.closeQuietly(contentStream);
        }
    }

    /**
     * Creates a child folder with the name specified of the type specified. If
     * type is null then will default to cmis:folder.
     * 
     * @param parentFolder
     *            the parent folder
     * @param name
     *            the folder name
     * @param type
     *            the folder type (defaults to <code>cmis:folder</code>)
     * @return the newly created folder
     * 
     * @throws CmisBaseException
     *             if something go wrong, for example the parent folder doesn't
     *             exist
     */
    public static Folder createFolder(Folder parentFolder, String name, String type) {
        if (type == null) {
            type = BaseTypeId.CMIS_FOLDER.value();
        }

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, type);
        properties.put(PropertyIds.NAME, name);

        return parentFolder.createFolder(properties);
    }

    /**
     * Creates a folder using a String identifier.
     * 
     * @param parentIdOrPath
     *            the id or path of the parent folder
     * @param name
     *            the folder name
     * @param type
     *            the folder type (defaults to <code>cmis:folder</code>)
     * @param session
     *            the session
     * @return the newly created folder
     * 
     * @throws CmisBaseException
     *             if something go wrong, for example the parent folder doesn't
     *             exist
     */
    public static Folder createFolder(String parentIdOrPath, String name, String type, Session session) {
        Folder parentFolder = getFolder(parentIdOrPath, session);

        if (type == null) {
            type = BaseTypeId.CMIS_FOLDER.value();
        }

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, type);
        properties.put(PropertyIds.NAME, name);

        return parentFolder.createFolder(properties);
    }

    /**
     * Downloads the contentStream for the given doc to the specified path.
     * 
     * @param doc
     *            the document
     * @param destinationPath
     *            the destination path
     * 
     * @throws IOException
     *             if the download fails because of an IO problem
     * @throws CmisBaseException
     *             if something go wrong, for example the document doesn't exist
     */
    public static void download(Document doc, String destinationPath) throws IOException {
        if (doc == null) {
            return;
        }

        ContentStreamUtils.writeContentStreamToFile(doc.getContentStream(), new File(destinationPath));
    }

    /**
     * Downloads a document by its id or path.
     * 
     * @param docIdOrPath
     *            the id or path of the document
     * @param destinationPath
     *            the destination path
     * @param session
     *            the session
     * 
     * @throws IOException
     *             if the download fails because of an IO problem
     * @throws CmisBaseException
     *             if something go wrong, for example the document doesn't exist
     */
    public static void download(String docIdOrPath, String destinationPath, Session session) throws IOException {
        CmisObject doc = getObject(docIdOrPath, session);

        if (doc instanceof Document) {
            download((Document) doc, destinationPath);
        } else {
            throw new IllegalArgumentException("Object is not a document!");
        }
    }

    /**
     * Deletes an object by path or id (string identifier).
     * 
     * @param pathOrIdOfObject
     *            the id or path of the object
     * @param session
     *            the session
     * 
     * @throws CmisBaseException
     *             if something go wrong, for example the object doesn't exist
     */
    public static void delete(String pathOrIdOfObject, Session session) {
        CmisObject object = getObject(pathOrIdOfObject, session);

        if (object instanceof Folder) {
            ((Folder) object).deleteTree(true, UnfileObject.DELETE, true);
        } else {
            object.delete(true);
        }
    }

    /**
     * Prints out all of the properties for this object to System.out.
     * 
     * @param object
     *            the object
     */
    public static void printProperties(CmisObject object) {
        printProperties(object, System.out);
    }

    /**
     * Prints out all of the properties for this object to the given
     * PrintStream.
     * 
     * @param object
     *            the object
     */
    public static void printProperties(CmisObject object, PrintStream out) {
        for (Property<?> prop : object.getProperties()) {
            printProperty(prop, out);
        }
    }

    public static void printProperty(Property<?> prop) {
        printProperty(prop, System.out);
    }

    public static void printProperty(Property<?> prop, PrintStream out) {
        out.println(prop.getId() + ": " + prop.getValuesAsString());
    }
}
