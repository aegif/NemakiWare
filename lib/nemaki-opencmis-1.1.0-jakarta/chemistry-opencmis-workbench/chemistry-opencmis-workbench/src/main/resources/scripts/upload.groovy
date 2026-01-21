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

import org.apache.chemistry.opencmis.commons.*
import org.apache.chemistry.opencmis.commons.data.*
import org.apache.chemistry.opencmis.commons.enums.*
import org.apache.chemistry.opencmis.client.api.*
import org.apache.chemistry.opencmis.client.util.*

cmis = new scripts.CMIS(session)

// destination folder
Folder destFolder = cmis.getFolder("/")

// source folder
String localPath = "/some/local/folder"

// upload folder tree
upload(destFolder, localPath)


//--------------------------------------------------

def upload(destination, String localPath,
        String folderType = "cmis:folder",
        String documentType = "cmis:document",
        VersioningState versioningState = VersioningState.MAJOR) {

    println "Uploading...\n"
    doUpload(destination, new File(localPath), folderType, documentType, versioningState)
    println "\n...done."
}

def doUpload(Folder parent, File folder, String folderType, String documentType, VersioningState versioningState) {
    folder.eachFile {
        if (it.name.startsWith(".")) {
            println "Skipping ${it.name}"
            return
        }

        println it.name

        if (it.isFile()) {
            cmis.createDocumentFromFile(parent, it, documentType, versioningState)
        }
        else if(it.isDirectory()) {
            Folder newFolder = cmis.createFolder(parent, it.name, folderType)
            doUpload(newFolder, it, folderType, documentType, versioningState)
        }
    }
}
