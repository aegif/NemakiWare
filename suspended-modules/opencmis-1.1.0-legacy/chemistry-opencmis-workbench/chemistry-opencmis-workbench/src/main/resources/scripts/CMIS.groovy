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
package scripts

import org.apache.chemistry.opencmis.commons.*
import org.apache.chemistry.opencmis.commons.data.*
import org.apache.chemistry.opencmis.commons.enums.*
import org.apache.chemistry.opencmis.commons.definitions.*
import org.apache.chemistry.opencmis.client.api.*

class CMIS {

    Session session

    CMIS(Session session) {
        this.session = session
    }

    CmisObject getObject(id) {
        CmisObject result = null

        if (id instanceof CmisObject) {
            result = id
        } else if (id instanceof ObjectId) {
            result = session.getObject(id)
        } else if (id instanceof String) {
            if (id.startsWith("/")) {
                result = session.getObjectByPath(id)
            } else {
                result = session.getObject(id)
            }
        }

        if (result == null) {
            throw new Exception("Object not found!")
        }

        result
    }

    Folder getFolder(id) {
        CmisObject folder = getObject(id)
        if(!(folder instanceof Folder)) {
            throw new Exception("Object is not a folder!")
        }

        folder
    }

    Document getDocument(id) {
        CmisObject doc = getObject(id)
        if(!(doc instanceof Document)) {
            throw new Exception("Object is not a document!")
        }

        doc
    }

    void printProperties(id) {
        CmisObject object = getObject(id)

        if(!object.properties) {
            println "- no properties (???) -"
        } else {
            object.properties.each { prop -> printProperty(prop) }
        }
    }

    void printProperty(Property prop) {
        println "${prop.id}: ${prop.valuesAsString}"
    }

    void printAllowableActions(id) {
        CmisObject object = getObject(id)

        if (!object.allowableActions || !object.allowableActions.allowableActions) {
            println "- no allowable actions -"
        } else {
            object.allowableActions.allowableActions.each { action ->
                println action.value()
            }
        }
    }

    void printVersions(id) {
        Document doc = getDocument(id)

        if (!((DocumentType) doc.type).isVersionable()) {
            println "- not versionsable -"
            return
        }

        List<Document> versions = doc.allVersions

        if (!versions) {
            println "- no versions -"
        } else {
            versions.each { version -> println "${version.versionLabel} (${version.id}) [${version.type.id}]" }
        }
    }

    void printChildren(id) {
        Folder folder = getFolder(id)

        boolean hasChildren = false
        folder.children.each { child ->
            println "${child.name} (${child.id}) [${child.type.id}]"
            hasChildren = true
        }

        if (!hasChildren) {
            println "- no children -"
        }
    }

    void printRelationships(id) {
        CmisObject object = getObject(id)

        boolean hasRelationships = false
        object.relationships.each { rel ->
            println "${rel.name} (${rel.id}) [${rel.type.id}]"
            hasRelationships = true
        }

        if (!hasRelationships) {
            println "- no relationships -"
        }
    }

    void printRenditions(id) {
        Document doc = getDocument(id)

        List<Rendition> renditons = doc.renditions

        if(!renditons) {
            println "- no renditions -"
        } else {
            renditons.each { rendition -> println "${rendition.title} (MIME type: ${rendition.mimeType}, length: ${rendition.length} bytes" }
        }
    }

    void printObjectSummary(id) {
        CmisObject object = getObject(id)

        println "Name:        ${object.name}"
        println "Object Id:   ${object.id}"
        println "Object Type: ${object.type.id}"
        println ""
        println "--------------------------------------------------"
        println "Properties:"
        println "--------------------------------------------------"
        printProperties(object)
        println ""
        println "--------------------------------------------------"
        println "Allowable Actions:"
        println "--------------------------------------------------"
        printAllowableActions(object)
        println ""
        println "--------------------------------------------------"
        println "Relationships:"
        println "--------------------------------------------------"
        printRelationships(object)

        if(object instanceof Document) {
            println ""
            println "--------------------------------------------------"
            println "Versions:"
            println "--------------------------------------------------"
            printVersions(object)
            println ""
            println "--------------------------------------------------"
            println "Renditions:"
            println "--------------------------------------------------"
            printRenditions(object)
        }

        if(object instanceof Folder) {
            println ""
            println "--------------------------------------------------"
            println "Children:"
            println "--------------------------------------------------"
            printChildren(id)
        }
    }

    void download(id, destination) {
        Document doc = getDocument(id)

        def file = new FileOutputStream(destination)
        def out = new BufferedOutputStream(file)
        if(doc.contentStream != null && doc.contentStream.stream != null) {
            out << doc.contentStream.stream
        }
        out.close()
    }

    Folder createFolder(parent, String name, String type = "cmis:folder") {
        CmisObject parentFolder = getFolder(parent)

        def properties = [
            (PropertyIds.OBJECT_TYPE_ID): type,
            (PropertyIds.NAME): name
        ]

        parentFolder.createFolder(properties)
    }

    Document createTextDocument(parent, String name, String content, String type = "cmis:document",
            VersioningState versioningState = VersioningState.MAJOR) {
        CmisObject parentFolder = getFolder(parent)

        def properties = [
            (PropertyIds.OBJECT_TYPE_ID): type,
            (PropertyIds.NAME): name
        ]

        def stream = new ByteArrayInputStream(content.bytes)

        def contentStream = session.objectFactory.createContentStream(name, content.bytes.length, "text/plain", stream)

        parentFolder.createDocument(properties, contentStream, versioningState)
    }

    Document createDocumentFromFile(parent, File file, String type = "cmis:document",
            VersioningState versioningState = VersioningState.MAJOR) {
        CmisObject parentFolder = getFolder(parent)

        def name = file.name
        def mimetype = org.apache.chemistry.opencmis.commons.impl.MimeTypes.getMIMEType(file)

        def properties = [
            (PropertyIds.OBJECT_TYPE_ID): type,
            (PropertyIds.NAME): name
        ]

        def contentStream = session.objectFactory.createContentStream(name, file.size(), mimetype, new FileInputStream(file))

        parentFolder.createDocument(properties, contentStream, versioningState)
    }

    Relationship createRelationship(source, target, name, type) {
        CmisObject sourceObject = getObject(source)
        CmisObject targetObject = getObject(target)

        def properties = [
            (PropertyIds.OBJECT_TYPE_ID): type,
            (PropertyIds.NAME): name,
            (PropertyIds.SOURCE_ID): sourceObject.id,
            (PropertyIds.TARGET_ID): targetObject.id
        ]

        getObject(session.createRelationship(properties))
    }

    void delete(id) {
        if (id instanceof ObjectId) {
            session.delete(id)
        } else{
            session.delete(session.createObjectId(id))
        }
    }
}