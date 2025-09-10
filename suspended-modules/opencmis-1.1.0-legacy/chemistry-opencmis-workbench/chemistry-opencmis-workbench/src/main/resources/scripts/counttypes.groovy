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

println "'cmis:document' and subtypes:     ${countTypes('cmis:document')}"
println "'cmis:item' and subtypes:         ${countTypes('cmis:item')}"
println "'cmis:folder' and subtypes:       ${countTypes('cmis:folder')}"
println "'cmis:relationship' and subtypes: ${countTypes('cmis:relationship')}"
println "'cmis:policy' and subtypes:       ${countTypes('cmis:policy')}"



int countTypes(String typeId) {
    def counter = 0

    try {
        session.getTypeDescendants(typeId, -1, false).each { counter += 1 + count(it) }
        counter++
    }
    catch (any) {
    }

    counter
}

int count(Tree tree) {
    def counter = 0
    tree.children.each { counter += 1 + count(it) }

    counter
}