#!/bin/bash

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

#Publishes dist artifacts and stages CMS site to production

# Version to release
VERSION=$1
TARGET_DIST_DIR=/www/www.apache.org/dist/chemistry/opencmis/${VERSION}

# Deploys dist packages
echo "Deploying dist packages"
mkdir ${TARGET_DIST_DIR} 
cp ~/public_html/chemistry/opencmis/${VERSION}/dist/* ${TARGET_DIST_DIR}

echo "Publishing site with CMS"
publish.pl chemistry ${USER}

echo "Release pushed"

