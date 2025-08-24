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

// variable 'session' is bound to the current OpenCMIS session


// print the repository name - Java style
println "Repository: " + session.getRepositoryInfo().getName()

// print the repository name - Groovy style
println "Repository: ${session.repositoryInfo.name}"


// get root folder
Folder root = session.getRootFolder()
println "--- Root Folder: " + root.getName() + " ---"

// print root folder children
for(CmisObject object: root.getChildren()) {
    println object.getName() + " \t(" + object.getType().getId() + ")"
}

// run a quick query
for(QueryResult hit: session.query("SELECT * FROM cmis:document", false)) {
    hit.properties.each{ println "${it.queryName}: ${it.firstValue}" }
    println "----------------------------------"
}

// CMIS helper script
def cmis = new scripts.CMIS(session)

cmis.printProperties "/"                    // access by path
cmis.printProperties session.rootFolder.id  // access by id
cmis.printProperties session.rootFolder     // access by object

// Folder folder = cmis.createFolder("/", "test-folder", "cmis:folder")

// Document doc = cmis.createTextDocument(folder, "test.txt", "Hello World!", "cmis:document")
// cmis.printProperties doc
// cmis.download(doc, "/some/path/helloWorld.txt")
// cmis.delete doc.id

// cmis.delete folder


// see /scripts/CMIS.groovy for more methods