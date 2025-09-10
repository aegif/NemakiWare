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

Folder folder = (Folder) session.getObjectByPath("/")


def stats = count(folder, true)

println "Folder ${folder.name}"
println "----------------------------------------------"
println "Folders:   ${stats['folders']}"
println "Documents: ${stats['documents']}"
println "Items:     ${stats['items']}"
println "Policies:  ${stats['policies']}"
println "Content:   ${stats['bytes']} bytes"



def count(Folder folder, boolean tree = false) {
    def stats = [:]
    stats["folders"] = 0
    stats["documents"] = 0
    stats["items"] = 0
    stats["policies"] = 0
    stats["bytes"] = 0

    OperationContext oc = session.createOperationContext()
    oc.setFilterString("cmis:objectId,cmis:contentStreamLength")
    oc.setIncludeAllowableActions(false)
    oc.setMaxItemsPerPage(10000)

    countInternal(folder, tree, oc, stats)

    stats
}

def countInternal(Folder folder, boolean tree, OperationContext oc, def stats) {
    folder.getChildren(oc).each { child ->
        if (child instanceof Folder) {
            stats["folders"]++
            if (tree) {
                countInternal(child, true, oc, stats)
            }
        } else if (child instanceof Document) {
            stats["documents"]++
            long size = ((Document) child).getContentStreamLength()
            if (size > 0) {
                stats["bytes"] += size
            }
        } else if (child instanceof Item) {
            stats["item"]++
        } else if (child instanceof Policy) {
            stats["policies"]++
        }
    }
}