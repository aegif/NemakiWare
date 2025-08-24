#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and

# Uploads wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER} chemistry commodity packages for apache.org/dist release vote

# Version to release
VERSION=$1
# Example of relative folder chemistry-013/org/apache/chemistry/opencmis
STAGING_FOLDER=$2
STAGING_REPO=https://repository.apache.org/content/repositories


 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis/${VERSION}/chemistry-opencmis-${VERSION}-source-release.zip
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis/${VERSION}/chemistry-opencmis-${VERSION}-source-release.zip.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis/${VERSION}/chemistry-opencmis-${VERSION}-source-release.zip.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis/${VERSION}/chemistry-opencmis-${VERSION}-source-release.zip.sha1

 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-android-client/${VERSION}/chemistry-opencmis-android-client-${VERSION}-pack.zip
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-android-client/${VERSION}/chemistry-opencmis-android-client-${VERSION}-pack.zip.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-android-client/${VERSION}/chemistry-opencmis-android-client-${VERSION}-pack.zip.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-android-client/${VERSION}/chemistry-opencmis-android-client-${VERSION}-pack.zip.sha1


 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-bridge/${VERSION}/chemistry-opencmis-bridge-${VERSION}.war
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-bridge/${VERSION}/chemistry-opencmis-bridge-${VERSION}.war.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-bridge/${VERSION}/chemistry-opencmis-bridge-${VERSION}.war.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-bridge/${VERSION}/chemistry-opencmis-bridge-${VERSION}.war.sha1

 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-client-impl/${VERSION}/chemistry-opencmis-client-impl-${VERSION}-with-dependencies.zip
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-client-impl/${VERSION}/chemistry-opencmis-client-impl-${VERSION}-with-dependencies.zip.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-client-impl/${VERSION}/chemistry-opencmis-client-impl-${VERSION}-with-dependencies.zip.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-client-impl/${VERSION}/chemistry-opencmis-client-impl-${VERSION}-with-dependencies.zip.sha1


 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-dist/${VERSION}/chemistry-opencmis-dist-${VERSION}-server-webapps.zip
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-dist/${VERSION}/chemistry-opencmis-dist-${VERSION}-server-webapps.zip.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-dist/${VERSION}/chemistry-opencmis-dist-${VERSION}-server-webapps.zip.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-dist/${VERSION}/chemistry-opencmis-dist-${VERSION}-server-webapps.zip.sha1

 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-osgi-client/${VERSION}/chemistry-opencmis-osgi-client-${VERSION}.jar
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-osgi-client/${VERSION}/chemistry-opencmis-osgi-client-${VERSION}.jar.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-osgi-client/${VERSION}/chemistry-opencmis-osgi-client-${VERSION}.jar.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-osgi-client/${VERSION}/chemistry-opencmis-osgi-client-${VERSION}.jar.sha1

 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-server-bindings-war/${VERSION}/chemistry-opencmis-server-bindings-war-${VERSION}.war
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-server-bindings-war/${VERSION}/chemistry-opencmis-server-bindings-war-${VERSION}.war.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-server-bindings-war/${VERSION}/chemistry-opencmis-server-bindings-war-${VERSION}.war.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-server-bindings-war/${VERSION}/chemistry-opencmis-server-bindings-war-${VERSION}.war.sha1

 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-workbench/${VERSION}/chemistry-opencmis-workbench-${VERSION}-full.zip
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-workbench/${VERSION}/chemistry-opencmis-workbench-${VERSION}-full.zip.asc
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-workbench/${VERSION}/chemistry-opencmis-workbench-${VERSION}-full.zip.md5
 wget --no-check-certificate ${STAGING_REPO}/${STAGING_FOLDER}/chemistry-opencmis-workbench/${VERSION}/chemistry-opencmis-workbench-${VERSION}-full.zip.sha1

