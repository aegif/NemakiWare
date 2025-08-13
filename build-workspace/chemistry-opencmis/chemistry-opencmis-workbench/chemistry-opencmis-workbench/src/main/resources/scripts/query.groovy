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

String cql = "SELECT cmis:objectId, cmis:name, cmis:contentStreamLength FROM cmis:document"

ItemIterable<QueryResult> results = session.query(cql, false)

//ItemIterable<QueryResult> results = session.query(cql, false).getPage(10)
//ItemIterable<QueryResult> results = session.query(cql, false).skipTo(10).getPage(10)

results.each { hit ->
    hit.properties.each { println "${it.queryName}: ${it.firstValue}" }
    println "--------------------------------------"
}

println "--------------------------------------"
println "Total number: ${results.totalNumItems}"
println "Has more: ${results.hasMoreItems}"
println "--------------------------------------"