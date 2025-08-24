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
# limitations under the License.

# @version@

SCRIPT_DIR=$(dirname "$0")

pushd "$SCRIPT_DIR" > /dev/null

cd "$SCRIPT_DIR"
WB_PATH=$(pwd)

popd  > /dev/null

cat > cmis-workbench.desktop << EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=CMIS Workbench
Exec=$WB_PATH/workbench.sh
Icon=$WB_PATH/cmis.png
Path=$WB_PATH
Terminal=false
Categories=Development
EOF